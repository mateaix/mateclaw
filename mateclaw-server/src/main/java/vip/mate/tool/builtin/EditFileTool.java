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
import java.nio.file.Paths;

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

    private final vip.mate.i18n.I18nService i18n;

    @vip.mate.tool.ConcurrencyUnsafe("in-place file edit — must not race with reads/writes on the same path")
    @Tool(description = "Edit file content via find-and-replace. Finds exact match of old_text and replaces with new_text. "
            + "Returns structured JSON with filePath, replacements count. "
            + "May require user approval when security rules flag the edit. "
            + "Replaces first occurrence by default; set replaceAll=true for all.")
    public String edit_file(
            @ToolParam(description = "Absolute or relative file path") String filePath,
            @ToolParam(description = "Original text to find (exact match)") String oldText,
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

            // 读取文件内容
            String content = Files.readString(path, StandardCharsets.UTF_8);

            // 检查 oldText 是否存在
            if (!content.contains(oldText)) {
                return errorResult(filePath, i18n.msg("tool.edit_file.error.old_not_found"));
            }

            // 执行替换
            String newContent;
            int replacements;
            boolean doReplaceAll = replaceAll != null && replaceAll;

            if (doReplaceAll) {
                // 统计匹配次数
                replacements = countOccurrences(content, oldText);
                newContent = content.replace(oldText, newText);
            } else {
                // 只替换第一处
                int idx = content.indexOf(oldText);
                newContent = content.substring(0, idx) + newText + content.substring(idx + oldText.length());
                replacements = 1;
            }

            // 写回文件
            Files.writeString(path, newContent, StandardCharsets.UTF_8);

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

    private int countOccurrences(String text, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    private String errorResult(String filePath, String message) {
        JSONObject result = new JSONObject();
        result.set("filePath", filePath);
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
