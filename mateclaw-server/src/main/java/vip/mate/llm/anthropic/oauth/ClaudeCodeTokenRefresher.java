package vip.mate.llm.anthropic.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vip.mate.exception.MateClawException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC-062: refresh Claude Code OAuth access tokens via Anthropic's public
 * token endpoint chain.
 *
 * <p>Anthropic exposes the same refresh endpoint at two hostnames:
 * <ul>
 *   <li>{@code https://platform.claude.com/v1/oauth/token} — primary, used by
 *       Claude Code &gt;= 2.1.114.</li>
 *   <li>{@code https://console.anthropic.com/v1/oauth/token} — legacy alias,
 *       still active. Used as a fallback for transient platform.claude.com
 *       outages.</li>
 * </ul>
 *
 * <p>The refresh request is a vanilla OAuth 2.0 refresh-token grant with the
 * public Claude Code {@code client_id}. We must spoof the Claude Code
 * {@code User-Agent} — Anthropic's edge filters drop unrecognised UAs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeCodeTokenRefresher {

    /** Public Claude Code OAuth client_id. */
    static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

    /** Endpoints tried in order until one succeeds. */
    static final List<String> ENDPOINTS = List.of(
            "https://platform.claude.com/v1/oauth/token",
            "https://console.anthropic.com/v1/oauth/token");

    private final ObjectMapper objectMapper;
    private final ClaudeCodeVersionDetector versionDetector;
    private final RestClient restClient = RestClient.create();

    /**
     * Exchange a refresh_token for a fresh access_token.
     *
     * @return credentials carrying {@link ClaudeCodeCredentials.Source#REFRESH_RESPONSE}.
     *         The caller is responsible for combining this with the original
     *         source so the writer knows where to persist.
     * @throws MateClawException when all endpoints fail.
     */
    public ClaudeCodeCredentials refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new MateClawException("err.anthropic.token_expired_no_refresh",
                    "Claude Code refresh_token 缺失，无法刷新");
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", CLIENT_ID);
        String body = formEncode(params);

        Exception lastException = null;
        for (String endpoint : ENDPOINTS) {
            try {
                String response = restClient.post()
                        .uri(endpoint)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        // Bare UA — see ClaudeCodeApiHeaders.userAgent() javadoc
                        // for why the (external, cli) suffix would trip Anthropic's
                        // anti-abuse fingerprint.
                        .header(HttpHeaders.USER_AGENT,
                                "claude-cli/" + versionDetector.get())
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return parseTokenResponse(response, refreshToken);
            } catch (Exception e) {
                lastException = e;
                log.debug("[ClaudeCodeRefresher] {} failed: {}", endpoint, e.getMessage());
            }
        }
        String detail = lastException != null ? lastException.getMessage() : "unknown";
        throw new MateClawException("err.anthropic.refresh_failed",
                "Claude Code token 刷新失败: " + detail);
    }

    /** Package-private for unit testing. */
    ClaudeCodeCredentials parseTokenResponse(String body, String fallbackRefreshToken) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String accessToken = json.path("access_token").asText("");
            if (accessToken.isBlank()) {
                throw new MateClawException("err.anthropic.refresh_failed",
                        "Refresh response missing access_token");
            }
            String refreshToken = json.has("refresh_token") && !json.path("refresh_token").asText("").isBlank()
                    ? json.path("refresh_token").asText()
                    : fallbackRefreshToken;
            // Some endpoints return expires_in (seconds); some return expires_at (ms).
            long expiresAtMs;
            if (json.has("expires_at")) {
                expiresAtMs = json.path("expires_at").asLong(0L);
            } else {
                long expiresInSec = json.path("expires_in").asLong(0L);
                expiresAtMs = expiresInSec > 0
                        ? System.currentTimeMillis() + (expiresInSec * 1000L)
                        : 0L;
            }
            return new ClaudeCodeCredentials(
                    accessToken,
                    refreshToken,
                    expiresAtMs,
                    ClaudeCodeCredentials.Source.REFRESH_RESPONSE);
        } catch (MateClawException e) {
            throw e;
        } catch (Exception e) {
            throw new MateClawException("err.anthropic.refresh_failed",
                    "Refresh response parse failed: " + e.getMessage());
        }
    }

    private static String formEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
