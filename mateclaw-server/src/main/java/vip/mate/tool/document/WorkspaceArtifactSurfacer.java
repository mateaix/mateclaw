package vip.mate.tool.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Turns files that a tool run wrote into the working directory into one-click
 * download links, so a user can grab generated artifacts (xlsx / csv / images /
 * …) without the model having to call {@code send_file} or echo a server path.
 *
 * <p>Each returned entry is a {@code [name](url)} markdown link backed by
 * {@link GeneratedFileCache} (7-day, disk-persisted, served by
 * {@code GeneratedFileController}). The chat layer already scans tool results for
 * exactly this shape and surfaces them as downloads, so callers just need to put
 * the links somewhere in their result payload.
 *
 * <p>Shared by {@code execute_code} and {@code execute_shell_command}. Best-effort
 * throughout — surfacing a download must never fail the tool run.
 */
@Slf4j
public final class WorkspaceArtifactSurfacer {

    private static final int SCAN_DEPTH = 4;
    private static final int MAX_ARTIFACTS = 8;
    private static final int MAX_SCAN_CANDIDATES = 200;
    private static final long MAX_ARTIFACT_BYTES = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_ARTIFACT_BYTES = 48L * 1024 * 1024;

    private WorkspaceArtifactSurfacer() {}

    /**
     * Register files created or modified in {@code workingDir} at or after
     * {@code sinceMillis} into the cache and return their download links.
     * Returns an empty list when there is no persistent working dir (e.g. a
     * private scratch dir that gets deleted after the run).
     */
    public static List<String> collect(GeneratedFileCache cache, @Nullable Path workingDir,
                                       long sinceMillis, @Nullable ToolContext ctx) {
        if (cache == null || workingDir == null || !Files.isDirectory(workingDir)) {
            return List.of();
        }
        List<String> links = new ArrayList<>();
        long totalBytes = 0L;
        try (Stream<Path> walk = Files.walk(workingDir, SCAN_DEPTH)) {
            List<Path> candidates = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isNoise(p))
                    .filter(p -> modifiedSince(p, sinceMillis))
                    .limit(MAX_SCAN_CANDIDATES)
                    .toList();
            for (Path p : candidates) {
                if (links.size() >= MAX_ARTIFACTS) {
                    break;
                }
                try {
                    long size = Files.size(p);
                    if (size <= 0 || size > MAX_ARTIFACT_BYTES || totalBytes + size > MAX_TOTAL_ARTIFACT_BYTES) {
                        continue;
                    }
                    byte[] bytes = Files.readAllBytes(p);
                    totalBytes += size;
                    String name = p.getFileName().toString();
                    String id = cache.put(bytes, name, probeMime(p, name));
                    links.add("[" + name + "](" + cache.downloadUrl(id, ctx) + ")");
                } catch (Exception perFile) {
                    log.debug("[ArtifactSurfacer] skip {}: {}", p, perFile.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("[ArtifactSurfacer] scan failed for {}: {}", workingDir, e.getMessage());
        }
        return links;
    }

    private static boolean modifiedSince(Path p, long sinceMillis) {
        try {
            // 1s slack absorbs filesystem mtime granularity.
            return Files.getLastModifiedTime(p).toMillis() >= sinceMillis - 1000L;
        } catch (Exception e) {
            return false;
        }
    }

    /** Skip hidden files, dependency/cache dirs, and obvious scratch/log files. */
    private static boolean isNoise(Path p) {
        for (Path seg : p) {
            String s = seg.toString();
            if (s.startsWith(".") || s.equals("__pycache__") || s.equals("node_modules")) {
                return true;
            }
        }
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".pyc") || name.endsWith(".tmp") || name.endsWith(".lock")
                || name.endsWith(".log") || name.endsWith(".class");
    }

    private static String probeMime(Path p, String name) {
        try {
            String mime = Files.probeContentType(p);
            if (mime != null && !mime.isBlank()) {
                return mime;
            }
        } catch (Exception ignore) {
            // fall through to extension default
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
