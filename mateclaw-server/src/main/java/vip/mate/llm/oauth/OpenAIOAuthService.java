package vip.mate.llm.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.repository.ModelProviderMapper;
import vip.mate.llm.service.ModelProviderService;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI OAuth service — supports three flow modes for the same Codex CLI client_id.
 *
 * <ul>
 *   <li><b>LOCAL</b>: Authorization Code + PKCE with a temporary HTTP server bound on
 *       127.0.0.1:1455 to receive the callback. Used when MateClaw is reached via
 *       localhost — the browser can hit our loopback callback. The Codex CLI client_id
 *       only accepts {@code http://localhost:1455/auth/callback} as redirect_uri, so
 *       this path is impossible for remote deployments.</li>
 *   <li><b>DEVICE_CODE</b>: OAuth 2.0 Device Authorization Grant (RFC 8628) — the
 *       authorization happens entirely inside {@code auth.openai.com}; no callback
 *       server is needed. This is the default for remote deployments. Token exchange
 *       still goes through {@link #exchangeTokenWithVerifier} so downstream
 *       persistence and refresh logic stay identical to the PKCE path. Driven by
 *       {@code OpenAIDeviceCodeService}.</li>
 *   <li><b>MANUAL_PASTE</b>: graceful fallback when LOCAL bind fails or DEVICE_CODE
 *       endpoints are unavailable. The user copies the (unreachable) callback URL
 *       from the browser address bar and pastes it back; we parse {@code code} and
 *       {@code state} from the query string and complete the exchange.</li>
 * </ul>
 *
 * <p>Mode selection:
 * <ol>
 *   <li>Config override {@code mateclaw.oauth.openai.deployment-mode}: one of
 *       {@code local} / {@code device_code} / {@code manual_paste} / {@code auto}
 *       (default). Alias: {@code server} maps to {@code device_code} for
 *       compatibility with older configs that pre-date device code support.</li>
 *   <li>{@code auto} dispatches by Host header: localhost / 127.0.0.1 / ::1 → LOCAL,
 *       any other host → DEVICE_CODE.</li>
 *   <li>LOCAL bind failure degrades to MANUAL_PASTE so the user always has a path
 *       forward.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAIOAuthService {

    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize";
    private static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    /** OpenAI Codex CLI client_id only accepts http://localhost:1455/auth/callback. */
    private static final String REDIRECT_URI = "http://localhost:1455/auth/callback";
    private static final String SCOPES = "openid profile email offline_access";
    private static final String PROVIDER_ID = "openai-chatgpt";
    private static final int CALLBACK_PORT = 1455;
    private static final String DEFAULT_CALLBACK_BIND_HOST = "127.0.0.1";

    private final ModelProviderMapper modelProviderMapper;
    private final ObjectMapper objectMapper;
    private final ModelProviderService modelProviderService;
    private final RestClient restClient = RestClient.create();

    /** state → code_verifier 缓存 */
    private final ConcurrentHashMap<String, String> pendingStates = new ConcurrentHashMap<>();

    /** 当前运行中的回调服务器（用于启动新服务器前关闭旧的） */
    private volatile HttpServer activeCallbackServer;

    /**
     * OAuth flow mode — selects how the authorization code reaches the backend.
     */
    public enum OAuthFlowMode {
        /** Authorization Code + PKCE with a temporary localhost:1455 callback server. */
        LOCAL,
        /** Device Authorization Grant (RFC 8628) — no callback server, polling-based. */
        DEVICE_CODE,
        /** User pastes the callback URL back into the UI. Last-resort fallback. */
        MANUAL_PASTE
    }

    // ==================== OAuth 流程 ====================

    /**
     * 生成授权 URL — 自动按部署形态选 LOCAL / MANUAL_PASTE 模式。
     *
     * @param requestHost 来自 controller 的 Host header（可空 → 默认 LOCAL 行为）
     */
    public OAuthAuthorizeResult buildAuthorizeUrl(String requestHost) {
        OAuthFlowMode mode = resolveFlowMode(requestHost);

        // DEVICE_CODE flow does not produce an authorize URL or PKCE state here — the
        // frontend sees mode=DEVICE_CODE and calls OpenAIDeviceCodeService directly.
        if (mode == OAuthFlowMode.DEVICE_CODE) {
            return new OAuthAuthorizeResult("", "", mode);
        }

        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state = generateState();
        pendingStates.put(state, codeVerifier);

        if (mode == OAuthFlowMode.LOCAL) {
            boolean serverStarted = startCallbackServer(state);
            if (!serverStarted) {
                log.warn("Callback server bind failed on port {} — degrading to MANUAL_PASTE flow",
                        CALLBACK_PORT);
                mode = OAuthFlowMode.MANUAL_PASTE;
            }
        }

        String url = AUTHORIZE_URL
                + "?response_type=code"
                + "&client_id=" + enc(CLIENT_ID)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&scope=" + enc(SCOPES)
                + "&code_challenge=" + enc(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + enc(state)
                + "&id_token_add_organizations=true"
                + "&codex_cli_simplified_flow=true"
                + "&originator=pi";

        return new OAuthAuthorizeResult(url, state, mode);
    }

    /** Backwards-compatible overload (used by tests / older callers). */
    public OAuthAuthorizeResult buildAuthorizeUrl() {
        return buildAuthorizeUrl(null);
    }

    /**
     * Pick the flow mode based on (1) explicit config override, (2) deployment
     * heuristic from the request Host header.
     *
     * <p>Heuristic: if Host is localhost / 127.0.0.1 / ::1, the user is hitting
     * MateClaw on the same machine they'll do the OAuth login on — LOCAL works.
     * Any other host (a domain, a public IP, a private LAN IP) means the user's
     * browser cannot resolve {@code localhost:1455} to MateClaw's server, so we
     * use DEVICE_CODE (browser-agnostic, no callback server needed).
     *
     * <p>Config override values: {@code local} / {@code device_code} /
     * {@code manual_paste} / {@code auto}. {@code server} is kept as an alias for
     * {@code device_code} so older configs do not break.
     */
    OAuthFlowMode resolveFlowMode(String requestHost) {
        String configMode = System.getProperty("mateclaw.oauth.openai.deployment-mode",
                System.getenv("MATECLAW_OAUTH_OPENAI_DEPLOYMENT_MODE"));
        if (configMode != null) {
            String norm = configMode.trim().toLowerCase();
            if ("local".equals(norm)) return OAuthFlowMode.LOCAL;
            if ("device_code".equals(norm) || "server".equals(norm)) return OAuthFlowMode.DEVICE_CODE;
            if ("manual_paste".equals(norm)) return OAuthFlowMode.MANUAL_PASTE;
            // "auto" / unknown → fall through to heuristic
        }

        if (requestHost == null || requestHost.isBlank()) {
            return OAuthFlowMode.LOCAL;
        }
        String host = requestHost.toLowerCase();
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.charAt(0) != '[') {  // not IPv6
            host = host.substring(0, colon);
        }
        if ("localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host)) {
            return OAuthFlowMode.LOCAL;
        }
        return OAuthFlowMode.DEVICE_CODE;
    }

    /**
     * Manual-paste fallback: user copies the (failed-to-load) callback URL from
     * their browser's address bar back into MateClaw. We parse code + state and
     * complete the token exchange.
     *
     * @param pastedUrl e.g. {@code http://localhost:1455/auth/callback?code=XXX&state=YYY}
     *                  — anything from {@code ?} onward is parsed; the host part
     *                  is ignored. Trailing fragments / encoding tolerated.
     */
    public void completeFromPastedUrl(String pastedUrl) {
        if (pastedUrl == null || pastedUrl.isBlank()) {
            throw new MateClawException("err.llm.oauth_paste_empty",
                    "粘贴的 URL 为空，请回到浏览器地址栏复制完整 URL");
        }
        String trimmed = pastedUrl.trim();
        int q = trimmed.indexOf('?');
        if (q < 0) {
            throw new MateClawException("err.llm.oauth_paste_invalid",
                    "粘贴的 URL 没有查询参数，请确认包含 ?code=... 部分");
        }
        // Strip fragment if any (the # part)
        String query = trimmed.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);

        String code = extractParam(query, "code");
        String state = extractParam(query, "state");
        if (code == null || code.isBlank()) {
            throw new MateClawException("err.llm.oauth_paste_no_code",
                    "粘贴的 URL 中缺少 code 参数，登录可能未完成");
        }
        if (state == null || state.isBlank()) {
            throw new MateClawException("err.llm.oauth_paste_no_state",
                    "粘贴的 URL 中缺少 state 参数");
        }
        log.info("OAuth manual-paste completion: state prefix={}", state.substring(0, Math.min(8, state.length())));
        exchangeToken(code, state);
    }

    /**
     * Start the temporary HTTP callback server on localhost:1455.
     *
     * @return {@code true} if bound successfully (caller proceeds with LOCAL mode);
     *         {@code false} if bind failed (port in use OR not on a host that can
     *         bind 127.0.0.1 — caller should fall back to MANUAL_PASTE).
     */
    private boolean startCallbackServer(String expectedState) {
        // 关闭上一次可能残留的回调服务器
        stopActiveCallbackServer();

        // Try to bind synchronously up front so callers can detect failure.
        HttpServer server;
        String bindHost = resolveCallbackBindHost();
        try {
            server = HttpServer.create(new InetSocketAddress(bindHost, CALLBACK_PORT), 0);
        } catch (java.net.BindException e) {
            log.warn("OAuth callback bind failed on {}:{} (in-use or restricted): {}",
                    bindHost, CALLBACK_PORT, e.getMessage());
            pendingStates.remove(expectedState);
            return false;
        } catch (java.io.IOException e) {
            log.warn("OAuth callback HttpServer.create IO error on {}:{}: {}",
                    bindHost, CALLBACK_PORT, e.getMessage());
            pendingStates.remove(expectedState);
            return false;
        }

        final HttpServer boundServer = server;
        CompletableFuture.runAsync(() -> {
            try {
                final HttpServer srv = boundServer;

                server.createContext("/auth/callback", exchange -> {
                    try {
                        String query = exchange.getRequestURI().getQuery();
                        String code = extractParam(query, "code");
                        String state = extractParam(query, "state");

                        if (!expectedState.equals(state)) {
                            String errorHtml = "<html><body><h1>State mismatch</h1><p>OAuth state 不匹配，请重试。</p></body></html>";
                            byte[] bytes = errorHtml.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                            exchange.sendResponseHeaders(400, bytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                            return;
                        }

                        if (code == null || code.isBlank()) {
                            String errorHtml = "<html><body><h1>Missing code</h1><p>缺少授权码。</p></body></html>";
                            byte[] bytes = errorHtml.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                            exchange.sendResponseHeaders(400, bytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                            return;
                        }

                        // 交换 token
                        try {
                            exchangeToken(code, state);
                            String successHtml = "<html><body><h1>✓ 登录成功</h1>"
                                    + "<p>OpenAI OAuth 授权完成，您可以关闭此窗口。</p>"
                                    + "<script>setTimeout(function(){window.close()},2000)</script>"
                                    + "</body></html>";
                            byte[] bytes = successHtml.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                            exchange.sendResponseHeaders(200, bytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                        } catch (Exception e) {
                            log.error("OAuth token 交换失败", e);
                            String errorHtml = "<html><body><h1>Token 交换失败</h1><p>" + e.getMessage() + "</p></body></html>";
                            byte[] bytes = errorHtml.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                            exchange.sendResponseHeaders(500, bytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                        }
                    } finally {
                        // 收到回调后关闭服务器
                        srv.stop(1);
                        activeCallbackServer = null;
                        log.info("OAuth 回调服务器已关闭");
                    }
                });

                boundServer.start();
                activeCallbackServer = boundServer;
                log.info("OAuth 回调服务器已启动，监听 {}:{}，浏览器回调地址 {}",
                        bindHost, CALLBACK_PORT, REDIRECT_URI);

                // 3 分钟超时自动关闭
                CompletableFuture.delayedExecutor(3, TimeUnit.MINUTES).execute(() -> {
                    try {
                        boundServer.stop(0);
                        if (activeCallbackServer == boundServer) {
                            activeCallbackServer = null;
                        }
                        pendingStates.remove(expectedState);
                        log.info("OAuth 回调服务器超时关闭");
                    } catch (Exception ignored) {}
                });

            } catch (Exception e) {
                // bind 已经成功（同步阶段处理过 BindException），这里捕获 createContext /
                // start 等运行时错误。
                log.error("OAuth 回调服务器运行时错误", e);
                pendingStates.remove(expectedState);
                try { boundServer.stop(0); } catch (Exception ignored) {}
            }
        });
        return true;
    }

    /**
     * Exchange an authorization code (from either PKCE callback or device flow) for
     * tokens at {@code /oauth/token}. Shared by both flows so persistence and JWT
     * parsing stay in one place.
     *
     * @param code         authorization code
     * @param codeVerifier PKCE verifier — for LOCAL/MANUAL_PASTE this is the value
     *                     stashed in {@link #pendingStates} during authorize-URL
     *                     generation; for DEVICE_CODE this comes back as part of
     *                     the device-auth poll response
     * @param redirectUri  redirect_uri presented during authorize — must match the
     *                     value the original authorize call used. {@link #REDIRECT_URI}
     *                     for PKCE; {@code https://auth.openai.com/deviceauth/callback}
     *                     for device flow.
     */
    void exchangeTokenWithVerifier(String code, String codeVerifier, String redirectUri) {
        String body = "grant_type=authorization_code"
                + "&client_id=" + enc(CLIENT_ID)
                + "&code=" + enc(code)
                + "&code_verifier=" + enc(codeVerifier)
                + "&redirect_uri=" + enc(redirectUri);
        JsonNode tokenResponse = postTokenRequest(body);
        saveTokens(tokenResponse);
    }

    /** PKCE callback path — looks up the verifier by state and delegates. */
    private void exchangeToken(String code, String state) {
        String codeVerifier = pendingStates.remove(state);
        if (codeVerifier == null) {
            throw new MateClawException("err.llm.oauth_state_invalid", "无效的 OAuth state，可能已过期或重复使用");
        }
        exchangeTokenWithVerifier(code, codeVerifier, REDIRECT_URI);
    }

    /**
     * 刷新 access_token
     */
    public void refreshToken() {
        ModelProviderEntity provider = getProvider();
        if (!StringUtils.hasText(provider.getOauthRefreshToken())) {
            throw new MateClawException("err.llm.oauth_no_refresh", "无 refresh_token，请重新登录");
        }

        String body = "grant_type=refresh_token"
                + "&refresh_token=" + enc(provider.getOauthRefreshToken())
                + "&client_id=" + enc(CLIENT_ID);

        JsonNode tokenResponse = postTokenRequest(body);
        saveTokens(tokenResponse);
    }

    /**
     * 确保 access_token 有效（过期时自动刷新）
     */
    public String ensureValidAccessToken() {
        ModelProviderEntity provider = getProvider();
        if (!StringUtils.hasText(provider.getOauthAccessToken())) {
            throw new MateClawException("err.llm.oauth_not_connected", "未连接 OpenAI OAuth，请先登录");
        }

        // 提前 5 分钟刷新
        if (provider.getOauthExpiresAt() != null
                && System.currentTimeMillis() > provider.getOauthExpiresAt() - 300_000) {
            log.info("OpenAI OAuth token 即将过期，自动刷新...");
            refreshToken();
            provider = getProvider();
        }

        return provider.getOauthAccessToken();
    }

    /**
     * 获取 account_id（用于请求 header）
     */
    public String getAccountId() {
        ModelProviderEntity provider = getProvider();
        String accountId = provider.getOauthAccountId();
        // 兼容修复：旧版 JWT 解析字段名错误导致 accountId 为空，从现有 token 重新解析
        if (!StringUtils.hasText(accountId) && StringUtils.hasText(provider.getOauthAccessToken())) {
            accountId = extractAccountIdFromJwt(provider.getOauthAccessToken());
            if (StringUtils.hasText(accountId)) {
                provider.setOauthAccountId(accountId);
                modelProviderMapper.updateById(provider);
                log.info("从已有 token 重新解析并保存 accountId={}", accountId);
            }
        }
        return accountId;
    }

    /**
     * 清除 OAuth 凭证
     */
    public void revokeToken() {
        // MyBatis Plus updateById 默认跳过 null 字段，必须用 LambdaUpdateWrapper 显式置空
        modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getProviderId, PROVIDER_ID)
                .set(ModelProviderEntity::getOauthAccessToken, null)
                .set(ModelProviderEntity::getOauthRefreshToken, null)
                .set(ModelProviderEntity::getOauthExpiresAt, null)
                .set(ModelProviderEntity::getOauthAccountId, null));
        log.info("OpenAI OAuth 凭证已清除");
    }

    /**
     * 获取 OAuth 连接状态
     */
    public OAuthStatusResult getStatus() {
        ModelProviderEntity provider = modelProviderMapper.selectById(PROVIDER_ID);
        if (provider == null || !StringUtils.hasText(provider.getOauthAccessToken())) {
            return new OAuthStatusResult(false, false, null);
        }
        boolean expired = provider.getOauthExpiresAt() != null
                && System.currentTimeMillis() > provider.getOauthExpiresAt();
        return new OAuthStatusResult(true, expired, provider.getOauthExpiresAt());
    }

    // ==================== 内部工具方法 ====================

    private JsonNode postTokenRequest(String formBody) {
        try {
            String response = restClient.post()
                    .uri(TOKEN_URL)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .body(formBody)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            log.error("OpenAI OAuth token 请求失败", e);
            throw new MateClawException("err.llm.oauth_exchange_failed", "OAuth token 交换失败: " + e.getMessage());
        }
    }

    private void saveTokens(JsonNode tokenResponse) {
        String accessToken = tokenResponse.path("access_token").asText(null);
        String refreshToken = tokenResponse.path("refresh_token").asText(null);
        int expiresIn = tokenResponse.path("expires_in").asInt(3600);

        if (!StringUtils.hasText(accessToken)) {
            throw new MateClawException("err.llm.oauth_no_token", "OAuth 响应中缺少 access_token");
        }

        String accountId = extractAccountIdFromJwt(accessToken);

        ModelProviderEntity provider = getProvider();
        provider.setOauthAccessToken(accessToken);
        if (StringUtils.hasText(refreshToken)) {
            provider.setOauthRefreshToken(refreshToken);
        }
        provider.setOauthExpiresAt(System.currentTimeMillis() + (long) expiresIn * 1000);
        if (StringUtils.hasText(accountId)) {
            provider.setOauthAccountId(accountId);
        }
        modelProviderMapper.updateById(provider);
        modelProviderService.activateFirstModelIfDefaultUnavailable(PROVIDER_ID);
        log.info("OpenAI OAuth token 已保存，expires_in={}s, accountId={}", expiresIn, accountId);
    }

    /**
     * 从 JWT access_token 中解析 chatgpt_account_id
     */
    String extractAccountIdFromJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(payload);
            JsonNode auth = node.path("https://api.openai.com/auth");
            if (!auth.isMissingNode()) {
                String accountId = auth.path("chatgpt_account_id").asText(null);
                if (accountId == null) {
                    accountId = auth.path("chatgpt_account_user_id").asText(null);
                }
                return accountId;
            }
            return null;
        } catch (Exception e) {
            log.warn("解析 JWT 提取 account_id 失败", e);
            return null;
        }
    }

    private ModelProviderEntity getProvider() {
        ModelProviderEntity provider = modelProviderMapper.selectById(PROVIDER_ID);
        if (provider == null) {
            throw new MateClawException("err.llm.chatgpt_not_configured", "OpenAI ChatGPT provider 未配置，请检查数据库初始化");
        }
        return provider;
    }

    private void stopActiveCallbackServer() {
        HttpServer existing = activeCallbackServer;
        if (existing != null) {
            try {
                existing.stop(0);
                log.info("已关闭上一个残留的 OAuth 回调服务器");
            } catch (Exception ignored) {}
            activeCallbackServer = null;
        }
    }

    String resolveCallbackBindHost() {
        String configured = System.getProperty("mateclaw.oauth.openai.callback-bind-host",
                System.getenv("MATECLAW_OAUTH_OPENAI_CALLBACK_BIND_HOST"));
        if (!StringUtils.hasText(configured)) {
            return DEFAULT_CALLBACK_BIND_HOST;
        }
        return configured.trim();
    }

    // ==================== PKCE 工具 ====================

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return base64UrlEncode(bytes);
    }

    private String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return base64UrlEncode(digest);
        } catch (Exception e) {
            throw new MateClawException("err.llm.pkce_failed", "PKCE code_challenge 生成失败: " + e.getMessage());
        }
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String padBase64(String base64) {
        int mod = base64.length() % 4;
        if (mod > 0) {
            base64 += "=".repeat(4 - mod);
        }
        return base64;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String extractParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ==================== 结果类 ====================

    /**
     * @param authorizeUrl OpenAI 授权 URL
     * @param state PKCE state（前端可不关心）
     * @param mode 通知前端用哪种 UX：LOCAL 自动 callback / MANUAL_PASTE 引导粘贴
     */
    public record OAuthAuthorizeResult(String authorizeUrl, String state, OAuthFlowMode mode) {
        /** Backwards-compatible 2-arg constructor (defaults to LOCAL). */
        public OAuthAuthorizeResult(String authorizeUrl, String state) {
            this(authorizeUrl, state, OAuthFlowMode.LOCAL);
        }
    }

    public record OAuthStatusResult(boolean connected, boolean expired, Long expiresAt) {}
}
