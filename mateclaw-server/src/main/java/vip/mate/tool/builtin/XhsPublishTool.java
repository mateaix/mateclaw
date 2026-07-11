package vip.mate.tool.builtin;

import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.tool.browser.UrlSafetyChecker;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.document.GeneratedFileLink;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Built-in tool: package a Xiaohongshu (小红书) image-text note into a single
 * downloadable bundle, and hand off to the creator platform for manual upload.
 *
 * <p>Xiaohongshu has no official open publishing API for personal notes, and
 * this tool deliberately does <b>not</b> automate uploads or bypass any risk
 * control / human verification. Instead it does the reliable, compliant part:
 * collects the copy + tags + rendered card images into one {@code .zip} the
 * user downloads in a single click, then points them at the creator platform
 * with step-by-step instructions to finish the post themselves.
 *
 * <p>Card images are usually produced by {@code render_html_image}, whose
 * results are {@code /api/v1/files/generated/{id}} links; those are resolved
 * back to bytes through {@link GeneratedFileCache}. Plain http(s) URLs and
 * workspace file paths are also accepted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XhsPublishTool {

    private static final String CREATOR_URL = "https://creator.xiaohongshu.com/publish/publish";
    private static final int MAX_IMAGES = 18; // Xiaohongshu allows up to 18 images per note.

    private final GeneratedFileCache cache;

    @Tool(name = "xhs_publish", description = """
        Package a Xiaohongshu (小红书) note into one downloadable bundle and give
        manual-publish instructions.

        Actions:
          - export (default): build a .zip containing 文案.txt (title + body + tags)
            and the card images in order, then return a download link plus steps to
            upload at the creator platform. `images` is a comma-separated list of
            card image references: render_html_image download URLs
            (/api/v1/files/generated/{id}), plain http(s) image URLs, or workspace
            file paths.
          - guide: just return the manual-publish steps and the creator URL.

        Xiaohongshu has no official publish API; this tool never auto-uploads or
        bypasses verification — the user completes the post manually.
        """)
    public String xhs_publish(
            @ToolParam(description = "Action: export (default) or guide", required = false)
            String action,
            @ToolParam(description = "Note title (小红书 标题, <=20 chars recommended)", required = false)
            String title,
            @ToolParam(description = "Note body text with emoji and line breaks", required = false)
            String body,
            @ToolParam(description = "Topic tags, comma-separated, e.g. 咖啡,探店,周末去哪儿", required = false)
            String tags,
            @ToolParam(description = "Comma-separated card image references (generated URLs / http URLs / workspace paths), in display order", required = false)
            String images,
            @Nullable ToolContext ctx) {

        String act = (action == null || action.isBlank()) ? "export" : action.trim().toLowerCase();
        if ("guide".equals(act)) {
            return guideText();
        }
        if (!"export".equals(act)) {
            return "Error: unknown action '" + act + "'. Use 'export' or 'guide'.";
        }

        String copy = buildCopy(title, body, tags);
        List<byte[]> imageBytes = new ArrayList<>();
        List<String> imageExts = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (images != null && !images.isBlank()) {
            String[] refs = images.split(",");
            for (String raw : refs) {
                String ref = raw.trim();
                if (ref.isEmpty()) {
                    continue;
                }
                if (imageBytes.size() >= MAX_IMAGES) {
                    skipped.add(ref + " (超过 " + MAX_IMAGES + " 张上限)");
                    continue;
                }
                try {
                    ResolvedImage img = resolveImage(ref);
                    imageBytes.add(img.bytes());
                    imageExts.add(img.ext());
                } catch (Exception e) {
                    skipped.add(ref + " (" + e.getMessage() + ")");
                }
            }
        }

        byte[] zip;
        try {
            zip = buildZip(copy, imageBytes, imageExts);
        } catch (Exception e) {
            log.warn("[XhsPublish] zip build failed: {}", e.getMessage());
            return "Error: failed to build the bundle — " + e.getMessage();
        }

        String linkMsg = GeneratedFileLink.resultZh(
                zip, "小红书发布包.zip", "application/zip", cache, "发布包", ctx);

        StringBuilder out = new StringBuilder();
        out.append(linkMsg).append("\n\n");
        out.append("📦 发布包含：文案.txt");
        if (!imageBytes.isEmpty()) {
            out.append(" + ").append(imageBytes.size()).append(" 张卡片图（已按顺序编号）");
        }
        out.append("。\n");
        if (!skipped.isEmpty()) {
            out.append("⚠️ 未打包：").append(String.join("；", skipped)).append("\n");
        }
        out.append('\n').append(guideText());
        log.info("[XhsPublish] exported bundle: {} images, {} skipped", imageBytes.size(), skipped.size());
        return out.toString();
    }

    private String buildCopy(String title, String body, String tags) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("【标题】\n").append(title.trim()).append("\n\n");
        }
        if (body != null && !body.isBlank()) {
            sb.append("【正文】\n").append(body.trim()).append("\n\n");
        }
        if (tags != null && !tags.isBlank()) {
            StringBuilder tagLine = new StringBuilder("【话题标签】\n");
            for (String t : tags.split(",")) {
                String tag = t.trim().replaceFirst("^#", "");
                if (!tag.isEmpty()) {
                    tagLine.append('#').append(tag).append(' ');
                }
            }
            sb.append(tagLine.toString().trim()).append('\n');
        }
        return sb.toString().strip();
    }

    private byte[] buildZip(String copy, List<byte[]> imageBytes, List<String> imageExts) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("文案.txt"));
            zos.write(copy.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (int i = 0; i < imageBytes.size(); i++) {
                String name = String.format("%02d.%s", i + 1, imageExts.get(i));
                zos.putNextEntry(new ZipEntry(name));
                zos.write(imageBytes.get(i));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** Resolve an image reference (generated URL / http URL / workspace path) to bytes + extension. */
    private ResolvedImage resolveImage(String ref) throws Exception {
        Matcher m = GeneratedFileCache.GENERATED_URL_PATTERN.matcher(ref);
        if (m.find()) {
            String id = m.group(1);
            Optional<GeneratedFileCache.Entry> entry = cache.get(id);
            if (entry.isEmpty()) {
                throw new IllegalStateException("生成文件已过期或不存在");
            }
            return new ResolvedImage(entry.get().bytes(), extFromMime(entry.get().mimeType(), "png"));
        }
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            UrlSafetyChecker.check(ref);
            byte[] bytes = HttpUtil.downloadBytes(ref);
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("下载为空");
            }
            return new ResolvedImage(bytes, extFromUrl(ref));
        }
        // Otherwise treat as a workspace-relative/absolute file path.
        Path path = WorkspacePathGuard.validatePath(ref);
        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw new IllegalStateException("文件不存在");
        }
        return new ResolvedImage(Files.readAllBytes(path), extFromUrl(ref));
    }

    private static String extFromMime(String mime, String fallback) {
        if (mime == null) {
            return fallback;
        }
        return switch (mime.toLowerCase()) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> fallback;
        };
    }

    private static String extFromUrl(String url) {
        String clean = url.split("[?#]")[0];
        int dot = clean.lastIndexOf('.');
        if (dot >= 0 && dot < clean.length() - 1) {
            String ext = clean.substring(dot + 1).toLowerCase();
            if (ext.length() <= 4 && ext.matches("[a-z0-9]+")) {
                return ext;
            }
        }
        return "png";
    }

    private String guideText() {
        return """
            📮 小红书发布步骤（手动，小红书无官方发布 API）：
            1. 下载上面的发布包并解压。
            2. 打开创作平台 %s （需已登录），点「上传图文」。
            3. 按 01、02… 顺序上传卡片图（首图即封面）。
            4. 从「文案.txt」复制标题、正文，粘贴到对应输入框。
            5. 添加话题标签，核对无违禁词后自行发布。""".formatted(CREATOR_URL);
    }

    private record ResolvedImage(byte[] bytes, String ext) {}
}
