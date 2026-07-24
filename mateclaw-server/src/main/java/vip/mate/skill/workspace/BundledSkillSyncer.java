package vip.mate.skill.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.bundle.ClasspathBundleSource;
import vip.mate.skill.workspace.bundle.MaterializeOptions;
import vip.mate.skill.workspace.bundle.SkillBundleFiles;
import vip.mate.skill.workspace.bundle.SkillBundleMaterializer;
import vip.mate.skill.workspace.bundle.SkillBundleSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers {@code classpath:skills/&#42;/SKILL.md} at startup and copies
 * each into the user's skill workspace, upgrading on version bumps.
 *
 * <p>Split out of {@link SkillWorkspaceManager} so the workspace service
 * keeps a single concern (path resolution + lifecycle) and the bundled
 * sync flow has its own home with the version-comparison helpers it owns.
 *
 * <p>Sync rules:
 * <ul>
 *   <li>First-time install: copy the whole bundle.</li>
 *   <li>Re-install: only when the classpath SKILL.md frontmatter
 *       {@code version} is strictly newer than the workspace copy. The
 *       existing workspace is archived (not deleted) before overwrite.</li>
 *   <li>Self-heal: when the bundle ships {@code scripts/} or
 *       {@code references/} but the workspace lacks that directory entirely
 *       (an install performed from a build whose jar was missing those
 *       folders), the workspace is archived and re-copied even though the
 *       version is unchanged.</li>
 *   <li>Same / older / unparseable version with all buckets present: leave
 *       the workspace alone so user edits aren't clobbered.</li>
 * </ul>
 *
 * <p>Whenever a bundle is copied to disk (install, upgrade, or self-heal),
 * its {@code scripts/} and {@code references/} files are also persisted to
 * the canonical {@code mate_skill_file} store so multi-instance deployments
 * see the same content regardless of which node performed the sync.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BundledSkillSyncer {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^version:\\s*[\"']?([^\"'\\s]+)[\"']?", Pattern.MULTILINE);

    /** Builtin/bundled skills are global and materialized under the default workspace. */
    private static final Long BUILTIN_WORKSPACE_ID = 1L;

    private final SkillWorkspaceProperties properties;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillBundleMaterializer bundleMaterializer;
    private final ApplicationEventPublisher eventPublisher;
    private final SkillService skillService;
    private final SkillFileService skillFileService;

    /**
     * Run a full sync pass. Idempotent — safe to call from both startup
     * (see {@link SkillWorkspaceBootstrapRunner}) and the admin
     * "re-sync bundled skills" REST endpoint.
     *
     * @return list of skill names that were freshly installed or upgraded
     */
    public List<String> sync() {
        String bundledPath = properties.getBundledSkillsPath();
        if (bundledPath == null || bundledPath.isBlank()) {
            return List.of();
        }

        List<String> synced = new ArrayList<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            String pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                    + bundledPath + "/*/SKILL.md";
            Resource[] manifests = resolver.getResources(pattern);
            for (Resource manifest : manifests) {
                String skillName = extractSkillName(manifest, bundledPath);
                if (skillName == null || skillName.isBlank()) continue;
                if (syncOne(resolver, bundledPath, skillName, manifest)) {
                    synced.add(skillName);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan bundled skills under classpath:{}/: {}", bundledPath, e.getMessage());
        }
        return synced;
    }

    /**
     * Sync a single bundled skill. Returns true if the workspace was
     * created, upgraded, or self-healed. Same/older versions with all
     * bundle buckets present on disk are no-ops.
     */
    private boolean syncOne(ResourcePatternResolver resolver, String bundledPath,
                             String skillName, Resource manifest) {
        // Builtin/bundled skills are global and seeded into workspace 1.
        Path targetDir = workspaceManager.resolveConventionPath(skillName, BUILTIN_WORKSPACE_ID);
        boolean firstInstall = !Files.exists(targetDir);

        SkillBundleSource source = new ClasspathBundleSource(resolver,
                bundledPath + "/" + skillName);

        if (!firstInstall) {
            String bundledVersion = readVersion(manifest);
            String workspaceVersion = readVersion(targetDir.resolve("SKILL.md"));
            boolean upgrade = bundledVersion != null && isNewerVersion(bundledVersion, workspaceVersion);
            if (upgrade) {
                log.info("Bundled skill '{}' version {} > workspace version {}, upgrading",
                        skillName, bundledVersion, workspaceVersion);
            } else {
                List<String> missing = missingBucketsOnDisk(source, targetDir);
                if (missing.isEmpty()) {
                    log.debug("Bundled skill '{}' workspace is current (bundled={}, workspace={}), skipping",
                            skillName, bundledVersion, workspaceVersion);
                    return false;
                }
                log.info("Bundled skill '{}' is missing {} on disk, restoring from bundle",
                        skillName, missing);
            }
            // Archive (never overwrite in place) so local edits stay recoverable.
            workspaceManager.archiveWorkspace(skillName, BUILTIN_WORKSPACE_ID);
        }

        copyBundle(source, targetDir);
        syncBundleFilesToDb(skillName, source);
        eventPublisher.publishEvent(
                new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.CREATED, targetDir));
        log.info("{} bundled skill '{}' → {}", firstInstall ? "Synced" : "Upgraded", skillName, targetDir);
        return true;
    }

    /**
     * Buckets ({@code scripts/}, {@code references/}) that ship in the
     * bundle but are absent from the workspace directory — the fingerprint
     * of an install performed from a build whose jar lacked those folders.
     */
    private List<String> missingBucketsOnDisk(SkillBundleSource source, Path targetDir) {
        List<String> missing = new ArrayList<>();
        try {
            List<SkillBundleSource.BundleAsset> assets = source.assets();
            for (String prefix : SkillBundleFiles.DB_BUCKET_PREFIXES) {
                boolean inBundle = assets.stream().anyMatch(a -> a.relativePath().startsWith(prefix));
                String dirName = prefix.substring(0, prefix.length() - 1);
                if (inBundle && !Files.isDirectory(targetDir.resolve(dirName))) {
                    missing.add(dirName);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to enumerate bundle assets from {}: {}", source.origin(), e.getMessage());
        }
        return missing;
    }

    /**
     * Mirror the bundle's DB-persisted buckets into {@code mate_skill_file}.
     * Skipped silently when the skill row doesn't exist yet (first boot
     * before seeding) — the skill file syncer's disk backfill covers that
     * case once the row appears.
     */
    private void syncBundleFilesToDb(String skillName, SkillBundleSource source) {
        SkillEntity skill = skillService.findByName(skillName);
        if (skill == null || skill.getId() == null) return;
        try {
            Map<String, String> bundleFiles = SkillBundleFiles.readDbEligible(source);
            if (bundleFiles.isEmpty()) return;
            skillFileService.applyBundleFiles(skill.getId(), bundleFiles, false);
        } catch (IOException e) {
            log.warn("Failed to load bundle files from {}: {}", source.origin(), e.getMessage());
        }
    }

    private void copyBundle(SkillBundleSource source, Path targetDir) {
        try {
            bundleMaterializer.materialize(source, targetDir, MaterializeOptions.verbatim());
        } catch (IOException e) {
            log.warn("Failed to copy bundled skill from {}: {}",
                    source.origin(), e.getMessage());
        }
    }

    /** {@code classpath:skills/etf-analyzer/SKILL.md} → {@code etf-analyzer} */
    private String extractSkillName(Resource resource, String bundledPath) {
        try {
            String uri = resource.getURI().toString();
            String marker = bundledPath + "/";
            int start = uri.indexOf(marker);
            if (start < 0) return null;
            String remainder = uri.substring(start + marker.length());
            int slash = remainder.indexOf('/');
            return slash > 0 ? remainder.substring(0, slash) : null;
        } catch (IOException e) {
            log.debug("Failed to extract skill name from resource: {}", e.getMessage());
            return null;
        }
    }

    private String readVersion(Resource resource) {
        try (InputStream is = resource.getInputStream()) {
            return parseVersion(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private String readVersion(Path path) {
        if (!Files.exists(path)) return null;
        try {
            return parseVersion(Files.readString(path));
        } catch (IOException e) {
            return null;
        }
    }

    private static String parseVersion(String content) {
        // Look only at the YAML frontmatter — between the leading "---" and the next "---".
        if (content == null || !content.startsWith("---")) return null;
        int end = content.indexOf("---", 3);
        if (end < 0) return null;
        Matcher m = VERSION_PATTERN.matcher(content.substring(0, end));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Semver-ish compare: numeric segments, dot-delimited. Missing segments
     * count as 0 ({@code 1.1} vs {@code 1.1.0} → equal). Non-numeric parts
     * coerce to 0 to keep the comparison total-order. Null {@code older}
     * means "no current version" → newer always wins.
     */
    static boolean isNewerVersion(String newer, String older) {
        if (newer == null) return false;
        if (older == null) return true;
        String[] n = newer.split("\\.");
        String[] o = older.split("\\.");
        int len = Math.max(n.length, o.length);
        for (int i = 0; i < len; i++) {
            int a = i < n.length ? parseSegment(n[i]) : 0;
            int b = i < o.length ? parseSegment(o[i]) : 0;
            if (a > b) return true;
            if (a < b) return false;
        }
        return false;
    }

    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
