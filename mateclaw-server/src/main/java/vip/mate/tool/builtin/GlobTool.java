package vip.mate.tool.builtin;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 内置工具：文件名模式匹配（glob 搜索）
 * <p>
 * 使用 Java PathMatcher 的 glob 语法在指定目录下递归查找匹配文件。
 * 不需要 shell out，路径边界由 {@code WorkspacePathGuard} 强制。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class GlobTool {

    private static final int MAX_RESULTS = 500;

    @Tool(description = """
            Find files by glob pattern. Uses standard glob syntax:
            ** for recursive directories, * for single-level wildcard,
            ? for single char, {a,b} for alternation.
            Examples: '**/*.java' (all java files), 'src/**/*.ts' (ts under src),
            '**/*.{js,ts}' (js or ts). Returns matching file paths (relative to
            searchPath when possible). No shell execution — pure Java PathMatcher.
            Supports offset pagination for large result sets. When truncated,
            use offset=nextOffset to get the next page.""")
    public String glob(
            @ToolParam(description = "Glob pattern, e.g. '**/*.java' or 'src/**/*.ts'") String pattern,
            @ToolParam(description = "Base directory to search in (absolute or relative). Defaults to workspace root", required = false) String searchPath,
            @ToolParam(description = "Skip the first N matches (pagination). Use nextOffset from a truncated result to get the next page. Default 0", required = false) Integer offset) {

        JSONObject result = new JSONObject();

        try {
            if (pattern == null || pattern.isBlank()) {
                result.set("error", true);
                result.set("message", "Pattern is required");
                return JSONUtil.toJsonPrettyStr(result);
            }

            Path base;
            try {
                String baseDir = (searchPath == null || searchPath.isBlank()) ? "." : searchPath;
                base = vip.mate.tool.guard.WorkspacePathGuard.validatePath(baseDir);
            } catch (IllegalArgumentException e) {
                result.set("error", true);
                result.set("message", e.getMessage());
                return JSONUtil.toJsonPrettyStr(result);
            }

            if (!Files.exists(base)) {
                result.set("error", true);
                result.set("message", "Search path does not exist: " + base);
                return JSONUtil.toJsonPrettyStr(result);
            }

            int skip = (offset != null && offset > 0) ? offset : 0;

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            JSONArray matches = new JSONArray();
            int[] totalScanned = {0};
            int[] totalMatched = {0};

            if (Files.isDirectory(base)) {
                Files.walkFileTree(base, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path relative = base.relativize(file);
                        if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                            totalMatched[0]++;
                            if (totalMatched[0] > skip && matches.size() < MAX_RESULTS) {
                                matches.add(file.toString().replace('\\', '/'));
                            }
                        }
                        // Keep walking even after filling the page so we know the true total
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        // Skip hidden / build output dirs
                        String name = dir.getFileName().toString();
                        if (name.equals(".git") || name.equals("node_modules") || name.equals("target")
                                || name.equals("build") || name.equals("dist") || name.equals(".idea")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                // base is a single file — match against filename
                if (matcher.matches(base.getFileName())) {
                    totalMatched[0] = 1;
                    if (skip < 1) {
                        matches.add(base.toString().replace('\\', '/'));
                    }
                }
            }

            result.set("pattern", pattern);
            result.set("searchPath", base.toString().replace('\\', '/'));
            result.set("matches", matches);
            result.set("count", matches.size());
            result.set("offset", skip);
            if (skip + matches.size() < totalMatched[0]) {
                result.set("truncated", true);
                result.set("totalMatches", totalMatched[0]);
                result.set("nextOffset", skip + MAX_RESULTS);
                result.set("message", "Showing " + (skip + 1) + "-" + (skip + matches.size())
                        + " of " + totalMatched[0] + " matches. Use offset=" + (skip + MAX_RESULTS)
                        + " for the next page");
            } else {
                result.set("truncated", false);
                result.set("totalMatches", totalMatched[0]);
                result.set("message", "Found " + totalMatched[0] + " file(s), showing "
                        + (skip + 1) + "-" + (skip + matches.size()));
            }

        } catch (Exception e) {
            log.error("[Glob] Failed: {}", e.getMessage(), e);
            result.set("error", true);
            result.set("message", e.getMessage());
        }

        return JSONUtil.toJsonPrettyStr(result);
    }
}
