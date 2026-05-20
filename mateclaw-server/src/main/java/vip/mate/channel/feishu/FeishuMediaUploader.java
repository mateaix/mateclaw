package vip.mate.channel.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateFileReq;
import com.lark.oapi.service.im.v1.model.CreateFileReqBody;
import com.lark.oapi.service.im.v1.model.CreateFileResp;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import com.lark.oapi.service.im.v1.model.CreateImageResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.media.ImageCompressor;
import vip.mate.channel.media.MediaSizeDecision;
import vip.mate.channel.media.MediaSource;
import vip.mate.channel.media.MediaUploadException;
import vip.mate.channel.media.MediaUploadRequest;
import vip.mate.channel.media.MediaUploadResult;
import vip.mate.channel.media.MediaUploader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Feishu implementation of {@link MediaUploader}. Routes through the
 * {@code oapi-sdk} so {@code tenant_access_token}, multipart framing,
 * retries, and domain (feishu / lark) are all handled by the SDK —
 * no hand-rolled HTTP for any new send path.
 *
 * <p>Flow:
 * <ol>
 *   <li>Resolve the {@link MediaSource} into bytes (in-memory for
 *       URL/Path sources are normalised the same way the SDK needs).</li>
 *   <li>Consult {@link FeishuSizePolicy}. If the decision rejects,
 *       throw {@link MediaUploadException}. If it downgrades, switch
 *       to the file endpoint and carry the user-facing note on the
 *       result.</li>
 *   <li>If still {@code image} and oversized but under hard ceiling,
 *       run {@link ImageCompressor} so the original payload fits.</li>
 *   <li>Stage to a temp file (the SDK signatures take
 *       {@code java.io.File}, not streams), invoke the right SDK
 *       endpoint, then delete the temp file in {@code finally}.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuMediaUploader implements MediaUploader {

    /**
     * Cap on the bytes a {@link MediaSource.RemoteUrl} fetch may yield.
     * Same as Feishu's hard {@link FeishuSizePolicy#FILE_MAX_BYTES file
     * ceiling} — beyond this the size policy will reject anyway, so no
     * point downloading more.
     */
    private static final long REMOTE_FETCH_MAX_BYTES = FeishuSizePolicy.FILE_MAX_BYTES;

    /** Connection + per-request fetch timeout for {@link MediaSource.RemoteUrl}. */
    private static final Duration REMOTE_FETCH_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Extension → SDK {@code file_type} for the file endpoint. Anything
     * not on this list maps to {@code stream} (the SDK's catch-all).
     * Image extensions are intentionally absent — images go through
     * the image endpoint, not file.
     */
    private static final Map<String, String> EXT_TO_FILE_TYPE = Map.of(
            "pdf", "pdf",
            "doc", "doc",
            "docx", "doc",
            "xls", "xls",
            "xlsx", "xls",
            "ppt", "ppt",
            "pptx", "ppt",
            "mp4", "mp4",
            "opus", "opus"
    );

    private final FeishuClientFactory clientFactory;
    private final FeishuSizePolicy sizePolicy;

    /** Lazily-built HTTP client for {@link MediaSource.RemoteUrl} fetches. */
    private volatile HttpClient httpClient;

    @Override
    public String channelType() {
        return "feishu";
    }

    @Override
    public MediaUploadResult upload(MediaUploadRequest request) throws MediaUploadException {
        byte[] bytes = resolveBytes(request.source(), request.fileName());

        // ---- Apply size policy. May reject, downgrade, or pass.
        MediaSizeDecision decision = sizePolicy.evaluate(
                bytes.length, request.mediaType(), request.contentType());
        if (decision.rejected()) {
            throw new MediaUploadException(decision.rejectReason());
        }
        String effectiveType = decision.finalMediaType();

        // ---- For images, give the compressor a chance before upload
        if ("image".equals(effectiveType) && bytes.length > FeishuSizePolicy.IMAGE_MAX_BYTES / 2) {
            // Pre-emptive compression when the image is in the upper
            // half of the 10 MB window — keeps a margin against
            // transient size growth from JPEG re-encoding.
            bytes = ImageCompressor.compressIfNeeded(bytes, request.fileName(), FeishuSizePolicy.IMAGE_MAX_BYTES);
            if (bytes.length > FeishuSizePolicy.IMAGE_MAX_BYTES) {
                // Compression couldn't get under — fall through to the
                // file endpoint instead of failing outright.
                log.warn("[feishu-upload] {} image still {}KB after compression — downgrading to file",
                        request.fileName(), bytes.length / 1024);
                effectiveType = "file";
                decision = MediaSizeDecision.downgradeTo("file",
                        "图片压缩后仍超过 10MB，已转为文件形式发送");
            }
        }

        Path tempFile = null;
        try {
            tempFile = stageToTempFile(bytes, request.fileName());
            File asFile = tempFile.toFile();
            Client client = clientFactory.client(request.channelId());

            String mediaId = switch (effectiveType) {
                case "image" -> uploadImage(client, asFile);
                case "audio", "video", "file" -> uploadFile(
                        client, asFile, effectiveType, request.fileName(),
                        request.contentType(), request.durationMillis());
                default -> throw new MediaUploadException(
                        "Unsupported mediaType: " + effectiveType);
            };

            return new MediaUploadResult(mediaId, effectiveType, decision.downgradeNote());

        } catch (MediaUploadException e) {
            throw e;
        } catch (Exception e) {
            throw new MediaUploadException(
                    "Feishu upload failed for " + request.fileName() + ": " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignore) {
                    // tmpdir cleanup is best-effort
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // SDK endpoint calls
    // ------------------------------------------------------------------

    private String uploadImage(Client client, File file) throws Exception {
        CreateImageReq req = CreateImageReq.newBuilder()
                .createImageReqBody(CreateImageReqBody.newBuilder()
                        .imageType("message")
                        .image(file)
                        .build())
                .build();
        CreateImageResp resp = client.im().v1().image().create(req);
        if (!resp.success()) {
            throw new MediaUploadException(formatSdkError("im.image.create", resp.getCode(), resp.getMsg()));
        }
        return resp.getData().getImageKey();
    }

    private String uploadFile(Client client, File file, String effectiveType, String fileName,
                              String contentType, Integer durationMillis) throws Exception {
        String fileType = resolveFileType(effectiveType, fileName, contentType);
        CreateFileReqBody.Builder body = CreateFileReqBody.newBuilder()
                .fileType(fileType)
                .fileName(fileName)
                .file(file);
        if (durationMillis != null && durationMillis > 0) {
            body.duration(durationMillis);
        }
        CreateFileReq req = CreateFileReq.newBuilder()
                .createFileReqBody(body.build())
                .build();
        CreateFileResp resp = client.im().v1().file().create(req);
        if (!resp.success()) {
            throw new MediaUploadException(formatSdkError("im.file.create", resp.getCode(), resp.getMsg()));
        }
        return resp.getData().getFileKey();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Map (effectiveType, fileName, contentType) → SDK {@code file_type}
     * accepted by the {@code /im/v1/files} endpoint. The SDK enforces a
     * closed set; unknown values are rejected with code 230003.
     */
    private static String resolveFileType(String effectiveType, String fileName, String contentType) {
        if ("audio".equals(effectiveType)) {
            return "opus";
        }
        if ("video".equals(effectiveType)) {
            return "mp4";
        }
        // effectiveType == "file" — derive from extension, fall back to stream
        String ext = extensionOf(fileName);
        if (ext != null) {
            String mapped = EXT_TO_FILE_TYPE.get(ext);
            if (mapped != null) return mapped;
        }
        // Some content-types carry a hint (e.g. application/pdf)
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("pdf")) return "pdf";
            if (ct.contains("msword") || ct.contains("wordprocessing")) return "doc";
            if (ct.contains("excel") || ct.contains("spreadsheet")) return "xls";
            if (ct.contains("powerpoint") || ct.contains("presentation")) return "ppt";
        }
        return "stream";
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private byte[] resolveBytes(MediaSource source, String fileName) throws MediaUploadException {
        try {
            return switch (source) {
                case MediaSource.Bytes b -> b.data();
                case MediaSource.LocalPath p -> Files.readAllBytes(p.path());
                case MediaSource.RemoteUrl u -> fetchRemote(u.url());
            };
        } catch (MediaUploadException e) {
            throw e;
        } catch (IOException e) {
            throw new MediaUploadException(
                    "Failed to read media source for " + fileName + ": " + e.getMessage(), e);
        }
    }

    private byte[] fetchRemote(String url) throws MediaUploadException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REMOTE_FETCH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = httpClient()
                    .send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new MediaUploadException(
                        "Remote fetch " + url + " returned HTTP " + resp.statusCode());
            }
            try (InputStream is = resp.body()) {
                return readCapped(is, REMOTE_FETCH_MAX_BYTES, url);
            }
        } catch (MediaUploadException e) {
            throw e;
        } catch (Exception e) {
            throw new MediaUploadException(
                    "Failed to fetch remote media " + url + ": " + e.getMessage(), e);
        }
    }

    /**
     * Read at most {@code cap} bytes from {@code in}. Throws if the
     * stream still has more — we never load oversized remote payloads
     * into memory, since the size policy would reject them anyway.
     */
    private static byte[] readCapped(InputStream in, long cap, String urlForError) throws IOException, MediaUploadException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > cap) {
                throw new MediaUploadException(
                        "Remote media " + urlForError + " exceeds " + cap + " bytes — aborted partial read");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private HttpClient httpClient() {
        HttpClient c = this.httpClient;
        if (c != null) return c;
        synchronized (this) {
            c = this.httpClient;
            if (c == null) {
                c = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                this.httpClient = c;
            }
        }
        return c;
    }

    private static Path stageToTempFile(byte[] bytes, String fileName) throws IOException {
        // Preserve extension so the SDK and Feishu server can sniff
        // content type. Strip path separators from the suggested name.
        String safeName = fileName == null ? "upload" : fileName.replaceAll("[/\\\\]", "_");
        Path tmp = Files.createTempFile("feishu-upload-", "-" + safeName);
        Files.copy(new java.io.ByteArrayInputStream(bytes), tmp, StandardCopyOption.REPLACE_EXISTING);
        return tmp;
    }

    private static String formatSdkError(String op, int code, String msg) {
        return op + " failed (code=" + code + ", msg=" + msg + ")";
    }

    /** Reserved for future inference if request.contentType is null and source is a path. */
    @SuppressWarnings("unused")
    private static String sniffContentType(Path path) {
        try {
            String type = Files.probeContentType(path);
            if (type != null) return type;
        } catch (IOException ignore) {
            // fall through
        }
        return URLConnection.guessContentTypeFromName(path.getFileName().toString());
    }
}
