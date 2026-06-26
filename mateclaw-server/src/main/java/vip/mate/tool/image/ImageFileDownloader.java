package vip.mate.tool.image;

import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 图片文件下载器 — 从 provider CDN 下载图片到本地存储，或解码 Base64 图片
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageFileDownloader {

    private final ChatUploadLocationResolver uploadLocationResolver;

    /**
     * Persist an image referenced by either a {@code data:} URL (inline base64 /
     * percent-encoded payload) or an http(s) CDN URL. Returns the local path
     * the caller can convert to a serving URL via {@link #toServingUrl}.
     *
     * <p>Without the {@code data:} branch, callers ended up handing the raw URL
     * to {@code HttpUtil.downloadFile}, which interprets it relative to the
     * working directory and produces nonsense like
     * {@code file:/cwd/http:/data:image/...} before failing — the image never
     * lands on disk and the assistant message renders empty.
     */
    public Path download(String imageUrl, String conversationId, String taskId, int index) throws IOException {
        if (imageUrl == null) {
            throw new IOException("imageUrl is null");
        }
        Path dir = uploadLocationResolver.resolveUploadRoot(conversationId).resolve(conversationId);
        Files.createDirectories(dir);

        if (imageUrl.startsWith("data:")) {
            return saveDataUrl(imageUrl, dir, taskId, index);
        }

        String extension = guessExtension(imageUrl);
        String fileName = "image_" + taskId + "_" + index + extension;
        Path targetFile = dir.resolve(fileName);

        log.info("[ImageDownloader] Downloading image from {} to {}", imageUrl, targetFile);
        long size = HttpUtil.downloadFile(imageUrl, targetFile.toFile());
        log.info("[ImageDownloader] Downloaded {} bytes to {}", size, targetFile);

        return targetFile;
    }

    /**
     * Decode a {@code data:[<mediatype>][;base64],<data>} URL onto disk.
     * Handles both the base64-encoded and percent-encoded body forms defined
     * by RFC 2397, and picks the file extension from the media type so the
     * stored asset is openable by name.
     */
    private Path saveDataUrl(String dataUrl, Path dir, String taskId, int index) throws IOException {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new IOException("Malformed data URL: missing comma");
        }
        // Header layout: "data:<mediatype>?(;base64)?"  (we already saw "data:")
        String header = dataUrl.substring("data:".length(), comma);
        String body = dataUrl.substring(comma + 1);

        boolean isBase64 = header.toLowerCase().contains(";base64");
        String mime = isBase64
                ? header.substring(0, header.toLowerCase().indexOf(";base64"))
                : (header.indexOf(';') >= 0 ? header.substring(0, header.indexOf(';')) : header);

        byte[] bytes;
        try {
            bytes = isBase64
                    ? Base64.getDecoder().decode(body)
                    : URLDecoder.decode(body, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid base64 payload in data URL: " + e.getMessage(), e);
        }

        String extension = extensionForMime(mime);
        String fileName = "image_" + taskId + "_" + index + extension;
        Path targetFile = dir.resolve(fileName);
        Files.write(targetFile, bytes);
        log.info("[ImageDownloader] Saved data URL ({} bytes, mime={}) to {}",
                bytes.length, mime.isBlank() ? "image/png" : mime, targetFile);
        return targetFile;
    }

    private static String extensionForMime(String mime) {
        if (mime == null) return ".png";
        return switch (mime.toLowerCase().trim()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            case "image/svg+xml" -> ".svg";
            default -> ".png";
        };
    }

    /**
     * 将 Base64 编码的图片保存到本地
     */
    public Path saveBase64(String base64Data, String conversationId, String taskId, int index) throws IOException {
        Path dir = uploadLocationResolver.resolveUploadRoot(conversationId).resolve(conversationId);
        Files.createDirectories(dir);

        String fileName = "image_" + taskId + "_" + index + ".png";
        Path targetFile = dir.resolve(fileName);

        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
        Files.write(targetFile, imageBytes);
        log.info("[ImageDownloader] Saved base64 image ({} bytes) to {}", imageBytes.length, targetFile);

        return targetFile;
    }

    /**
     * 构造文件的 API 访问 URL
     */
    public String toServingUrl(String conversationId, Path localPath) {
        return "/api/v1/chat/files/" + conversationId + "/" + localPath.getFileName().toString();
    }

    private String guessExtension(String url) {
        String lower = url.toLowerCase().split("\\?")[0];
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".gif")) return ".gif";
        return ".png";
    }
}
