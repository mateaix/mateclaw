package vip.mate.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vip.mate.common.net.SsrfProperties;
import vip.mate.hook.action.*;
import vip.mate.hook.model.HookEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 按 {@link HookEntity} 的 {@code action_kind} + {@code action_config} 装配 {@link HookAction}。
 *
 * <p>解析一次后由 {@code HookRegistry} 缓存复用；HTTP RestClient 在本 factory 内持有单例
 * 连接池，避免为每个 hook 重新构造。</p>
 */
@Component
@RequiredArgsConstructor
public class HookActionFactory {

    private final ObjectMapper objectMapper;
    private final HookProperties props;
    private final SsrfProperties ssrfProperties;

    /** 懒加载的共享 RestClient；所有 HttpAction 复用同一连接池。 */
    private volatile RestClient httpRestClient;

    public HookAction build(HookEntity e) {
        HookAction.Kind kind = HookAction.Kind.valueOf(e.getActionKind());
        JsonNode cfg = readConfig(e.getActionConfig());
        long timeoutMs = e.getTimeoutMs() == null ? 3000L : e.getTimeoutMs();

        HookAction action = switch (kind) {
            case BUILTIN -> new BuiltinAction(
                    text(cfg, "op", "log.info"),
                    text(cfg, "arg", ""));
            // RFC-03 Lane H1 — hmacSecret + signatureHeader are optional; null /
            // blank disables signing (legacy behavior). Receivers that need
            // origin-verification re-compute SHA-256 HMAC on the raw body.
            case HTTP -> new HttpAction(
                    sharedRestClient(),
                    text(cfg, "method", "POST"),
                    URI.create(required(cfg, "url")),
                    text(cfg, "body", null),
                    props.getTrustedDomains(),
                    ssrfProperties.getSsrfAllowlist(),
                    timeoutMs,
                    text(cfg, "hmacSecret", null),
                    text(cfg, "signatureHeader", null));
            case SHELL -> new ShellAction(required(cfg, "command"));
            case CHANNEL_MESSAGE -> new ChannelMessageAction(
                    required(cfg, "channelType"),
                    text(cfg, "message", ""));
        };
        action.validate();
        return action;
    }

    private RestClient sharedRestClient() {
        var existing = this.httpRestClient;
        if (existing != null) return existing;
        synchronized (this) {
            if (this.httpRestClient != null) return this.httpRestClient;
            ClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                            .connectTimeout(props.getHttp().getConnectTimeout())
                            .build());
            ((JdkClientHttpRequestFactory) requestFactory).setReadTimeout(props.getHttp().getReadTimeout());
            this.httpRestClient = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build();
            return this.httpRestClient;
        }
    }

    private JsonNode readConfig(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid action_config json: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode n, String field, String def) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? def : v.asText();
    }

    private static String required(JsonNode n, String field) {
        String v = text(n, field, null);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required action config field: " + field);
        }
        return v;
    }

    /** 测试辅助：让 sharedRestClient 可注入。 */
    void overrideRestClientForTest(RestClient rc) {
        this.httpRestClient = rc;
    }
}
