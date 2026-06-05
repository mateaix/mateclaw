package vip.mate.wiki.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiSourcePathValidator {

    private final WikiProperties properties;

    public WikiSourcePathValidator(WikiProperties properties) {
        this.properties = properties;
    }

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

    /** Whether a path passes validation, without throwing. */
    public boolean isAllowed(String rawPath) {
        try {
            validateDirectory(rawPath);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

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
}
