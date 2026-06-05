package vip.mate.wiki.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single point of truth for validating a KB source directory path, shared by
 * the manual directory scan and the source-directory config endpoint (and the
 * future filesystem watcher).
 *
 * <p>The raw path is canonicalized with {@code toRealPath()} (resolving
 * symlinks) when it exists, so a symlink cannot escape the allowed area. When
 * {@code mate.wiki.allowed-source-roots} is configured, the resolved path must
 * lie within one of those roots; when it is empty the path is only
 * canonicalized (opt-in enforcement — existing single-tenant / desktop setups
 * keep working, server operators can lock it down).
 *
 * <p>Also owns the parsing helpers for the multi-line source-paths config
 * format so that both the validation endpoint and the scan service share a
 * single implementation.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiSourcePathValidator {

    private final WikiProperties properties;

    public WikiSourcePathValidator(WikiProperties properties) {
        this.properties = properties;
    }

    // ==================== parsing helpers (stateless, no Spring context) ====================

    /**
     * Parse the {@code sourceDirectory} field: split by newline, strip blank
     * lines and lines starting with {@code #}.
     */
    public static List<String> parseSourcePatterns(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank() && !s.startsWith("#"))
                .collect(Collectors.toList());
    }

    /**
     * Extract the fixed-prefix base directory from a glob pattern — the
     * leading path segments before the first wildcard segment.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code /data/ocr/**}{@code /*.txt} → {@code /data/ocr}</li>
     *   <li>{@code /data/*.txt} → {@code /data}</li>
     *   <li>{@code /data/docs} → {@code /data/docs} (no wildcard)</li>
     * </ul>
     */
    public static String extractBasePath(String pattern) {
        if (!containsWildcard(pattern)) {
            return pattern;
        }
        String[] segments = pattern.split("/", -1);
        List<String> baseSegments = new ArrayList<>();
        for (String seg : segments) {
            if (containsWildcard(seg)) break;
            baseSegments.add(seg);
        }
        if (baseSegments.isEmpty()) return "/";
        String joined = String.join("/", baseSegments);
        return joined.isEmpty() ? "/" : joined;
    }

    // ==================== validation ====================

    /**
     * Canonicalize and authorize a source directory path.
     *
     * @return the resolved absolute path
     * @throws IllegalArgumentException when blank or outside the allowed roots
     */
    public Path validateDirectory(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Source directory path is required");
        }
        Path resolved = canonicalize(Paths.get(rawPath));
        List<String> roots = properties.getAllowedSourceRoots();
        // Filter blank entries so MATE_WIKI_ALLOWED_SOURCE_ROOTS="" (unset env var)
        // behaves identically to an empty list rather than a list with one blank entry.
        List<String> nonBlankRoots = (roots == null) ? List.of()
                : roots.stream().filter(r -> r != null && !r.isBlank()).toList();
        if (nonBlankRoots.isEmpty()) {
            if (properties.isRequireAllowedRoots()) {
                throw new IllegalArgumentException(
                        "No allowed source roots are configured; refusing the path (fail-closed). "
                        + "Set MATE_WIKI_ALLOWED_SOURCE_ROOTS (env var) or "
                        + "mate.wiki.allowed-source-roots to permit directories.");
            }
            return resolved;
        }
        for (String root : nonBlankRoots) {
            Path rootPath = canonicalize(Paths.get(root));
            if (resolved.startsWith(rootPath)) {
                return resolved;
            }
        }
        throw new IllegalArgumentException(
                "Path is outside the allowed source roots: " + resolved);
    }

    /**
     * Validate all patterns in a multi-line source-directory config.  Each
     * non-blank, non-comment line is validated; the first violation is thrown.
     *
     * @throws IllegalArgumentException describing which line failed and why
     */
    public void validateSourcePatterns(String raw) {
        List<String> patterns = parseSourcePatterns(raw);
        for (String pattern : patterns) {
            validatePatternBase(pattern);
        }
    }

    /**
     * Validate a single path-or-glob-pattern: for glob patterns the
     * fixed-prefix base directory is extracted and validated; for plain paths
     * the path itself is validated.
     *
     * @return the resolved base directory path
     * @throws IllegalArgumentException when the base is outside the allowed roots
     */
    public Path validatePatternBase(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Pattern is blank");
        }
        String basePath = extractBasePath(pattern);
        try {
            return validateDirectory(basePath);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Pattern '" + pattern + "' has an invalid base path: " + e.getMessage(), e);
        }
    }

    /** Whether a path passes validation, without throwing. */
    public boolean isAllowed(String rawPath) {
        try {
            validateDirectory(rawPath);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ==================== private ====================

    private Path canonicalize(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (Files.exists(abs)) {
            try {
                return abs.toRealPath();
            } catch (IOException e) {
                log.debug("[WikiPath] toRealPath failed for {}: {}", abs, e.getMessage());
            }
        }
        return abs;
    }

    private static boolean containsWildcard(String s) {
        return s.contains("*") || s.contains("?") || s.contains("{") || s.contains("[");
    }
}
