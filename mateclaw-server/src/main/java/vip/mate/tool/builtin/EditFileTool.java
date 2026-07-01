package vip.mate.tool.builtin;

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

/**
 * 内置工具：编辑文件（查找替换）
 * <p>
 * 借鉴 opencode 的 multi-strategy fuzzy matching 设计：当 LLM 提供的
 * oldText 无法精确匹配时，按顺序尝试多个 Replacer 策略（行 trim、空白
 * 归一化、缩进灵活、转义归一化等），大幅提升编辑成功率。
 * <p>
 * 安全说明：
 * <ul>
 *   <li>编辑操作会经过 ToolGuard 安全检查；命中安全规则时会要求用户审批</li>
 *   <li>路径边界由 {@code WorkspacePathGuard} 处理</li>
 *   <li>唯一匹配校验：非 replaceAll 模式下，多处匹配会拒绝并要求更多上下文</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class EditFileTool {

    private final vip.mate.i18n.I18nService i18n;

    @vip.mate.tool.ConcurrencyUnsafe("in-place file edit — must not race with reads/writes on the same path")
    @Tool(description = """
            Edit file content via find-and-replace. Finds match of old_text and \
            replaces with new_text. Uses multi-strategy fuzzy matching: if exact \
            match fails, tries line-trimmed, whitespace-normalized, \
            indentation-flexible, and escape-normalized matching in order. \
            Non-replaceAll mode requires unique match — if old_text matches \
            multiple locations, the edit is rejected with a message asking for \
            more surrounding context. Returns structured JSON with filePath, \
            replacements count, match strategy used, and a unified diff preview. \
            May require user approval when security rules flag the edit.""")
    public String edit_file(
            @ToolParam(description = "Absolute or relative file path") String filePath,
            @ToolParam(description = "Original text to find (exact match preferred; fuzzy matching used as fallback)") String oldText,
            @ToolParam(description = "Replacement text") String newText,
            @ToolParam(description = "Replace all occurrences, default false (first only)", required = false) Boolean replaceAll) {

        JSONObject result = new JSONObject();
        result.set("filePath", filePath);

        try {
            if (filePath == null || filePath.isBlank()) {
                return errorResult(filePath, i18n.msg("tool.edit_file.error.path_empty"));
            }
            if (oldText == null || oldText.isEmpty()) {
                return errorResult(filePath, i18n.msg("tool.edit_file.error.old_text_empty"));
            }
            if (newText == null) {
                newText = "";
            }
            if (oldText.equals(newText)) {
                return errorResult(filePath, i18n.msg("tool.edit_file.error.same_text"));
            }

            Path path;
            try {
                path = vip.mate.tool.guard.WorkspacePathGuard.validatePath(filePath);
            } catch (IllegalArgumentException e) {
                return errorResult(filePath, e.getMessage());
            }

            if (!Files.exists(path)) {
                return errorResult(filePath, i18n.msg("tool.edit_file.error.not_found", path));
            }
            if (Files.isDirectory(path)) {
                return errorResult(filePath, i18n.msg("tool.edit_file.error.is_directory", path));
            }
            if (!Files.isReadable(path) || !Files.isWritable(path)) {
                return errorResult(filePath, i18n.msg("tool.edit_file.error.not_rw", path));
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            boolean doReplaceAll = replaceAll != null && replaceAll;

            // Try each replacer strategy in order until one succeeds
            ReplacementOutcome outcome = null;
            for (Replacer replacer : REPLACERS) {
                outcome = replacer.tryReplace(content, oldText, newText, doReplaceAll);
                if (outcome != null) {
                    break;
                }
            }

            if (outcome == null) {
                // All strategies failed — give the LLM actionable feedback
                String hint = buildNotFoundHint(content, oldText);
                return errorResult(filePath, "Could not find old_text in file. " + hint);
            }

            // Disproportionate match rejection: if the matched span is much
            // larger than oldText, the fuzzy matcher likely ate too much.
            if (outcome.matchedLength > oldText.length() * 3 && outcome.matchedLength > oldText.length() + 200) {
                return errorResult(filePath,
                        "Refusing replacement because the matched span (" + outcome.matchedLength
                                + " chars) is much larger than old_text (" + oldText.length()
                                + " chars). Provide a more precise old_text with exact content.");
            }

            // Write the new content
            Files.writeString(path, outcome.newContent, StandardCharsets.UTF_8);

            // Build a diff preview (first 30 changed lines)
            String diffPreview = buildDiffPreview(content, outcome.newContent, path.getFileName().toString());

            result.set("replacements", outcome.replacements);
            result.set("replaceAll", doReplaceAll);
            result.set("matchStrategy", outcome.strategyName);
            result.set("diffPreview", diffPreview);

            // Lightweight post-edit syntax check (opencode LSP diagnostic loop —
            // simplified version: run a quick syntax check for common code files,
            // include any errors in the result so the LLM can self-correct).
            String syntaxWarning = postEditSyntaxCheck(path);
            if (syntaxWarning != null) {
                result.set("syntaxWarning", syntaxWarning);
                result.set("message", "Edit successful: " + outcome.replacements
                        + " replacement(s) via " + outcome.strategyName
                        + ". WARNING: syntax check found issues — see syntaxWarning field.");
            } else {
                result.set("message", "Edit successful: " + outcome.replacements
                        + " replacement(s) via " + outcome.strategyName);
            }

            log.info("[EditFile] Edited {}: {} replacement(s) via {}",
                    path, outcome.replacements, outcome.strategyName);

        } catch (Exception e) {
            log.error("[EditFile] Failed to edit file: {}", e.getMessage(), e);
            return errorResult(filePath, i18n.msg("tool.edit_file.error.edit_exception", e.getMessage()));
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    // ==================== Multi-strategy Replacer (opencode-inspired, line-based) ====================

    /**
     * A replacer strategy attempts to find {@code oldText} in {@code content}
     * and replace it with {@code newText}. Returns null if no match.
     *
     * <p>All strategies use <b>line-level matching</b>: split both content and
     * oldText into lines, apply a per-line transformation, find a consecutive
     * window of content lines whose transformed versions match the transformed
     * oldText lines, then replace those <em>original</em> lines with newText.
     * This avoids the "map back from transformed space" problem that caused
     * duplicate lines in the previous whole-string approach.
     */
    private interface Replacer {
        ReplacementOutcome tryReplace(String content, String oldText, String newText, boolean replaceAll);
    }

    /** Result of a successful replacement. */
    private static class ReplacementOutcome {
        final String newContent;
        final int replacements;
        final int matchedLength;
        final String strategyName;

        ReplacementOutcome(String newContent, int replacements, int matchedLength, String strategyName) {
            this.newContent = newContent;
            this.replacements = replacements;
            this.matchedLength = matchedLength;
            this.strategyName = strategyName;
        }
    }

    /**
     * Transform a single line for matching. Each strategy provides a different
     * transformation (identity, trim, whitespace-normalize, etc.).
     */
    private interface LineTransformer {
        String transform(String line);
    }

    // --- Strategy 1: Exact match (whole-string, not line-based) ---
    private static final Replacer EXACT = (content, oldText, newText, replaceAll) -> {
        if (!content.contains(oldText)) return null;
        if (!replaceAll) {
            int first = content.indexOf(oldText);
            int last = content.lastIndexOf(oldText);
            if (first != last) return null; // multiple matches — reject
            String nc = content.substring(0, first) + newText + content.substring(first + oldText.length());
            return new ReplacementOutcome(nc, 1, oldText.length(), "exact");
        }
        int count = countOccurrences(content, oldText);
        return new ReplacementOutcome(content.replace(oldText, newText), count, oldText.length(), "exact");
    };

    // --- Strategy 2: Line-trimmed match ---
    private static final Replacer LINE_TRIMMED = createLineBasedReplacer(
            "line-trimmed",
            line -> line.strip()
    );

    // --- Strategy 3: Whitespace-normalized match ---
    private static final Replacer WHITESPACE_NORMALIZED = createLineBasedReplacer(
            "whitespace-normalized",
            line -> line.replaceAll("\\s+", " ").strip()
    );

    // --- Strategy 4: Indentation-flexible match (strip leading whitespace) ---
    private static final Replacer INDENTATION_FLEXIBLE = createLineBasedReplacer(
            "indentation-flexible",
            line -> line.stripIndent()
    );

    // --- Strategy 5: Escape-normalized match ---
    private static final Replacer ESCAPE_NORMALIZED = createLineBasedReplacer(
            "escape-normalized",
            line -> line.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t")
    );

    /**
     * Create a line-based replacer that uses the given per-line transformer.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Split content and oldText into lines</li>
     *   <li>Transform each line of both</li>
     *   <li>Find a consecutive window in content whose transformed lines
     *       match the transformed oldText lines</li>
     *   <li>Replace those original (untransformed) content lines with newText</li>
     * </ol>
     *
     * <p>This is the key fix for the duplicate-lines bug: the replacement
     * always operates on original lines, never on transformed text, so the
     * boundary is always a clean line boundary.
     */
    private static Replacer createLineBasedReplacer(String strategyName, LineTransformer transformer) {
        return (content, oldText, newText, replaceAll) -> {
            String[] contentLines = content.split("\n", -1);
            String[] oldLines = oldText.split("\n", -1);
            if (oldLines.length == 0 || oldLines.length > contentLines.length) return null;

            // Transform oldText lines
            String[] transformedOld = new String[oldLines.length];
            for (int i = 0; i < oldLines.length; i++) {
                transformedOld[i] = transformer.transform(oldLines[i]);
            }

            // Find all matching windows in content
            List<int[]> matches = new ArrayList<>(); // each: [startLine, endLine] (0-based, inclusive)
            for (int start = 0; start <= contentLines.length - oldLines.length; start++) {
                boolean match = true;
                for (int j = 0; j < oldLines.length; j++) {
                    String transformedContentLine = transformer.transform(contentLines[start + j]);
                    if (!transformedContentLine.equals(transformedOld[j])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    matches.add(new int[]{start, start + oldLines.length - 1});
                    if (!replaceAll) break; // only need first match for single replace
                }
            }

            if (matches.isEmpty()) return null;
            if (!replaceAll && matches.size() > 1) return null; // ambiguous — reject

            // Perform replacement: replace original lines [startLine..endLine] with newText
            // Process from end to start so indices don't shift
            List<int[]> toReplace = replaceAll ? matches : List.of(matches.get(0));
            StringBuilder result = new StringBuilder();
            int pos = 0; // line index in contentLines
            int totalMatchedChars = 0;

            for (int[] range : toReplace) {
                int startLine = range[0];
                int endLine = range[1];

                // Append unchanged lines before this match
                for (int i = pos; i < startLine; i++) {
                    result.append(contentLines[i]).append('\n');
                }

                // Calculate matched char count (for disproportionate check)
                for (int i = startLine; i <= endLine; i++) {
                    totalMatchedChars += contentLines[i].length() + 1; // +1 for \n
                }

                // Append replacement text
                result.append(newText);

                // Ensure exactly one newline after replacement (if there are more lines)
                pos = endLine + 1;
            }

            // Append remaining lines
            for (int i = pos; i < contentLines.length; i++) {
                result.append(contentLines[i]);
                if (i < contentLines.length - 1) result.append('\n');
            }

            // Handle trailing newline: if original content ended with \n and we
            // didn't add one, add it. If original didn't end with \n, don't add.
            boolean originalEndsWithNewline = content.endsWith("\n");
            boolean resultEndsWithNewline = result.length() > 0
                    && result.charAt(result.length() - 1) == '\n';
            if (originalEndsWithNewline && !resultEndsWithNewline) {
                result.append('\n');
            }

            return new ReplacementOutcome(result.toString(), toReplace.size(),
                    totalMatchedChars, strategyName);
        };
    }

    // All replacers in priority order
    private static final List<Replacer> REPLACERS = List.of(
            EXACT,
            LINE_TRIMMED,
            WHITESPACE_NORMALIZED,
            INDENTATION_FLEXIBLE,
            ESCAPE_NORMALIZED
    );

    // ==================== Helpers ====================

    private static int countOccurrences(String text, String target) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    /**
     * Build a short hint when oldText is not found, showing nearby content
     * to help the LLM correct its oldText.
     */
    private static String buildNotFoundHint(String content, String oldText) {
        // Find the first non-empty line of oldText and search for it
        String[] oldLines = oldText.split("\n", -1);
        String firstLine = "";
        for (String l : oldLines) { String t = l.strip(); if (!t.isEmpty()) { firstLine = t; break; } }

        if (!firstLine.isEmpty()) {
            int idx = content.indexOf(firstLine);
            if (idx >= 0) {
                // Show context around the partial match
                int lineStart = idx;
                while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') lineStart--;
                int lineEnd = content.indexOf('\n', idx);
                if (lineEnd < 0) lineEnd = content.length();
                int contextStart = Math.max(0, lineStart - 200);
                int contextEnd = Math.min(content.length(), lineEnd + 200);
                return "Partial match found near: \""
                        + content.substring(contextStart, contextEnd).replace("\n", "\\n")
                        + "\". The surrounding text differs from old_text. "
                        + "Try copying the exact text from the file using read_file first.";
            }
        }

        return "No partial match found. Use read_file to read the file first, then copy the exact text to old_text.";
    }

    /**
     * Build a compact unified diff preview of the changes (first 30 lines).
     */
    private static String buildDiffPreview(String original, String modified, String fileName) {
        String[] oldLines = original.split("\n", -1);
        String[] newLines = modified.split("\n", -1);

        // Simple line-by-line diff (not full LCS, but good enough for preview)
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(fileName).append("\n");
        sb.append("+++ ").append(fileName).append("\n");

        int maxLines = Math.max(oldLines.length, newLines.length);
        int shown = 0;
        int maxShow = 30;

        for (int i = 0; i < maxLines && shown < maxShow; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String newLine = i < newLines.length ? newLines[i] : null;

            if (oldLine != null && newLine != null && oldLine.equals(newLine)) {
                continue; // skip unchanged lines
            }
            if (oldLine != null) {
                sb.append("- ").append(truncateLine(oldLine, 120)).append("\n");
                shown++;
            }
            if (newLine != null) {
                sb.append("+ ").append(truncateLine(newLine, 120)).append("\n");
                shown++;
            }
        }

        if (shown >= maxShow) {
            sb.append("... (diff truncated, ").append(maxLines).append(" total lines)\n");
        }
        return sb.toString();
    }

    private static String truncateLine(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Lightweight post-edit syntax check (opencode LSP diagnostic loop —
     * simplified). Detects file type by extension and runs a quick syntax
     * check via subprocess. Returns a warning string if syntax errors are
     * found, or null if the file passes (or is not a checkable type).
     *
     * <p>Non-blocking: if the check command is unavailable or times out,
     * null is returned (the edit already succeeded). Only checks file types
     * with fast, reliable syntax checkers:
     * <ul>
     *   <li>.java → javac -Xlint:none (syntax only, no classpath)</li>
     *   <li>.py → python -m py_compile</li>
     *   <li>.js → node --check</li>
     *   <li>.ts → npx tsc --noEmit (if available)</li>
     *   <li>.json → python -m json.tool (fast validation)</li>
     * </ul>
     */
    private static String postEditSyntaxCheck(Path filePath) {
        String name = filePath.getFileName().toString().toLowerCase();
        List<String> cmd = new ArrayList<>();
        Path tmpDir = null;

        if (name.endsWith(".java")) {
            try {
                tmpDir = Files.createTempDirectory("mc_syntax_check_");
            } catch (java.io.IOException e) {
                return null;
            }
            cmd.add("javac");
            cmd.add("-Xlint:none");
            cmd.add("-d");
            cmd.add(tmpDir.toString());
            cmd.add(filePath.toString());
        } else if (name.endsWith(".py")) {
            cmd.add("python");
            cmd.add("-m");
            cmd.add("py_compile");
            cmd.add(filePath.toString());
        } else if (name.endsWith(".js")) {
            cmd.add("node");
            cmd.add("--check");
            cmd.add(filePath.toString());
        } else if (name.endsWith(".json")) {
            cmd.add("python");
            cmd.add("-m");
            cmd.add("json.tool");
            cmd.add(filePath.toString());
        } else if (name.endsWith(".ts") || name.endsWith(".tsx")) {
            cmd.add("npx");
            cmd.add("--yes");
            cmd.add("tsc");
            cmd.add("--noEmit");
            cmd.add("--skipLibCheck");
            cmd.add(filePath.toString());
        } else {
            return null;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null; // timeout — don't block the edit
            }
            if (p.exitValue() == 0) return null; // syntax OK

            // Read error output (limit to 2000 chars)
            byte[] out = p.getInputStream().readAllBytes();
            String errors = new String(out, StandardCharsets.UTF_8);
            if (errors.length() > 2000) errors = errors.substring(0, 2000) + "\n... (truncated)";

            return "Syntax errors detected after edit (please fix):\n" + errors;
        } catch (Exception e) {
            // Syntax checker not available — silently skip
            return null;
        } finally {
            if (tmpDir != null) {
                deleteTempDir(tmpDir);
            }
        }
    }

    private static void deleteTempDir(Path dir) {
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    try { Files.deleteIfExists(file); } catch (Exception ignored) {}
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path d, java.io.IOException exc) {
                    try { Files.deleteIfExists(d); } catch (Exception ignored) {}
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
    }

    private String errorResult(String filePath, String message) {
        JSONObject result = new JSONObject();
        result.set("filePath", filePath);
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
