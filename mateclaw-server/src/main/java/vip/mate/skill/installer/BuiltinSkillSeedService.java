package vip.mate.skill.installer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Builtin skill seed service — RFC-044 §4.2.
 *
 * <p>Scans {@code classpath:skills/*\/SKILL.md} on startup, parses each
 * frontmatter, and upserts the row into {@code mate_skill} so the bundled
 * SKILL.md becomes the single source of truth.
 *
 * <p>Replaces (and obsoletes) the per-skill {@code INSERT INTO mate_skill}
 * blocks in {@code data-{locale}.sql}. Those are kept for one release as a
 * compatibility shim — see RFC-044 §4.2 step 3.
 *
 * <p><b>Upsert key:</b> {@code name}. The mate_skill primary key {@code id}
 * is preserved on update, so nothing referencing a skill by id breaks.
 *
 * <p><b>Field merge policy:</b> frontmatter wins where present; if the
 * frontmatter omits a field (e.g. {@code icon}, {@code tags}, {@code author}),
 * the existing DB value is preserved rather than blanked out. New skills
 * (with no DB row yet) get sensible defaults.
 *
 * <p><b>Order:</b> 110 — runs after Flyway and {@link
 * vip.mate.config.DatabaseBootstrapRunner} (Order 1), so SQL seeds load first
 * and this service then overlays the authoritative classpath SKILL.md.
 */
@Slf4j
@Service
@Order(110)
@RequiredArgsConstructor
public class BuiltinSkillSeedService implements ApplicationRunner {

    private static final String SKILL_GLOB = "classpath*:skills/*/SKILL.md";
    private static final String DEFAULT_AUTHOR = "MateClaw";
    private static final String DEFAULT_ICON = "🛠️";
    private static final String DEFAULT_VERSION = "1.0.0";
    private static final String SKILL_TYPE_BUILTIN = "builtin";

    /** Snapshot version — bump on any schema change inside the JSON. */
    private static final int SNAPSHOT_VERSION = 1;

    private final SkillMapper skillMapper;
    private final SkillFrontmatterParser frontmatterParser;
    private final ObjectMapper objectMapper;

    /** Workspace root, used as the parent of the snapshot file. Mirrors
     *  {@code SkillWorkspaceProperties#root} so we don't drag the whole
     *  properties bean in for one path lookup. */
    @Value("${mateclaw.skill.workspace.root:#{systemProperties['user.home'] + '/.mateclaw/skills'}}")
    private String workspaceRoot;

    @Override
    public void run(ApplicationArguments args) {
        try {
            syncBuiltinSkills();
        } catch (Exception e) {
            log.warn("[SkillSeed] Sync failed (table may not exist yet): {}", e.getMessage());
        }
    }

    /** Public so tests and admin endpoints can re-trigger sync. */
    public SyncStats syncBuiltinSkills() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(SKILL_GLOB);
        } catch (Exception e) {
            log.warn("[SkillSeed] Failed to scan {}: {}", SKILL_GLOB, e.getMessage());
            return new SyncStats(0, 0, 0, 0);
        }

        // Fast path — if every SKILL.md's (size, mtime) matches the snapshot
        // from the previous successful run AND the DB still holds the same
        // number of builtin rows, nothing on disk changed since last seed
        // and we can skip the parse / select / update loop entirely.
        // The check is stat-only — no content read.
        Map<String, long[]> currentManifest = buildResourceManifest(resources);
        SeedSnapshot snapshot = loadSnapshot();
        if (snapshot != null
                && snapshot.version == SNAPSHOT_VERSION
                && manifestEquals(snapshot.manifest, currentManifest)
                && countBuiltinRows() == snapshot.rowCount) {
            log.info("[SkillSeed] Manifest unchanged ({} skills); skipping per-row resolve",
                    currentManifest.size());
            return new SyncStats(0, 0, currentManifest.size(), 0);
        }

        int inserted = 0, updated = 0, unchanged = 0, skipped = 0;
        for (Resource resource : resources) {
            try {
                String content = readContent(resource);
                SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);
                String name = parsed.getName();
                if (name == null || name.isBlank()) {
                    log.warn("[SkillSeed] {}: SKILL.md has no `name` in frontmatter — skipped",
                            resource.getDescription());
                    skipped++;
                    continue;
                }

                SkillEntity existing = skillMapper.selectOne(
                        new LambdaQueryWrapper<SkillEntity>().eq(SkillEntity::getName, name));

                if (existing == null) {
                    SkillEntity row = buildNew(parsed, content);
                    skillMapper.insert(row);
                    inserted++;
                    log.info("[SkillSeed] inserted '{}' (version={})", name, row.getVersion());
                } else if (mergeIntoExisting(existing, parsed, content)) {
                    skillMapper.updateById(existing);
                    updated++;
                    log.info("[SkillSeed] updated '{}' (version={})", name, existing.getVersion());
                } else {
                    unchanged++;
                }
            } catch (Exception e) {
                log.warn("[SkillSeed] Failed to process {}: {}", resource.getDescription(), e.getMessage());
                skipped++;
            }
        }
        log.info("[SkillSeed] Builtin skills: {} inserted, {} updated, {} unchanged, {} skipped",
                inserted, updated, unchanged, skipped);
        // Persist the manifest so the next startup can take the fast path
        // when nothing changed. Failure to write is non-fatal — worst case
        // the next startup re-runs the full loop.
        writeSnapshot(new SeedSnapshot(SNAPSHOT_VERSION, currentManifest, countBuiltinRows()));
        return new SyncStats(inserted, updated, unchanged, skipped);
    }

    // ==================== Snapshot helpers ====================

    /** Snapshot persisted at {workspace_root}/.builtin-seed-snapshot.json. */
    private record SeedSnapshot(int version, Map<String, long[]> manifest, long rowCount) {}

    private Path snapshotPath() {
        return Paths.get(workspaceRoot).resolve(".builtin-seed-snapshot.json");
    }

    /**
     * Build a stable manifest of {@code uri → [size, mtime]} for the
     * shipped SKILL.md set. {@link TreeMap} keeps the iteration order
     * deterministic so byte-equality of two manifests means the same
     * thing across runs.
     *
     * <p>{@link Resource#contentLength()} works in both exploded-classpath
     * dev mode and inside a JAR. {@link URLConnection#getLastModified()}
     * returns the JAR's mtime when the resource lives inside one — that
     * still gives us a useful invalidation signal: rebuilding the JAR
     * shifts every entry's mtime in lockstep, busting the snapshot.
     */
    private Map<String, long[]> buildResourceManifest(Resource[] resources) {
        Map<String, long[]> out = new TreeMap<>();
        for (Resource res : resources) {
            try {
                String key = res.getURI().toString();
                long size = res.contentLength();
                long mtime;
                try {
                    URLConnection conn = res.getURL().openConnection();
                    mtime = conn.getLastModified();
                } catch (IOException ignored) {
                    mtime = 0L; // unknown — still hash-stable across runs
                }
                out.put(key, new long[]{size, mtime});
            } catch (IOException e) {
                // Skip resources we can't stat — they'll be picked up by the
                // slow path which reads them anyway.
                log.debug("Failed to stat resource {}: {}", res.getDescription(), e.getMessage());
            }
        }
        return out;
    }

    /** Element-wise compare of two manifests (TreeMap ordering not assumed). */
    private static boolean manifestEquals(Map<String, long[]> a, Map<String, long[]> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, long[]> e : a.entrySet()) {
            long[] other = b.get(e.getKey());
            if (other == null) return false;
            long[] mine = e.getValue();
            if (mine.length != other.length) return false;
            for (int i = 0; i < mine.length; i++) {
                if (mine[i] != other[i]) return false;
            }
        }
        return true;
    }

    private long countBuiltinRows() {
        try {
            Long n = skillMapper.selectCount(
                    new LambdaQueryWrapper<SkillEntity>()
                            .eq(SkillEntity::getSkillType, SKILL_TYPE_BUILTIN)
                            .eq(SkillEntity::getDeleted, 0));
            return n != null ? n : 0L;
        } catch (Exception e) {
            log.debug("Failed to count builtin skills: {}", e.getMessage());
            return -1L; // mismatch sentinel — forces full re-scan
        }
    }

    private SeedSnapshot loadSnapshot() {
        Path path = snapshotPath();
        if (!Files.isRegularFile(path)) return null;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, Object> root = objectMapper.readValue(
                    json, new TypeReference<>() {});
            int version = toLong(root.get("version"), 0L).intValue();
            long rowCount = toLong(root.get("rowCount"), 0L);
            // Manifest entries arrive as List<Object> — each element may be a
            // JSON number (Integer/Long) OR a quoted string. The mateclaw
            // global ObjectMapper serializes long values as strings to avoid
            // JS precision loss (mtime is 13-digit ms-since-epoch). Coerce
            // both shapes back to long here.
            Map<String, List<Object>> rawManifest = objectMapper.convertValue(
                    root.getOrDefault("manifest", Map.of()),
                    new TypeReference<>() {});
            Map<String, long[]> manifest = new TreeMap<>();
            for (Map.Entry<String, List<Object>> e : rawManifest.entrySet()) {
                List<Object> arr = e.getValue();
                if (arr == null || arr.size() < 2) continue;
                manifest.put(e.getKey(), new long[]{
                        toLong(arr.get(0), 0L),
                        toLong(arr.get(1), 0L),
                });
            }
            return new SeedSnapshot(version, manifest, rowCount);
        } catch (Exception e) {
            log.debug("Could not read seed snapshot {}: {}", path, e.getMessage());
            return null;
        }
    }

    /** Coerce JSON-decoded value to long. Handles both numeric (Integer /
     *  Long / Double) and string-encoded-long forms — the latter is what
     *  mateclaw's ObjectMapper emits for long fields by default. */
    private static Long toLong(Object value, Long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void writeSnapshot(SeedSnapshot snapshot) {
        Path path = snapshotPath();
        try {
            Files.createDirectories(path.getParent());
            // Serialize the long[] entries as plain List<Long> for portable JSON.
            Map<String, List<Long>> serializableManifest = new LinkedHashMap<>();
            for (Map.Entry<String, long[]> e : snapshot.manifest().entrySet()) {
                long[] v = e.getValue();
                serializableManifest.put(e.getKey(), List.of(v[0], v[1]));
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", snapshot.version());
            root.put("rowCount", snapshot.rowCount());
            root.put("manifest", serializableManifest);
            Files.writeString(path, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Could not write seed snapshot {}: {}", path, e.getMessage());
        }
    }

    private String readContent(Resource resource) throws Exception {
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Build a brand-new entity for a skill that has no row in mate_skill yet. */
    private SkillEntity buildNew(SkillFrontmatterParser.ParsedSkillMd parsed, String content) {
        SkillEntity row = new SkillEntity();
        row.setName(parsed.getName());
        row.setDescription(nullIfBlank(parsed.getDescription()));
        row.setSkillType(SKILL_TYPE_BUILTIN);
        row.setBuiltin(true);
        // Frontmatter `optional: true` flips the initial seed to enabled=false,
        // so heavyweight bundled skills (paid CLI dependencies, external OAuth,
        // niche integrations) ship dark — the user opts in from the Skills page
        // when they actually want them. The default (frontmatter absent or
        // false) preserves the historical "all bundled skills active" behavior.
        // {@link #mergeIntoExisting} deliberately does NOT touch `enabled`, so
        // once a user activates an optional skill, subsequent boots keep their
        // choice and a downgrade in frontmatter never silently disables it.
        boolean optional = booleanFromFrontmatter(parsed, "optional", false);
        row.setEnabled(!optional);
        row.setSkillContent(content);
        row.setVersion(stringFromFrontmatter(parsed, "version", DEFAULT_VERSION));
        row.setIcon(stringFromFrontmatter(parsed, "icon", DEFAULT_ICON));
        row.setAuthor(stringFromFrontmatter(parsed, "author", DEFAULT_AUTHOR));
        row.setTags(tagsFromFrontmatter(parsed, parsed.getName()));
        // RFC-042 §2.2 — optional bilingual display names from frontmatter.
        row.setNameZh(stringFromFrontmatter(parsed, "nameZh", null));
        row.setNameEn(stringFromFrontmatter(parsed, "nameEn", null));
        row.setConfigJson(buildConfigJson(parsed));
        LocalDateTime now = LocalDateTime.now();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        row.setDeleted(0);
        return row;
    }

    /**
     * Apply frontmatter onto an existing row. Returns {@code true} if any
     * tracked field changed and the row needs an UPDATE.
     *
     * <p>Frontmatter wins where present. Fields the frontmatter omits are
     * left as-is so we don't blank out values populated elsewhere (UI,
     * legacy SQL seed, manual admin tweaks).
     */
    private boolean mergeIntoExisting(SkillEntity existing,
                                       SkillFrontmatterParser.ParsedSkillMd parsed,
                                       String content) {
        boolean dirty = false;

        String desc = nullIfBlank(parsed.getDescription());
        if (desc != null && !Objects.equals(existing.getDescription(), desc)) {
            existing.setDescription(desc);
            dirty = true;
        }

        String version = stringFromFrontmatter(parsed, "version", null);
        if (version != null && !Objects.equals(existing.getVersion(), version)) {
            existing.setVersion(version);
            dirty = true;
        }

        String icon = stringFromFrontmatter(parsed, "icon", null);
        if (icon != null && !Objects.equals(existing.getIcon(), icon)) {
            existing.setIcon(icon);
            dirty = true;
        }

        String author = stringFromFrontmatter(parsed, "author", null);
        if (author != null && !Objects.equals(existing.getAuthor(), author)) {
            existing.setAuthor(author);
            dirty = true;
        }

        String tags = tagsFromFrontmatter(parsed, null);
        if (tags != null && !Objects.equals(existing.getTags(), tags)) {
            existing.setTags(tags);
            dirty = true;
        }

        // RFC-042 §2.2 — bilingual display names. Frontmatter wins; if silent,
        // preserve whatever the SQL seed or a manual UI edit populated.
        String nameZh = stringFromFrontmatter(parsed, "nameZh", null);
        if (nameZh != null && !Objects.equals(existing.getNameZh(), nameZh)) {
            existing.setNameZh(nameZh);
            dirty = true;
        }
        String nameEn = stringFromFrontmatter(parsed, "nameEn", null);
        if (nameEn != null && !Objects.equals(existing.getNameEn(), nameEn)) {
            existing.setNameEn(nameEn);
            dirty = true;
        }

        String configJson = buildConfigJson(parsed);
        if (!Objects.equals(existing.getConfigJson(), configJson)) {
            existing.setConfigJson(configJson);
            dirty = true;
        }

        if (!Objects.equals(existing.getSkillContent(), content)) {
            existing.setSkillContent(content);
            dirty = true;
        }

        // Re-affirm builtin classification — historic rows occasionally drifted.
        if (!SKILL_TYPE_BUILTIN.equals(existing.getSkillType())) {
            existing.setSkillType(SKILL_TYPE_BUILTIN);
            dirty = true;
        }
        if (!Boolean.TRUE.equals(existing.getBuiltin())) {
            existing.setBuiltin(true);
            dirty = true;
        }

        return dirty;
    }

    /**
     * Read a boolean frontmatter key, tolerant of the YAML / casual-string
     * forms the parser might surface ({@code true} / {@code "true"} /
     * {@code "yes"} / {@code "1"}). Anything else falls back to the supplied
     * default so a typo doesn't silently flip behavior.
     */
    private boolean booleanFromFrontmatter(SkillFrontmatterParser.ParsedSkillMd parsed,
                                            String key, boolean fallback) {
        Map<String, Object> fm = parsed.getFrontmatter();
        if (fm == null) return fallback;
        Object value = fm.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        String s = value.toString().trim().toLowerCase();
        if (s.isEmpty()) return fallback;
        return s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("on");
    }

    @SuppressWarnings("unchecked")
    private String stringFromFrontmatter(SkillFrontmatterParser.ParsedSkillMd parsed,
                                          String key, String fallback) {
        Map<String, Object> fm = parsed.getFrontmatter();
        if (fm == null) return fallback;
        Object value = fm.get(key);
        if (value == null) return fallback;
        String s = value.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    /**
     * Build the canonical {@code tags} string. Accepts either a CSV string,
     * a YAML list, or — when the frontmatter is silent — derives a single
     * tag from the supplied default (typically the skill name).
     *
     * <p>Returns {@code null} when nothing usable was supplied; callers use
     * that as "do not touch".
     */
    @SuppressWarnings("unchecked")
    private String tagsFromFrontmatter(SkillFrontmatterParser.ParsedSkillMd parsed, String defaultTag) {
        Map<String, Object> fm = parsed.getFrontmatter();
        if (fm != null) {
            Object raw = fm.get("tags");
            if (raw instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                for (Object item : list) {
                    if (item == null) continue;
                    String s = item.toString().trim();
                    if (s.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(s);
                }
                if (sb.length() > 0) return sb.toString();
            } else if (raw instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return defaultTag != null ? defaultTag : null;
    }

    /**
     * Stable {@code config_json} payload. Preserves the historical shape
     * ({@code upstream}, {@code entryFile}) and adds {@code requiredTools}
     * derived from {@code dependencies.tools}.
     */
    private String buildConfigJson(SkillFrontmatterParser.ParsedSkillMd parsed) {
        // LinkedHashMap → stable key ordering → stable diff against existing.
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("upstream", "mateclaw");
        config.put("entryFile", "SKILL.md");

        SkillFrontmatterParser.SkillDependencies deps = parsed.getDependencies();
        if (deps != null && deps.getTools() != null && !deps.getTools().isEmpty()) {
            config.put("requiredTools", deps.getTools());
        }
        if (parsed.getPlatforms() != null && !parsed.getPlatforms().isEmpty()) {
            config.put("platforms", parsed.getPlatforms());
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            // Fall back to legacy shape — never break startup over JSON encoding.
            return "{\"upstream\":\"mateclaw\",\"entryFile\":\"SKILL.md\"}";
        }
    }

    private String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record SyncStats(int inserted, int updated, int unchanged, int skipped) {}
}
