package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
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
import java.nio.file.StandardOpenOption;

/** Append-only file mutation with retry idempotency and an optional tail precondition. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppendFileTool {

    private final I18nService i18n;

    @ConcurrencyUnsafe("append file — must serialize with reads/writes on overlapping paths")
    @Tool(description = "Append a small content block to a file without rewriting existing content. "
            + "Creates the file and parent directories when absent. If the file already ends with the exact "
            + "content, the retry succeeds without writing it again. expectedTail can prevent appending to a "
            + "file that changed since it was read. Returns structured JSON.")
    public String append_file(
            @ToolParam(description = "Absolute or relative file path") String filePath,
            @ToolParam(description = "Content block to append; send only the new content") String content,
            @ToolParam(description = "Optional exact suffix that must currently end the file", required = false)
            String expectedTail,
            @Nullable ToolContext ctx) {

        if (filePath == null || filePath.isBlank()) {
            return error(filePath, "INVALID_ARGUMENT", i18n.msg("tool.write_file.error.path_empty"));
        }
        if (content == null || content.isEmpty()) {
            return error(filePath, "INVALID_ARGUMENT", "content must not be empty");
        }

        try {
            Path path = WorkspacePathGuard.validatePath(filePath, ctx);
            if (Files.isDirectory(path)) {
                return error(filePath, "IS_DIRECTORY", i18n.msg("tool.write_file.error.is_directory", path));
            }

            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);

            boolean existed = Files.exists(path);
            String current = existed ? Files.readString(path, StandardCharsets.UTF_8) : "";
            if (current.endsWith(content)) {
                JSONObject result = baseResult(filePath);
                result.set("bytesWritten", 0);
                result.set("created", false);
                result.set("alreadyApplied", true);
                result.set("message", "Content already present at file tail; no write needed");
                return JSONUtil.toJsonPrettyStr(result);
            }
            if (expectedTail != null && !current.endsWith(expectedTail)) {
                return error(filePath, "PRECONDITION_FAILED",
                        "File tail changed; re-read the file before appending");
            }

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            JSONObject result = baseResult(filePath);
            result.set("bytesWritten", bytes.length);
            result.set("created", !existed);
            result.set("alreadyApplied", false);
            result.set("message", "Appended: " + path + " (" + bytes.length + " bytes)");
            log.info("[AppendFile] Appended {} bytes to {}", bytes.length, path);
            return JSONUtil.toJsonPrettyStr(result);
        } catch (IllegalArgumentException e) {
            return error(filePath, "PATH_REJECTED", e.getMessage());
        } catch (Exception e) {
            log.error("[AppendFile] Failed to append file: {}", e.getMessage(), e);
            return error(filePath, "APPEND_FAILED",
                    i18n.msg("tool.write_file.error.write_exception", e.getMessage()));
        }
    }

    private JSONObject baseResult(String filePath) {
        JSONObject result = new JSONObject();
        result.set("filePath", filePath);
        return result;
    }

    private String error(String filePath, String code, String message) {
        JSONObject result = baseResult(filePath);
        result.set("error", true);
        result.set("code", code);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
