package vip.mate.auth.sso.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import vip.mate.auth.sso.SsoProperties;
import vip.mate.exception.MateClawException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 飞书 OAuth2 SSO Provider。
 * <p>
 * 授权码流程:
 * <ol>
 *   <li>app_id + app_secret → app_access_token (有效期 2h, Caffeine 缓存 ~110min)</li>
 *   <li>app_access_token + code → user_access_token (飞书 OIDC 端点)</li>
 *   <li>user_access_token → 用户信息 (open_id / union_id / name / email / avatar)</li>
 * </ol>
 *
 * <p>HTTP 调用模式复刻 {@code FeishuChannelAdapter.getUserName}:
 * JDK {@code HttpClient} + Jackson {@code ObjectMapper} + 飞书 {@code code==0} 约定。
 * 注意 SSO 的 app_access_token 与 IM 渠道的 tenant_access_token 是不同 token、不同应用, 无法复用。
 *
 * <p>apiBase 按 {@code domain} 切换: {@code feishu} → {@code https://open.feishu.cn};
 * {@code lark} → {@code https://open.larksuite.com}。
 *
 * @author MateClaw Team
 */
public class FeishuSsoProvider implements SsoProvider {

    private static final String PROVIDER_ID = "feishu";
    private static final String DISPLAY_NAME = "飞书";

    private final SsoProperties.Feishu cfg;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiBase;

    /** app_access_token 缓存: 飞书有效期 2h, TTL 110min 留余量 */
    private final Cache<String, String> appTokenCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(110))
            .maximumSize(1)
            .build();

    public FeishuSsoProvider(SsoProperties.Feishu cfg, ObjectMapper objectMapper) {
        this.cfg = cfg;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.apiBase = "lark".equalsIgnoreCase(cfg.getDomain())
                ? "https://open.larksuite.com"
                : "https://open.feishu.cn";
    }

    @Override
    public String id() { return PROVIDER_ID; }

    @Override
    public String displayName() { return DISPLAY_NAME; }

    @Override
    public String authorizeUrl(String state) {
        return apiBase + "/open-apis/authen/v1/authorize"
                + "?app_id=" + cfg.getAppId()
                + "&redirect_uri=" + encode(cfg.getRedirectUri())
                + "&response_type=code"
                + "&state=" + encode(state);
    }

    @Override
    public SsoUserInfo resolve(String code, String state) {
        String appAccessToken = getAppAccessToken();
        String userAccessToken = exchangeUserAccessToken(code, appAccessToken);
        return fetchUserInfo(userAccessToken);
    }

    // ------------------------------------------------------------------
    // 飞书 API 调用
    // ------------------------------------------------------------------

    /**
     * 获取 app_access_token (带缓存)。POST /auth/v3/app_access_token/internal。
     */
    private String getAppAccessToken() {
        String cached = appTokenCache.getIfPresent("token");
        if (cached != null) return cached;

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "app_id", cfg.getAppId(),
                    "app_secret", cfg.getAppSecret()));
            Map<String, Object> resp = postJson(
                    apiBase + "/open-apis/auth/v3/app_access_token/internal", body, null);
            checkCode(resp, "app_access_token");
            String token = (String) resp.get("app_access_token");
            if (token == null || token.isBlank()) {
                throw new MateClawException("err.sso.feishu_token_empty",
                        502, "飞书未返回 app_access_token");
            }
            appTokenCache.put("token", token);
            return token;
        } catch (MateClawException e) {
            throw e;
        } catch (Exception e) {
            throw new MateClawException("err.sso.feishu_app_token_failed",
                    502, "获取飞书 app_access_token 失败: " + e.getMessage());
        }
    }

    /**
     * code → user_access_token。POST /authen/v1/oidc/access_token。
     */
    private String exchangeUserAccessToken(String code, String appAccessToken) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "grant_type", "authorization_code",
                    "code", code));
            Map<String, Object> resp = postJson(
                    apiBase + "/open-apis/authen/v1/oidc/access_token", body, appAccessToken);
            checkCode(resp, "user_access_token");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) {
                throw new MateClawException("err.sso.feishu_no_data", 502, "飞书未返回 token 数据");
            }
            String token = (String) data.get("access_token");
            if (token == null || token.isBlank()) {
                throw new MateClawException("err.sso.feishu_user_token_empty",
                        502, "飞书未返回 user_access_token");
            }
            return token;
        } catch (MateClawException e) {
            throw e;
        } catch (Exception e) {
            throw new MateClawException("err.sso.feishu_code_exchange_failed",
                    502, "飞书授权码换取 token 失败: " + e.getMessage());
        }
    }

    /**
     * user_access_token → 用户信息。GET /authen/v1/user_info。
     */
    @SuppressWarnings("unchecked")
    private SsoUserInfo fetchUserInfo(String userAccessToken) {
        try {
            Map<String, Object> resp = getJson(
                    apiBase + "/open-apis/authen/v1/user_info", userAccessToken);
            checkCode(resp, "user_info");
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) {
                throw new MateClawException("err.sso.feishu_no_user_data",
                        502, "飞书未返回用户信息");
            }
            String openId = str(data.get("open_id"));
            if (openId == null || openId.isBlank()) {
                throw new MateClawException("err.sso.feishu_no_open_id",
                        502, "飞书用户信息缺少 open_id");
            }
            return new SsoUserInfo(
                    openId,
                    str(data.get("union_id")),
                    str(data.get("name")),
                    str(data.get("avatar")),
                    str(data.get("email")),
                    str(data.get("mobile")));
        } catch (MateClawException e) {
            throw e;
        } catch (Exception e) {
            throw new MateClawException("err.sso.feishu_user_info_failed",
                    502, "获取飞书用户信息失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // HTTP helpers (复刻 FeishuChannelAdapter.getUserName 模式)
    // ------------------------------------------------------------------

    private Map<String, Object> postJson(String url, String jsonBody, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readValue(response.body(), Map.class);
    }

    private Map<String, Object> getJson(String url, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + bearerToken)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readValue(response.body(), Map.class);
    }

    private void checkCode(Map<String, Object> resp, String api) {
        Integer code = resp.get("code") instanceof Number n ? n.intValue() : null;
        if (code == null || code != 0) {
            String msg = str(resp.get("msg"));
            throw new MateClawException("err.sso.feishu_api_error",
                    502, "飞书 " + api + " 接口返回错误: code=" + code + ", msg=" + msg);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
