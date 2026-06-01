package vip.mate.tool.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Store of bytes produced by tools (e.g. {@code DocxRenderTool}) and served by
 * {@link GeneratedFileController}. Each entry is written to disk under
 * {@link #DEFAULT_STORAGE_DIR} and mirrored in an in-memory map for fast reads.
 *
 * <p>Persistence is what makes download links durable: the bytes survive both
 * cache eviction and a JVM restart, so a link a user clicks minutes — or days —
 * after generation still resolves instead of 404ing. Entries are retained for
 * {@link #TTL} and a scheduled sweep removes expired files. The download URL
 * embeds a random {@link UUID}, which acts as the only access credential.
 */
@Slf4j
@Component
public class GeneratedFileCache {

    /** How long a generated file remains downloadable after creation. */
    public static final Duration TTL = Duration.ofDays(7);

    /** Default on-disk location for persisted generated files. */
    public static final Path DEFAULT_STORAGE_DIR = Paths.get("data", "generated-files");

    /** How often the expired-file sweep runs (6 hours). Must be a compile-time
     *  constant for use in {@link Scheduled#fixedDelay()}. */
    private static final long CLEANUP_INTERVAL_MS = 6L * 60 * 60 * 1000;

    /** Guards path resolution: only server-issued UUID-shaped ids are accepted. */
    private static final Pattern ID_RE = Pattern.compile("[a-zA-Z0-9-]{1,64}");

    private static final String META_SUFFIX = ".meta";

    /**
     * Upper bound on bytes held in memory. Disk is the source of truth and
     * retains entries for {@link #TTL}; this map is only a hot-read cache, so
     * capping it keeps heap bounded regardless of how many files are produced
     * within the retention window. A miss simply reloads from disk.
     */
    private static final int MAX_MEMORY_ENTRIES = 256;

    /**
     * URL pattern for generated files served by {@code GeneratedFileController}.
     * Public so channel adapters and graph nodes share a single source of truth.
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

    private final Path storageDir;

    /**
     * Access-ordered LRU bounded to {@link #MAX_MEMORY_ENTRIES}: the eldest
     * entry is dropped from memory once the cap is exceeded (the persisted
     * file stays on disk and is reloaded on the next read).
     */
    private final Map<String, Entry> entries = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > MAX_MEMORY_ENTRIES;
                }
            });

    public GeneratedFileCache() {
        this(DEFAULT_STORAGE_DIR);
    }

    public GeneratedFileCache(Path storageDir) {
        this.storageDir = storageDir.normalize();
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            log.warn("Could not create generated-files dir {}: {}", this.storageDir, e.toString());
        }
    }

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
        String id = UUID.randomUUID().toString();
        long expireAt = System.currentTimeMillis() + TTL.toMillis();
        Entry entry = new Entry(bytes, filename, mimeType, expireAt);
        entries.put(id, entry);
        persist(id, entry);
        log.debug("Cached generated file id={} filename={} bytes={}", id, filename,
                bytes != null ? bytes.length : 0);
        return id;
    }

    /**
     * Look up an entry. Returns {@link Optional#empty()} if missing or expired.
     * Falls back to disk on an in-memory miss so links survive eviction and
     * JVM restarts; expired entries are removed as a side-effect.
     */
    public Optional<Entry> get(String id) {
        if (id == null || !ID_RE.matcher(id).matches()) {
            return Optional.empty();
        }
        Entry entry = entries.get(id);
        if (entry == null) {
            entry = loadFromDisk(id);
            if (entry != null) {
                entries.put(id, entry);
            }
        }
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired()) {
            evict(id);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private void persist(String id, Entry entry) {
        if (entry.bytes() == null) {
            return;
        }
        try {
            Files.write(storageDir.resolve(id), entry.bytes());
            // expireAt \t mimeType \t base64(filename) — filename is base64-encoded
            // so arbitrary unicode / separators round-trip without escaping.
            String meta = entry.expireAt()
                    + "\t" + (entry.mimeType() == null ? "" : entry.mimeType())
                    + "\t" + Base64.getEncoder().encodeToString(
                            (entry.filename() == null ? "" : entry.filename()).getBytes(StandardCharsets.UTF_8));
            Files.writeString(storageDir.resolve(id + META_SUFFIX), meta);
        } catch (IOException e) {
            // Best-effort: an in-memory entry still serves the current process.
            log.warn("Could not persist generated file id={}: {}", id, e.toString());
        }
    }

    private Entry loadFromDisk(String id) {
        Path bin = storageDir.resolve(id).normalize();
        Path meta = storageDir.resolve(id + META_SUFFIX).normalize();
        // Containment guard — id is already validated, this is defence in depth.
        if (!bin.startsWith(storageDir) || !Files.isRegularFile(bin) || !Files.isRegularFile(meta)) {
            return null;
        }
        try {
            String[] parts = Files.readString(meta).split("\t", 3);
            long expireAt = Long.parseLong(parts[0].trim());
            String mimeType = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
            String filename = parts.length > 2 && !parts[2].isEmpty()
                    ? new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8)
                    : id;
            byte[] bytes = Files.readAllBytes(bin);
            return new Entry(bytes, filename, mimeType, expireAt);
        } catch (Exception e) {
            log.warn("Could not load generated file id={}: {}", id, e.toString());
            return null;
        }
    }

    private void evict(String id) {
        entries.remove(id);
        try {
            Files.deleteIfExists(storageDir.resolve(id));
            Files.deleteIfExists(storageDir.resolve(id + META_SUFFIX));
        } catch (IOException e) {
            log.debug("Could not delete generated file id={}: {}", id, e.toString());
        }
    }

    /**
     * Drop expired entries from memory and disk. Runs on a fixed delay; also
     * sweeps orphaned files left by an unclean shutdown.
     */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS, initialDelay = CLEANUP_INTERVAL_MS)
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        // entrySet() of a synchronizedMap must be iterated while holding its lock.
        synchronized (entries) {
            entries.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
        }
        if (!Files.isDirectory(storageDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(storageDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(META_SUFFIX))
                    .forEach(metaPath -> {
                        String name = metaPath.getFileName().toString();
                        String id = name.substring(0, name.length() - META_SUFFIX.length());
                        try {
                            long expireAt = Long.parseLong(
                                    Files.readString(metaPath).split("\t", 2)[0].trim());
                            if (expireAt <= now) {
                                evict(id);
                            }
                        } catch (Exception e) {
                            log.debug("Skipping unreadable meta {}: {}", name, e.toString());
                        }
                    });
        } catch (IOException e) {
            log.warn("Generated-files cleanup sweep failed: {}", e.toString());
        }
    }

    /**
     * Replace any {@code /api/v1/files/generated/{id}} URL in {@code text}
     * whose id is NOT present (or has expired) with
     * {@link #MISSING_REFERENCE_NOTICE}. URLs whose ids ARE live are left
     * intact so downstream channel adapters can still rewrite them into
     * native attachments.
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
            boolean live = get(id).isPresent();
            String replacement = live ? m.group(0) : MISSING_REFERENCE_NOTICE;
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
