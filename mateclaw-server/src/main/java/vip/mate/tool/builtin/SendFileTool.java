package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 内置工具：将服务器上的现有文件发送给用户
 * <p>
 * 读取指定路径的文件，放入 {@link GeneratedFileCache} 生成临时下载链接，
 * 由渠道适配器（飞书/钉钉/Telegram 等）自动检测并作为原生附件发送。
 * <p>
 * 与 {@link ReadFileTool} 不同，此工具处理任意文件类型（包括二进制文件），
 * 目标是将文件作为附件发送而非读取其文本内容。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class SendFileTool {

    private final GeneratedFileCache cache;
    private final vip.mate.i18n.I18nService i18n;

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB

    private static final Map<String, String> EXTENSION_MIME = Map.ofEntries(
            Map.entry(".pdf", "application/pdf"),
            Map.entry(".doc", "application/msword"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".xls", "application/vnd.ms-excel"),
            Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(".ppt", "application/vnd.ms-powerpoint"),
            Map.entry(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry(".txt", "text/plain"),
            Map.entry(".csv", "text/csv"),
            Map.entry(".json", "application/json"),
            Map.entry(".xml", "application/xml"),
            Map.entry(".html", "text/html"),
            Map.entry(".htm", "text/html"),
            Map.entry(".md", "text/markdown"),
            Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".gif", "image/gif"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".mp3", "audio/mpeg"),
            Map.entry(".wav", "audio/wav"),
            Map.entry(".ogg", "audio/ogg"),
            Map.entry(".mp4", "video/mp4"),
            Map.entry(".avi", "video/x-msvideo"),
            Map.entry(".mov", "video/quicktime"),
            Map.entry(".zip", "application/zip"),
            Map.entry(".tar", "application/x-tar"),
            Map.entry(".gz", "application/gzip"),
            Map.entry(".yaml", "text/yaml"),
            Map.entry(".yml", "text/yaml"),
            Map.entry(".log", "text/plain")
    );

    @Tool(description = """
            Send an existing file from the server to the user as an attachment. \
            The file is uploaded to the IM channel as a native attachment (not a text link). \
            Works for any file type: documents (PDF, DOCX, XLSX, PPTX), images, \
            audio, video, archives, etc. Use this instead of read_file when you \
            need to send a binary file to the user.""")
    public String send_file(
            @ToolParam(description = "Absolute or relative file path on the server") String filePath,
            @ToolParam(description = "Display name for the file (e.g. 'report.pdf'). Omit to use the original filename", required = false) String fileName,
            @Nullable ToolContext ctx) {

        JSONObject result = new JSONObject();
        result.set("filePath", filePath);

        try {
            Path path;
            try {
                path = WorkspacePathGuard.validatePath(filePath, ctx);
            } catch (IllegalArgumentException e) {
                // Sandbox rejected the literal path. Try chat-upload fallback.
                Path attachment = ChatUploadResolver.resolve(filePath);
                if (attachment == null) {
                    return errorResult(filePath, e.getMessage());
                }
                path = attachment;
            }

            if (!Files.exists(path)) {
                Path attachment = ChatUploadResolver.resolve(filePath);
                if (attachment == null) {
                    return errorResult(filePath, i18n.msg("tool.read_file.error.not_found", path));
                }
                path = attachment;
            }
            if (Files.isDirectory(path)) {
                return errorResult(filePath, i18n.msg("tool.read_file.error.is_directory", path));
            }
            if (!Files.isReadable(path)) {
                return errorResult(filePath, i18n.msg("tool.read_file.error.not_readable", path));
            }

            long fileSize = Files.size(path);
            if (fileSize > MAX_FILE_SIZE) {
                return errorResult(filePath, i18n.msg("tool.send_file.error.too_large",
                        fileSize / 1024 / 1024, MAX_FILE_SIZE / 1024 / 1024));
            }

            byte[] bytes = Files.readAllBytes(path);
            String displayName = (fileName != null && !fileName.isBlank()) ? fileName : path.getFileName().toString();
            String mimeType = resolveMimeType(displayName);

            String url = stash(bytes, displayName, mimeType);
            result.set("status", "sent");
            result.set("fileName", displayName);
            result.set("fileSize", fileSize);
            result.set("url", url);

            log.info("[SendFile] Sending {} ({}, {} bytes) via generated file cache",
                    displayName, mimeType, fileSize);

            return JSONUtil.toJsonPrettyStr(result);

        } catch (Exception e) {
            log.error("[SendFile] Failed to send file: {}", e.getMessage(), e);
            return errorResult(filePath, i18n.msg("tool.send_file.error.failed", e.getMessage()));
        }
    }

    private String stash(byte[] bytes, String displayName, String mimeType) {
        String id = cache.put(bytes, displayName, mimeType);
        return "/api/v1/files/generated/" + id;
    }

    private String resolveMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        for (Map.Entry<String, String> entry : EXTENSION_MIME.entrySet()) {
            if (lower.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "application/octet-stream";
    }

    private String errorResult(String filePath, String message) {
        JSONObject result = new JSONObject();
        result.set("filePath", filePath);
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
