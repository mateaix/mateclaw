package vip.mate.tool.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory cache of bytes produced by tools (e.g. {@code DocxRenderTool}) and
 * served by {@link GeneratedFileController}. Entries expire after {@link #TTL}
 * and are evicted lazily on every {@link #put} call.
 *
 * <p>The cache is process-local and intentionally not persisted: a JVM restart
 * invalidates all outstanding download links. The download URL embeds a random
 * {@link UUID}, which acts as the only access credential.
 */
@Slf4j
@Component
public class GeneratedFileCache {

    public static final Duration TTL = Duration.ofMinutes(10);

    /**
     * URL pattern for in-memory generated files served by
     * {@code GeneratedFileController}. Public so channel adapters and graph
     * nodes share a single source of truth.
     */
    public static final Pattern GENERATED_URL_PATTERN =
            Pattern.compile("/api/v1/files/generated/([a-zA-Z0-9-]+)");

    /**
     * User-visible warning swapped in for a cache-miss URL. Identical
     * wording to the channel-side fallback so users see one consistent
     * message regardless of which surface (web, IM, etc.) renders it.
     */
    public static final String MISSING_REFERENCE_NOTICE =
            "⚠️ 文件未真正生成（模型未调用文档生成工具），请重新发送请求";

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public record Entry(byte[] bytes, String filename, String mimeType, long expireAt) {

        public boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /**
     * Store the given bytes and return a fresh, unguessable identifier.
     * Callers should embed the id in a URL of the form
     * {@code /api/v1/files/generated/{id}}.
     */
    public String put(byte[] bytes, String filename, String mimeType) {
        evictExpired();
        String id = UUID.randomUUID().toString();
        long expireAt = System.currentTimeMillis() + TTL.toMillis();
        entries.put(id, new Entry(bytes, filename, mimeType, expireAt));
        log.debug("Cached generated file id={} filename={} bytes={}", id, filename, bytes.length);
        return id;
    }

    /**
     * Look up an entry. Returns {@link Optional#empty()} if missing or expired
     * (expired entries are removed as a side-effect).
     */
    public Optional<Entry> get(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired()) {
            entries.remove(id, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
    }

    /**
     * Replace any {@code /api/v1/files/generated/{id}} URL in {@code text}
     * whose id is NOT present (or has expired) in this cache with
     * {@link #MISSING_REFERENCE_NOTICE}. URLs whose ids ARE in the cache are
     * left intact so downstream channel adapters can still rewrite them
     * into native attachments.
     *
     * <p>Cache misses are nearly always LLM hallucinations — the model
     * emitted a UUID-shaped string without ever calling a render tool.
     * Without this scrub, every channel that receives the answer (Web,
     * Slack, DingTalk, Telegram, …) would render a clickable link that
     * 404s, and IM clients save the 404 HTML body as a {@code .docx}
     * which users then report as "corrupted file".
     */
    public String scrubMissingReferences(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = GENERATED_URL_PATTERN.matcher(text);
        if (!m.find()) return text;
        StringBuilder out = new StringBuilder();
        m.reset();
        while (m.find()) {
            String id = m.group(1);
            Entry entry = entries.get(id);
            boolean live = entry != null && !entry.expired();
            String replacement = live ? m.group(0) : MISSING_REFERENCE_NOTICE;
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
