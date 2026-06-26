package vip.mate.tool.video;

import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 视频文件下载器 — 从 provider CDN 下载视频到本地存储
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoFileDownloader {

    private final ChatUploadLocationResolver uploadLocationResolver;

    /**
     * 下载视频到本地
     *
     * @param videoUrl       provider 返回的视频 URL
     * @param conversationId 会话 ID
     * @param taskId         任务 ID
     * @return 本地文件路径
     */
    public Path download(String videoUrl, String conversationId, String taskId) throws IOException {
        Path dir = uploadLocationResolver.resolveUploadRoot(conversationId).resolve(conversationId);
        Files.createDirectories(dir);

        String extension = guessExtension(videoUrl);
        String fileName = "video_" + taskId + extension;
        Path targetFile = dir.resolve(fileName);

        log.info("[VideoDownloader] Downloading video from {} to {}", videoUrl, targetFile);
        long size = HttpUtil.downloadFile(videoUrl, targetFile.toFile());
        log.info("[VideoDownloader] Downloaded {} bytes to {}", size, targetFile);

        return targetFile;
    }

    /**
     * 构造文件的 API 访问 URL
     */
    public String toServingUrl(String conversationId, Path localPath) {
        return "/api/v1/chat/files/" + conversationId + "/" + localPath.getFileName().toString();
    }

    private String guessExtension(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".mp4")) return ".mp4";
        if (lower.contains(".webm")) return ".webm";
        if (lower.contains(".mov")) return ".mov";
        return ".mp4"; // 默认 mp4
    }
}
