package vip.mate.skill.workspace.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for the bundle file buckets that mirror into the canonical
 * {@code mate_skill_file} store ({@code scripts/}, {@code references/}
 * and {@code templates/}).
 *
 * <p>Shared by the bundled-skill startup sync, the skill file syncer's
 * classpath backfill, and the admin file editor so all agree on which
 * paths are DB-persisted and how bundle contents are read into memory.
 */
public final class SkillBundleFiles {

    /** Path prefixes of the buckets persisted to {@code mate_skill_file}. */
    public static final List<String> DB_BUCKET_PREFIXES = List.of("scripts/", "references/", "templates/");

    private SkillBundleFiles() {
    }

    /** True when the workspace-relative path belongs to a DB-persisted bucket. */
    public static boolean isDbEligible(String relativePath) {
        if (relativePath == null) return false;
        for (String prefix : DB_BUCKET_PREFIXES) {
            if (relativePath.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Read every DB-eligible bundle file ({@link #DB_BUCKET_PREFIXES})
     * into memory, keyed by workspace-relative path (the key shape
     * {@code SkillFileService#applyBundleFiles} expects). Iteration order
     * follows {@link SkillBundleSource#assets()} enumeration order.
     */
    public static Map<String, String> readDbEligible(SkillBundleSource source) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        for (SkillBundleSource.BundleAsset asset : source.assets()) {
            String path = asset.relativePath();
            if (!isDbEligible(path)) continue;
            try (InputStream is = asset.open().get()) {
                files.put(path, new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
