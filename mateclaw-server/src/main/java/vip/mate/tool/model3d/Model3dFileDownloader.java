package vip.mate.tool.model3d;

import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Downloads generated 3D-model assets (.glb / .obj / .fbx) from the provider's
 * CDN to local conversation storage. Mirrors {@link vip.mate.tool.video.VideoFileDownloader}
 * and {@link vip.mate.tool.image.ImageFileDownloader}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Model3dFileDownloader {

    private final ChatUploadLocationResolver uploadLocationResolver;

    public Path download(String modelUrl, String conversationId, String taskId,
                          String preferredExtension) throws IOException {
        Path dir = uploadLocationResolver.resolveUploadRoot(conversationId).resolve(conversationId);
        Files.createDirectories(dir);

        String ext = guessExtension(modelUrl, preferredExtension);
        String fileName = "model_" + taskId + ext;
        Path targetFile = dir.resolve(fileName);

        log.info("[Model3dDownloader] Downloading 3D model from {} to {}", modelUrl, targetFile);
        long size = HttpUtil.downloadFile(modelUrl, targetFile.toFile());
        log.info("[Model3dDownloader] Downloaded {} bytes to {}", size, targetFile);

        return targetFile;
    }

    public String toServingUrl(String conversationId, Path localPath) {
        return "/api/v1/chat/files/" + conversationId + "/" + localPath.getFileName().toString();
    }

    /**
     * Pick a filename extension that matches the actual bytes we'll download.
     * <p>
     * URL extension wins over {@code preferred} — Tencent Pro labels the OBJ
     * entry as Type="obj" but the {@code Url} actually points at a
     * {@code .zip} bundle (obj + mtl + textures). Saving such a file as
     * {@code .obj} would mislead anything downstream (browser, model-viewer)
     * into trying to parse zip bytes as OBJ text.
     */
    private String guessExtension(String url, String preferred) {
        String lower = url.toLowerCase().split("\\?")[0];
        if (lower.endsWith(".glb")) return ".glb";
        if (lower.endsWith(".obj")) return ".obj";
        if (lower.endsWith(".fbx")) return ".fbx";
        if (lower.endsWith(".usdz")) return ".usdz";
        if (lower.endsWith(".zip")) return ".zip";
        if (lower.endsWith(".gltf")) return ".gltf";
        // No URL hint — fall back to the provider-declared format.
        if (preferred != null && !preferred.isBlank()) {
            String p = preferred.toLowerCase();
            if (p.equals("glb") || p.equals("obj") || p.equals("fbx")
                    || p.equals("usdz") || p.equals("gltf")) {
                return "." + p;
            }
        }
        return ".glb";
    }
}
