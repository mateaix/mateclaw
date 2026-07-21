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
import vip.mate.skill.workspace.bundle.SkillBundleMaterializer;
import vip.mate.skill.workspace.bundle.SkillBundleSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers {@code classpath:skills/*\/SKILL.md} at startup and copies
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
 *   <li>Same / older / unparseable version: leave the workspace alone so
 *       user edits aren't clobbered.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BundledSkillSyncer {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^version:\\s*[\"']?([^\"'\\s]+)[\"']?", Pattern.MULTILINE);

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
     * created or upgraded. Same/older versions are no-ops.
     */
    private boolean syncOne(ResourcePatternResolver resolver, String bundledPath,
                             String skillName, Resource manifest) {
        Path targetDir = workspaceManager.resolveConventionPath(skillName);
        boolean firstInstall = !Files.exists(targetDir);

        SkillBundleSource source = new ClasspathBundleSource(resolver,
                bundledPath + "/" + skillName);
        Map<String, String> bundleFiles = loadBundleFilesFromSource(source);

        SkillEntity skill = skillService.findByName(skillName);
        if (skill != null && skill.getId() != null && !bundleFiles.isEmpty()) {
            skillFileService.applyBundleFiles(skill.getId(), bundleFiles, false);
        }

        if (!firstInstall) {
            String bundledVersion = readVersion(manifest);
            String workspaceVersion = readVersion(targetDir.resolve("SKILL.md"));
            boolean missingScriptsOnDisk = hasScriptsInBundle(bundleFiles) && !Files.exists(targetDir.resolve("scripts"));

            if (!missingScriptsOnDisk && (bundledVersion == null || !isNewerVersion(bundledVersion, workspaceVersion))) {
                log.debug("Bundled skill '{}' workspace is current (bundled={}, workspace={}), skipping",
                        skillName, bundledVersion, workspaceVersion);
                return false;
            }
            if (!missingScriptsOnDisk) {
                log.info("Bundled skill '{}' version {} > workspace version {}, upgrading",
                        skillName, bundledVersion, workspaceVersion);
                workspaceManager.archiveWorkspace(skillName);
            } else {
                log.info("Bundled skill '{}' is missing scripts directory on disk, force copying bundle", skillName);
            }
        }

        copyBundle(source, targetDir);
        eventPublisher.publishEvent(
                new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.CREATED, targetDir));
        log.info("{} bundled skill '{}' → {}", firstInstall ? "Synced" : "Upgraded", skillName, targetDir);
        return true;
    }

    private Map<String, String> loadBundleFilesFromSource(SkillBundleSource source) {
        Map<String, String> files = new LinkedHashMap<>();
        try {
            for (SkillBundleSource.BundleAsset asset : source.assets()) {
                String path = asset.relativePath();
                if (path.startsWith("scripts/") || path.startsWith("references/")) {
                    try (InputStream is = asset.open().get()) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        files.put(path, content);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load bundle assets from {}: {}", source.origin(), e.getMessage());
        }
        return files;
    }

    private boolean hasScriptsInBundle(Map<String, String> bundleFiles) {
        return bundleFiles.keySet().stream().anyMatch(p -> p.startsWith("scripts/"));
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
