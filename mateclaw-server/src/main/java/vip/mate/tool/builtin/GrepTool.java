package vip.mate.tool.builtin;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内置工具：文件内容正则搜索（grep）
 * <p>
 * 在指定目录下递归搜索文件内容，支持正则表达式。
 * 返回匹配行及行号、文件路径。不需要 shell out。
 * 路径边界由 {@code WorkspacePathGuard} 强制。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class GrepTool {

    private static final int MAX_MATCHES = 500;
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    private static final List<String> SKIP_DIRS = List.of(
            ".git", "node_modules", "target", "build", "dist", ".idea", ".vs", "out"
    );

    private static final List<String> TEXT_EXTENSIONS = List.of(
            ".java", ".kt", ".scala", ".groovy", ".js", ".ts", ".tsx", ".jsx",
            ".py", ".rb", ".go", ".rs", ".c", ".cpp", ".h", ".hpp", ".cs",
            ".php", ".swift", ".m", ".mm", ".sh", ".bash", ".zsh", ".ps1",
            ".yml", ".yaml", ".json", ".xml", ".html", ".css", ".scss", ".less",
            ".md", ".txt", ".sql", ".properties", ".conf", ".cfg", ".ini",
            ".toml", ".gradle", ".vue", ".svelte", ".dart", ".lua", ".r",
            ".jl", ".ex", ".exs", ".clj", ".cljs", ".edn", ".vim", ".el"
    );

    @Tool(description = """
            Search file contents with regex (like grep/ripgrep). Recursively \
            searches a directory, returning matching lines with line numbers \
            and file paths. Uses Java regex syntax. Only searches text files, \
            skips binary/build output. Returns structured JSON with matches \
            array. Supports offset pagination for large result sets. When \
            truncated, use offset=nextOffset to get the next page. No shell \
            execution — pure Java.""")
    public String grep(
            @ToolParam(description = "Java regex pattern to search for") String pattern,
            @ToolParam(description = "Directory to search in (absolute or relative). Defaults to workspace root", required = false) String searchPath,
            @ToolParam(description = "File extension filter, e.g. '.java' or '.ts'. Omit to search all text files", required = false) String fileExtension,
            @ToolParam(description = "Case-insensitive search, default false", required = false) Boolean ignoreCase,
            @ToolParam(description = "Also return lines before match (context), default 0", required = false) Integer beforeContext,
            @ToolParam(description = "Also return lines after match (context), default 0", required = false) Integer afterContext,
            @ToolParam(description = "Skip the first N matches (pagination). Use nextOffset from a truncated result to get the next page. Default 0", required = false) Integer offset) {

        JSONObject result = new JSONObject();

        try {
            if (pattern == null || pattern.isBlank()) {
                result.set("error", true);
                result.set("message", "Pattern is required");
                return JSONUtil.toJsonPrettyStr(result);
            }

            Pattern regex;
            try {
                int flags = (ignoreCase != null && ignoreCase) ? Pattern.CASE_INSENSITIVE : 0;
                regex = Pattern.compile(pattern, flags);
            } catch (PatternSyntaxException e) {
                result.set("error", true);
                result.set("message", "Invalid regex: " + e.getMessage());
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

            String extFilter = (fileExtension != null && !fileExtension.isBlank())
                    ? (fileExtension.startsWith(".") ? fileExtension : "." + fileExtension)
                    : null;

            int before = (beforeContext != null && beforeContext > 0) ? Math.min(beforeContext, 10) : 0;
            int after = (afterContext != null && afterContext > 0) ? Math.min(afterContext, 10) : 0;
            int skip = (offset != null && offset > 0) ? offset : 0;

            JSONArray matches = new JSONArray();
            int[] totalScanned = {0};
            int[] totalMatches = {0};
            int[] filesWithMatches = {0};

            List<Path> filesToSearch = new ArrayList<>();
            collectTextFiles(base, extFilter, filesToSearch);

            for (Path file : filesToSearch) {
                if (totalMatches[0] >= MAX_MATCHES) break;

                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    boolean fileHadMatch = false;

                    for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
                        if (totalMatches[0] >= MAX_MATCHES) break;

                        String line = lines.get(lineIdx);
                        if (regex.matcher(line).find()) {
                            totalScanned[0]++;
                            if (totalScanned[0] <= skip) continue;

                            JSONObject match = new JSONObject();
                            match.set("file", file.toString().replace('\\', '/'));
                            match.set("lineNumber", lineIdx + 1);
                            match.set("line", line);

                            if (before > 0) {
                                JSONArray beforeLines = new JSONArray();
                                for (int b = Math.max(0, lineIdx - before); b < lineIdx; b++) {
                                    JSONObject bl = new JSONObject();
                                    bl.set("lineNumber", b + 1);
                                    bl.set("line", lines.get(b));
                                    beforeLines.add(bl);
                                }
                                match.set("before", beforeLines);
                            }

                            if (after > 0) {
                                JSONArray afterLines = new JSONArray();
                                for (int a = lineIdx + 1; a <= Math.min(lines.size() - 1, lineIdx + after); a++) {
                                    JSONObject al = new JSONObject();
                                    al.set("lineNumber", a + 1);
                                    al.set("line", lines.get(a));
                                    afterLines.add(al);
                                }
                                match.set("after", afterLines);
                            }

                            matches.add(match);
                            totalMatches[0]++;
                            fileHadMatch = true;
                        }
                    }

                    if (fileHadMatch) filesWithMatches[0]++;

                } catch (IOException e) {
                    log.debug("[Grep] Could not read {}: {}", file, e.getMessage());
                }
            }

            result.set("pattern", pattern);
            result.set("searchPath", base.toString().replace('\\', '/'));
            result.set("matches", matches);
            result.set("matchCount", matches.size());
            result.set("filesWithMatches", filesWithMatches[0]);
            result.set("offset", skip);
            if (totalScanned[0] >= MAX_MATCHES + skip) {
                result.set("truncated", true);
                result.set("nextOffset", skip + MAX_MATCHES);
                result.set("message", "Showing matches " + (skip + 1) + "-" + (skip + matches.size())
                        + ". Use offset=" + (skip + MAX_MATCHES) + " for the next page");
            } else {
                result.set("truncated", false);
                result.set("message", "Found " + (skip + matches.size()) + " match(es) in " + filesWithMatches[0] + " file(s)");
            }

        } catch (Exception e) {
            log.error("[Grep] Failed: {}", e.getMessage(), e);
            result.set("error", true);
            result.set("message", e.getMessage());
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    private void collectTextFiles(Path base, String extFilter, List<Path> out) throws IOException {
        if (Files.isRegularFile(base)) {
            out.add(base);
            return;
        }

        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (SKIP_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > MAX_FILE_SIZE) return FileVisitResult.CONTINUE;

                String name = file.getFileName().toString();
                String lowerName = name.toLowerCase();

                if (extFilter != null) {
                    if (lowerName.endsWith(extFilter.toLowerCase())) {
                        out.add(file);
                    }
                } else {
                    // Check known text extensions
                    for (String ext : TEXT_EXTENSIONS) {
                        if (lowerName.endsWith(ext)) {
                            out.add(file);
                            break;
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
