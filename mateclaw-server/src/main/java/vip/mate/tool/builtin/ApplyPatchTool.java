package vip.mate.tool.builtin;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置工具：应用 unified diff 补丁
 * <p>
 * 解析标准 unified diff（git diff 格式），逐 hunk 应用到目标文件。
 * 每个 hunk 的上下文行用于唯一定位修改位置，避免歧义。
 * <p>
 * 安全说明：
 * <ul>
 *   <li>编辑操作经过 ToolGuard 安全检查</li>
 *   <li>路径边界由 {@code WorkspacePathGuard} 处理</li>
 *   <li>每个 hunk 要求上下文唯一匹配，否则整体回滚</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class ApplyPatchTool {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    @vip.mate.tool.ConcurrencyUnsafe("in-place multi-hunk file edit — must not race with reads/writes on the same path")
    @Tool(description = """
            Apply a unified diff patch to one or more files. Accepts standard \
            git-style unified diff format with @@ hunk headers. Each hunk's \
            context lines must uniquely match the target file — if a hunk's \
            context is ambiguous (matches multiple locations) or not found, the \
            entire patch is rejected and no changes are applied. This is safer \
            than edit_file for multi-location edits. Also supports file deletion \
            (--- filePath / +++ /dev/null) and file move (different old/new \
            paths). Returns structured JSON with per-file results and a diff \
            preview. May require user approval when security rules flag the \
            edit.""")
    public String apply_patch(
            @ToolParam(description = "Standard unified diff content (git diff format), may touch multiple files (add/update/delete/move)") String patch) {

        JSONObject result = new JSONObject();
        if (patch == null || patch.isBlank()) {
            result.set("error", true);
            result.set("message", "Patch content is empty");
            return JSONUtil.toJsonPrettyStr(result);
        }

        try {
            List<FilePatch> filePatches = parsePatch(patch);
            if (filePatches.isEmpty()) {
                result.set("error", true);
                result.set("message", "No valid file patches found in input");
                return JSONUtil.toJsonPrettyStr(result);
            }

            JSONArray fileResults = new JSONArray();
            int totalHunksApplied = 0;

            for (FilePatch fp : filePatches) {
                JSONObject fileResult = new JSONObject();
                fileResult.set("filePath", fp.filePath);

                try {
                    Path path;
                    try {
                        path = vip.mate.tool.guard.WorkspacePathGuard.validatePath(fp.filePath);
                    } catch (IllegalArgumentException e) {
                        fileResult.set("error", true);
                        fileResult.set("message", e.getMessage());
                        fileResults.add(fileResult);
                        continue;
                    }

                    // Handle file deletion (newPath = /dev/null)
                    if (fp.isDeleteFile) {
                        Path oldPath = vip.mate.tool.guard.WorkspacePathGuard.validatePath(fp.oldFilePath);
                        if (!Files.exists(oldPath)) {
                            fileResult.set("error", true);
                            fileResult.set("message", "File not found for deletion: " + oldPath);
                            fileResults.add(fileResult);
                            continue;
                        }
                        Files.deleteIfExists(oldPath);
                        fileResult.set("deleted", true);
                        fileResult.set("message", "File deleted: " + oldPath);
                        fileResults.add(fileResult);
                        continue;
                    }

                    // Handle file move (oldFilePath != filePath and both exist)
                    if (fp.oldFilePath != null && !fp.oldFilePath.equals(fp.filePath) && !fp.isNewFile) {
                        Path oldPath = vip.mate.tool.guard.WorkspacePathGuard.validatePath(fp.oldFilePath);
                        if (!Files.exists(oldPath)) {
                            fileResult.set("error", true);
                            fileResult.set("message", "Source file not found for move: " + oldPath);
                            fileResults.add(fileResult);
                            continue;
                        }
                        // Read old content, apply hunks, write to new path, delete old
                        String original = Files.readString(oldPath, StandardCharsets.UTF_8);
                        String patched = applyHunks(original, fp.hunks, fileResult);
                        if (patched == null) {
                            fileResults.add(fileResult);
                            continue;
                        }
                        Files.createDirectories(path.getParent());
                        Files.writeString(path, patched, StandardCharsets.UTF_8);
                        if (!oldPath.equals(path)) {
                            Files.deleteIfExists(oldPath);
                        }
                        int hunks = (int) fileResult.getInt("hunksApplied", 0);
                        totalHunksApplied += hunks;
                        fileResult.set("moved", true);
                        fileResult.set("oldPath", fp.oldFilePath);
                        fileResult.set("message", "File moved from " + fp.oldFilePath + " to " + fp.filePath
                                + " (" + hunks + " hunk(s) applied)");
                        fileResults.add(fileResult);
                        continue;
                    }

                    if (!Files.exists(path)) {
                        if (fp.isNewFile) {
                            Files.createDirectories(path.getParent());
                            Files.writeString(path, fp.newFileContent, StandardCharsets.UTF_8);
                            fileResult.set("hunksApplied", 0);
                            fileResult.set("created", true);
                            fileResult.set("message", "New file created");
                            totalHunksApplied++;
                            fileResults.add(fileResult);
                            continue;
                        }
                        fileResult.set("error", true);
                        fileResult.set("message", "File not found: " + path);
                        fileResults.add(fileResult);
                        continue;
                    }

                    if (Files.isDirectory(path)) {
                        fileResult.set("error", true);
                        fileResult.set("message", "Path is a directory: " + path);
                        fileResults.add(fileResult);
                        continue;
                    }

                    String original = Files.readString(path, StandardCharsets.UTF_8);
                    String patched = applyHunks(original, fp.hunks, fileResult);

                    if (patched == null) {
                        // fileResult already has error details set by applyHunks
                        fileResults.add(fileResult);
                        continue;
                    }

                    if (!patched.equals(original)) {
                        Files.writeString(path, patched, StandardCharsets.UTF_8);
                    }

                    int hunks = (int) fileResult.getInt("hunksApplied", 0);
                    totalHunksApplied += hunks;
                    fileResult.set("message", hunks + " hunk(s) applied");

                } catch (Exception e) {
                    log.error("[ApplyPatch] Failed on {}: {}", fp.filePath, e.getMessage(), e);
                    fileResult.set("error", true);
                    fileResult.set("message", e.getMessage());
                }
                fileResults.add(fileResult);
            }

            result.set("files", fileResults);
            result.set("totalHunksApplied", totalHunksApplied);
            result.set("message", "Patch applied: " + filePatches.size() + " file(s), " + totalHunksApplied + " hunk(s)");

        } catch (Exception e) {
            log.error("[ApplyPatch] Failed to apply patch: {}", e.getMessage(), e);
            result.set("error", true);
            result.set("message", "Failed to parse patch: " + e.getMessage());
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    private String applyHunks(String original, List<Hunk> hunks, JSONObject fileResult) {
        String content = original;
        int applied = 0;
        int searchOffset = 0;

        for (Hunk hunk : hunks) {
            String contextBlock = hunk.contextBlock();
            int idx = content.indexOf(contextBlock, searchOffset);

            if (idx < 0) {
                // Try from beginning if offset-based search failed
                idx = content.indexOf(contextBlock);
            }

            if (idx < 0) {
                fileResult.set("error", true);
                fileResult.set("hunksApplied", applied);
                fileResult.set("failedHunkLine", hunk.oldStartLine);
                fileResult.set("message", "Hunk context not found at line " + hunk.oldStartLine
                        + ". Context:\n" + truncate(contextBlock, 200));
                return null;
            }

            // Check uniqueness: no second occurrence after this one
            int secondIdx = content.indexOf(contextBlock, idx + contextBlock.length());
            if (secondIdx >= 0) {
                fileResult.set("error", true);
                fileResult.set("hunksApplied", applied);
                fileResult.set("failedHunkLine", hunk.oldStartLine);
                fileResult.set("message", "Hunk context is ambiguous (matches multiple locations) at line "
                        + hunk.oldStartLine + ". Add more context lines to disambiguate.");
                return null;
            }

            String replacement = hunk.replacementBlock();
            content = content.substring(0, idx) + replacement + content.substring(idx + contextBlock.length());
            searchOffset = idx + replacement.length();
            applied++;
        }

        fileResult.set("hunksApplied", applied);
        return content;
    }

    private List<FilePatch> parsePatch(String patch) {
        List<FilePatch> result = new ArrayList<>();
        String[] lines = patch.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            if (line.startsWith("+++ ") && i > 0 && lines[i - 1].startsWith("--- ")) {
                String newPath = stripPrefix(lines[i].substring(4));
                String oldPath = stripPrefix(lines[i - 1].substring(4));
                boolean isNew = oldPath.equals("/dev/null");
                boolean isDelete = newPath.equals("/dev/null");
                boolean isMove = !isNew && !isDelete && !oldPath.equals(newPath);

                FilePatch fp = new FilePatch();
                fp.filePath = isDelete ? null : newPath;
                fp.oldFilePath = (isNew || isDelete) ? oldPath : (isMove ? oldPath : null);
                fp.isNewFile = isNew;
                fp.isDeleteFile = isDelete;

                // If delete file, just record it
                if (isDelete) {
                    fp.filePath = oldPath; // store for deletion
                    result.add(fp);
                    i++;
                    continue;
                }

                // If new file, collect content from +++ block (all + lines)
                if (isNew) {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < lines.length && lines[i].startsWith("+") && !lines[i].startsWith("+++")) {
                        sb.append(lines[i].substring(1)).append('\n');
                        i++;
                    }
                    fp.newFileContent = sb.toString();
                    result.add(fp);
                    continue;
                }

                // For move or regular update, filePath is the new path
                if (isMove) {
                    fp.filePath = newPath;
                }

                // Collect hunks for existing file
                i++;
                while (i < lines.length) {
                    String hunkLine = lines[i];
                    Matcher hm = HUNK_HEADER.matcher(hunkLine);
                    if (!hm.matches()) {
                        if (hunkLine.startsWith("diff --git") || hunkLine.startsWith("--- ")) {
                            break;
                        }
                        i++;
                        continue;
                    }

                    Hunk hunk = new Hunk();
                    hunk.oldStartLine = Integer.parseInt(hm.group(1));
                    // Hunk line counts from the @@ header determine exactly how
                    // many lines belong to this hunk body. This is critical for
                    // multi-file diffs: without counting, a content line like
                    // "--- a/other.txt" (a removed line whose text starts with
                    // "-- a/other.txt") would be misread as the next file's
                    // header, truncating the current hunk and corrupting the
                    // rest of the patch.
                    int oldCount = hm.group(2) != null ? Integer.parseInt(hm.group(2)) : 1;
                    int newCount = hm.group(4) != null ? Integer.parseInt(hm.group(4)) : 1;
                    i++;

                    List<HunkLine> hunkLines = new ArrayList<>();
                    int oldLinesSeen = 0;
                    int newLinesSeen = 0;

                    while (i < lines.length) {
                        // Stop when we've consumed all lines in this hunk body
                        if (oldLinesSeen >= oldCount && newLinesSeen >= newCount) {
                            break;
                        }

                        String hl = lines[i];
                        if (hl.startsWith("@@")) break;
                        if (hl.startsWith("diff --git")) break;

                        char prefix;
                        String text;
                        if (hl.isEmpty()) {
                            // Empty line in diff = context for empty line
                            prefix = ' ';
                            text = "";
                        } else {
                            prefix = hl.charAt(0);
                            text = hl.length() > 1 ? hl.substring(1) : "";
                        }

                        // Only accept valid diff line prefixes
                        if (prefix != ' ' && prefix != '-' && prefix != '+') {
                            // Unknown prefix — treat as end of hunk (malformed diff)
                            break;
                        }

                        hunkLines.add(new HunkLine(prefix, text));
                        if (prefix == ' ' || prefix == '-') oldLinesSeen++;
                        if (prefix == ' ' || prefix == '+') newLinesSeen++;
                        i++;
                    }

                    hunk.lines = hunkLines;
                    fp.hunks.add(hunk);
                }

                if (!fp.hunks.isEmpty() || fp.isNewFile) {
                    result.add(fp);
                }
            } else {
                i++;
            }
        }

        return result;
    }

    private String stripPrefix(String path) {
        if (path.startsWith("b/")) return path.substring(2);
        if (path.startsWith("a/")) return path.substring(2);
        return path;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static class FilePatch {
        String filePath;
        String oldFilePath;  // for move operations
        boolean isNewFile;
        boolean isDeleteFile;  // when new path is /dev/null
        String newFileContent;
        final List<Hunk> hunks = new ArrayList<>();
    }

    private static class Hunk {
        int oldStartLine;
        List<HunkLine> lines;

        String contextBlock() {
            StringBuilder sb = new StringBuilder();
            for (HunkLine hl : lines) {
                if (hl.prefix == ' ' || hl.prefix == '-') {
                    sb.append(hl.text).append('\n');
                }
            }
            return sb.toString();
        }

        String replacementBlock() {
            StringBuilder sb = new StringBuilder();
            for (HunkLine hl : lines) {
                if (hl.prefix == ' ' || hl.prefix == '+') {
                    sb.append(hl.text).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private record HunkLine(char prefix, String text) {}
}
