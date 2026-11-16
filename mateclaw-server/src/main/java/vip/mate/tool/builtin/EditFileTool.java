package vip.mate.tool.builtin;

import vip.mate.workspace.core.service.MemberFileIsolation;
import vip.mate.workspace.core.service.MemberFileAccess;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.i18n.I18nService;
import vip.mate.tool.ConcurrencyUnsafe;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内置工具：编辑文件（查找替换）
 * <p>
 * 通过精确字符串匹配进行查找替换。
 * 支持替换首次匹配或全部匹配。
 * <p>
 * 安全说明：
 * <ul>
 *   <li>编辑操作会经过 ToolGuard 安全检查；命中安全规则时会要求用户审批</li>
 *   <li>路径边界由 {@code WorkspacePathGuard} 处理</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class EditFileTool {

    private final I18nService i18n;

    @ConcurrencyUnsafe("in-place file edit — must not race with reads/writes on the same path")
    @Tool(description = "Edit file content via find-and-replace. Matches old_text allowing newline-style differences and preserves file line endings. "
            + "Returns structured JSON with filePath, replacements count. "
            + "May require user approval when security rules flag the edit. "
            + "Replaces first occurrence by default; set replaceAll=true for all.")
    public String edit_file(
            @ToolParam(description = "Absolute or relative file path") String filePath,
            @ToolParam(description = "Original text to find (exact match)") String oldText,
            @ToolParam(description = "Replacement text") String newText,
            @ToolParam(description = "Replace all occurrences, default false (first only)", required = false) Boolean replaceAll,
            @Nullable ToolContext ctx) {

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
                path = WorkspacePathGuard.validatePath(filePath, ctx);
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

            // 读取文件内容
            String content = MemberFileIsolation.isEnabled()
                    ? new String(MemberFileAccess.read(MemberFileIsolation.root(ctx), path, 32 * 1024 * 1024), StandardCharsets.UTF_8)
                    : Files.readString(path, StandardCharsets.UTF_8);

            Replacement edit = replacePreservingNewlines(content, oldText, newText,
                    Boolean.TRUE.equals(replaceAll));
            if (edit.count() == 0) {
                return errorResult(filePath, i18n.msg("tool.edit_file.error.old_not_found"));
            }
            String newContent = edit.content();
            int replacements = edit.count();
            boolean doReplaceAll = Boolean.TRUE.equals(replaceAll);

            // 写回文件
            if (MemberFileIsolation.isEnabled()) MemberFileAccess.write(MemberFileIsolation.root(ctx), path, newContent.getBytes(StandardCharsets.UTF_8), false);
            else Files.writeString(path, newContent, StandardCharsets.UTF_8);

            result.set("replacements", replacements);
            result.set("replaceAll", doReplaceAll);
            result.set("message", "Edit successful: " + replacements + " replacement(s)");

            log.info("[EditFile] Edited {}: {} replacement(s)", path, replacements);

        } catch (Exception e) {
            log.error("[EditFile] Failed to edit file: {}", e.getMessage(), e);
            return errorResult(filePath, i18n.msg("tool.edit_file.error.edit_exception", e.getMessage()));
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    private record Replacement(String content, int count) {}

    /** Match an LF view, but copy untouched spans directly from the original. */
    static Replacement replacePreservingNewlines(String original, String oldText, String newText, boolean all) {
        StringBuilder normalized = new StringBuilder();
        int[] offsets = new int[original.length() + 1];
        java.util.List<String> endings = new java.util.ArrayList<>();
        for (int i = 0; i < original.length(); i++) {
            offsets[normalized.length()] = i;
            char c = original.charAt(i);
            if (c == '\r') {
                boolean paired = i + 1 < original.length() && original.charAt(i + 1) == '\n';
                endings.add(paired ? "\r\n" : "\r");
                if (paired) i++;
                normalized.append('\n');
            } else {
                if (c == '\n') endings.add("\n");
                normalized.append(c);
            }
        }
        offsets[normalized.length()] = original.length();
        String view = normalized.toString();
        String needle = toLf(oldText);
        String replacement = toLf(newText);
        String fallback = endings.isEmpty() ? "\n" : endings.get(0);
        StringBuilder output = new StringBuilder();
        int cursor = 0, count = 0, match;
        while ((match = view.indexOf(needle, cursor)) >= 0) {
            output.append(original, offsets[cursor], offsets[match]);
            String span = original.substring(offsets[match], offsets[match + needle.length()]);
            java.util.List<String> local = new java.util.ArrayList<>();
            var matcher = java.util.regex.Pattern.compile("\r\n|\r|\n").matcher(span);
            while (matcher.find()) local.add(matcher.group());
            long breaks = replacement.chars().filter(c -> c == '\n').count();
            String style = local.isEmpty() ? fallback : local.get(0);
            int index = 0;
            for (int i = 0; i < replacement.length(); i++) {
                char c = replacement.charAt(i);
                if (c == '\n') output.append(local.size() == breaks ? local.get(index++) : style);
                else output.append(c);
            }
            count++;
            cursor = match + needle.length();
            if (!all) break;
        }
        output.append(original, offsets[cursor], original.length());
        return new Replacement(output.toString(), count);
    }

    private static String toLf(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String errorResult(String filePath, String message) {
        JSONObject result = new JSONObject();
        result.set("filePath", filePath);
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
