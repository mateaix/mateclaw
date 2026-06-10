package vip.mate.channel.feishu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.core.enums.BaseUrlEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.repository.ChannelMapper;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-channelId cache of {@link Client} instances backed by Feishu's
 * official {@code oapi-sdk}. The SDK manages the
 * {@code tenant_access_token} lifecycle internally — callers never
 * touch tokens.
 *
 * <p><b>Invalidation</b>: when a Feishu channel row is mutated
 * (credential rotation, domain switch, deletion), {@code ChannelService}
 * calls {@link #evict(Long)} to drop the stale client; the next
 * {@link #client(Long)} call rebuilds from current config.
 *
 * <p>Each cache entry also carries a {@code fingerprint} (appId +
 * secret hash + domain). A subtle in-place edit that misses the
 * eviction hook is still caught on next lookup: the fingerprint
 * mismatch forces a rebuild.
 *
 * <p>Why a dedicated factory rather than building on the adapter's
 * existing hand-rolled HTTP path: the SDK handles multipart uploads,
 * CardKit streaming, contact lookups, calendar/docx, reactions, and
 * message updates uniformly — the adapter's hand-rolled
 * {@code HttpClient} only covers basic message send. Every new send
 * path in this codebase should go through this factory and the SDK.
 * The adapter's pre-existing hand-rolled paths stay untouched
 * (surgical principle) — two token caches coexisting is a negligible
 * memory cost.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuClientFactory {

    private static final String CHANNEL_TYPE = "feishu";

    private final ChannelMapper channelMapper;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();

    /** Cache entry — fingerprint guards against missed-eviction in-place edits. */
    private record Cached(String fingerprint, Client client) {}

    /**
     * Get (building lazily if absent) the SDK client for the given
     * Feishu channel.
     *
     * @throws IllegalArgumentException if the channel does not exist
     *         or is not of type {@code feishu}
     * @throws IllegalStateException    if the channel row is missing
     *         {@code app_id} or {@code app_secret}
     */
    public Client client(Long channelId) {
        if (channelId == null) {
            throw new IllegalArgumentException("channelId must not be null");
        }
        ChannelEntity ch = channelMapper.selectById(channelId);
        if (ch == null) {
            throw new IllegalArgumentException("Channel not found: " + channelId);
        }
        if (!CHANNEL_TYPE.equals(ch.getChannelType())) {
            throw new IllegalArgumentException(
                    "Channel " + channelId + " is type=" + ch.getChannelType()
                            + ", not " + CHANNEL_TYPE);
        }
        Map<String, Object> cfg = parseConfig(ch.getConfigJson());
        String appId = asString(cfg.get("app_id"));
        String appSecret = asString(cfg.get("app_secret"));
        String domain = asStringOr(cfg.get("domain"), "feishu");
        if (appId == null || appSecret == null) {
            throw new IllegalStateException(
                    "Feishu channel " + channelId + " missing app_id / app_secret");
        }
        String fp = fingerprint(appId, appSecret, domain);
        Cached existing = cache.get(channelId);
        if (existing != null && existing.fingerprint().equals(fp)) {
            return existing.client();
        }
        Client built = build(appId, appSecret, domain);
        cache.put(channelId, new Cached(fp, built));
        if (existing != null) {
            log.info("[feishu-client-factory] Rebuilt client for channel {} (config changed)", channelId);
        } else {
            log.info("[feishu-client-factory] Built client for channel {} (domain={})", channelId, domain);
        }
        return built;
    }

    /**
     * Drop the cached client for {@code channelId}. Idempotent — safe
     * to call for non-feishu channels (no-op) and for channels with no
     * cached client. Called by {@code ChannelService} on every
     * Feishu channel update / delete / toggle.
     */
    public void evict(Long channelId) {
        if (channelId == null) return;
        if (cache.remove(channelId) != null) {
            log.info("[feishu-client-factory] Evicted client for channel {}", channelId);
        }
    }

    /** Test hook — visible for assertion. */
    int cachedCount() {
        return cache.size();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Client build(String appId, String appSecret, String domain) {
        BaseUrlEnum baseUrl = "lark".equalsIgnoreCase(domain)
                ? BaseUrlEnum.LarkSuite
                : BaseUrlEnum.FeiShu;
        return Client.newBuilder(appId, appSecret)
                .openBaseUrl(baseUrl)
                .build();
    }

    private static String fingerprint(String appId, String appSecret, String domain) {
        return appId + '|' + Objects.hash(appSecret) + '|' + domain.toLowerCase();
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[feishu-client-factory] Failed to parse configJson: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String asStringOr(Object v, String fallback) {
        String s = asString(v);
        return s == null ? fallback : s;
    }
}
