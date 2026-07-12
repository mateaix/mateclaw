package vip.mate.tool.builtin;

import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.content.service.ContentItemService;
import vip.mate.tool.browser.UrlSafetyChecker;
import vip.mate.tool.document.GeneratedFileCache;
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
 * Built-in tool: assemble a Xiaohongshu (小红书) image-text note into an
 * <b>image-first</b> online preview plus a downloadable material bundle — the
 * flagship delivery step for 小红书, mirroring what {@code gzh_package} does for
 * 公众号.
 *
 * <p>小红书 is an image-first medium: readers swipe a set of vertical (3:4)
 * cards, and the copy is supporting. This tool therefore renders a phone-style
 * preview where the images dominate (a horizontal swipe carousel up top) and the
 * title / body / topic tags sit beneath as support, and it <b>requires at least
 * {@value #MIN_IMAGES} images</b> (a cover plus content cards / photos) — packaging
 * fewer is refused so a note never ships text-heavy.
 *
 * <p>Image references are usually {@code render_html_image} / {@code image_generate}
 * outputs ({@code /api/v1/files/generated/{id}} links), resolved to bytes via
 * {@link GeneratedFileCache}; plain http(s) URLs and workspace file paths also
 * work. A reference that points at a file's logical name instead of its issued id
 * is self-healed by filename, the same way {@code gzh_package} resolves its cover.
 *
 * <p>Outputs: an online preview (served {@code text/html} behind a strict CSP),
 * plus a {@code .zip} of numbered card images + {@code 文案.txt}. Publishing stays
 * manual — 小红书 has no official publish API — so the result carries the same
 * creator-platform upload steps as {@code xhs_publish}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XhsPackageTool {

    /** 小红书 is image-first: a note must carry at least a cover plus two more images. */
    private static final int MIN_IMAGES = 3;
    /** Xiaohongshu allows up to 18 images per note. */
    private static final int MAX_IMAGES = 18;
    private static final String CREATOR_URL = "https://creator.xiaohongshu.com/publish/publish";

    // Palette — light, clean, 小红书-ish.
    private static final String INK = "#222222";
    private static final String MUTED = "#7a7a7a";
    private static final String TAG = "#13386c";
    private static final String CARD_BG = "#ffffff";
    private static final String PAGE_BG = "#f4f4f4";

    private final GeneratedFileCache cache;
    private final ContentItemService contentItemService;

    @Tool(name = "xhs_package", description = """
        Package a Xiaohongshu (小红书) note into an IMAGE-FIRST online preview plus a
        downloadable material bundle — the default delivery step of xhs_note.

        小红书 leads with images: pass the ordered card images (first = cover) and the
        copy plays a supporting role. REQUIRES at least 3 images (a cover + >=2 content
        cards / photos); fewer is refused, so generate enough with image_generate
        (aspectRatio=portrait) / render_html_image first.

        Params: title, body (with emoji + line breaks), tags (comma-separated), and
        images (comma-separated references in display order — render_html_image /
        image_generate URLs /api/v1/files/generated/{id}, http(s) image URLs, or
        workspace file paths).

        Returns: an 在线预览 link (a phone-style swipe preview: images up top, copy
        below), a 素材下载 .zip (numbered card images + 文案.txt), and the manual
        creator-platform upload steps. 小红书 has no publish API — never auto-uploads.
        """)
    public String xhs_package(
            @ToolParam(description = "Note title (小红书 标题, <=20 chars recommended)")
            String title,
            @ToolParam(description = "Note body text, with emoji and line breaks")
            String body,
            @ToolParam(description = "Topic tags, comma-separated, e.g. 咖啡,探店,周末去哪儿", required = false)
            String tags,
            @ToolParam(description = "Comma-separated image references in display order (first = cover); >=3 required")
            String images,
            @ToolParam(description = "Selected topic (for the content ledger; falls back to title)", required = false)
            String topic,
            @Nullable ToolContext ctx) {

        if (title == null || title.isBlank()) {
            return "Error: title is required.";
        }

        // Resolve images first — 小红书 is image-first, so this is the gate.
        List<ResolvedImg> imgs = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (images != null && !images.isBlank()) {
            for (String raw : images.split(",")) {
                String ref = raw.trim();
                if (ref.isEmpty()) {
                    continue;
                }
                if (imgs.size() >= MAX_IMAGES) {
                    skipped.add(ref + " (超过 " + MAX_IMAGES + " 张上限)");
                    continue;
                }
                try {
                    imgs.add(resolveImage(ref, ctx));
                } catch (Exception e) {
                    skipped.add(ref + " (" + e.getMessage() + ")");
                }
            }
        }
        if (imgs.size() < MIN_IMAGES) {
            StringBuilder err = new StringBuilder();
            err.append("⛔ 小红书以图为主，至少需要 ").append(MIN_IMAGES)
               .append(" 张图（1 封面 + ≥").append(MIN_IMAGES - 1)
               .append(" 张内容图/照片），当前只解析到 ").append(imgs.size()).append(" 张。\n");
            if (!skipped.isEmpty()) {
                err.append("未解析：").append(String.join("；", skipped)).append("\n");
            }
            err.append("请先用 image_generate(aspectRatio=portrait) 或 render_html_image 生成到 ≥")
               .append(MIN_IMAGES).append(" 张竖版图，再调用 xhs_package。");
            return err.toString();
        }

        // Online preview — image-first phone layout served inline (text/html + CSP).
        String previewDoc = buildPreview(title.trim(), body, tags, imgs);
        String previewUrl = store(previewDoc.getBytes(StandardCharsets.UTF_8), "小红书预览.html", "text/html", ctx);

        // Material bundle — 文案.txt + numbered card images.
        String copy = buildCopy(title, body, tags);
        String zipUrl;
        try {
            zipUrl = store(buildZip(copy, imgs), "小红书素材.zip", "application/zip", ctx);
        } catch (Exception e) {
            log.warn("[XhsPackage] zip build failed: {}", e.getMessage());
            zipUrl = null;
        }

        StringBuilder out = new StringBuilder();
        out.append("✅ 小红书笔记已打包完成（").append(imgs.size()).append(" 张图，以图为主）。\n\n");
        out.append("🔍 在线预览（手机版滑动预览，图在上、文案在下）：").append(previewUrl).append('\n');
        if (zipUrl != null) {
            out.append("📦 素材下载（按 01、02… 编号的卡片图 + 文案.txt）：").append(zipUrl).append('\n');
        }
        if (!skipped.isEmpty()) {
            out.append("⚠️ 未打包：").append(String.join("；", skipped)).append('\n');
        }
        // Auto compliance scan on delivery — never relies on the model calling it.
        ComplianceScanner.Result scan = ComplianceScanner.scan(title + "\n" + (body == null ? "" : body));
        if (!scan.clean()) {
            out.append(ComplianceScanner.report(scan)).append('\n');
        }
        // Auto-record into the content ledger.
        try {
            Long itemId = contentItemService.record(workspaceFromContext(ctx), "xhs",
                    topic != null && !topic.isBlank() ? topic : title.trim(),
                    title.trim(), "packaged", previewUrl, null);
            out.append("🗓️ 已记入内容日历（item id: ").append(itemId).append("）。\n");
        } catch (Exception e) {
            log.warn("[XhsPackage] auto-record failed: {}", e.getMessage());
        }
        out.append('\n').append(guideText());
        log.info("[XhsPackage] packaged '{}' ({} images, {} skipped, complianceHits={})",
                title, imgs.size(), skipped.size(), scan.hits().size());
        return out.toString();
    }

    /** Build the image-first phone-style preview: a swipe carousel of cards, copy beneath. */
    private String buildPreview(String title, @Nullable String body, @Nullable String tags, List<ResolvedImg> imgs) {
        StringBuilder cards = new StringBuilder();
        for (int i = 0; i < imgs.size(); i++) {
            cards.append("<div style=\"flex:0 0 100%;scroll-snap-align:center;aspect-ratio:3/4;"
                    + "background:#eee;border-radius:14px;overflow:hidden;\">"
                    + "<img src=\"").append(escapeAttr(imgs.get(i).url()))
                    .append("\" alt=\"card ").append(i + 1)
                    .append("\" style=\"width:100%;height:100%;object-fit:cover;display:block;\" /></div>");
        }
        String swipeHint = imgs.size() > 1
                ? "<p style=\"text-align:center;color:" + MUTED + ";font-size:13px;margin:8px 0 0;\">← 左右滑动查看 "
                  + imgs.size() + " 张图 →</p>"
                : "";
        String bodyHtml = (body != null && !body.isBlank())
                ? "<p style=\"font-size:15px;line-height:1.75;color:" + INK + ";margin:12px 0 0;white-space:pre-wrap;\">"
                  + nl2br(body.trim()) + "</p>"
                : "";
        StringBuilder tagHtml = new StringBuilder();
        if (tags != null && !tags.isBlank()) {
            tagHtml.append("<p style=\"margin:14px 0 0;line-height:2;\">");
            for (String t : tags.split(",")) {
                String tag = t.trim().replaceFirst("^#", "");
                if (!tag.isEmpty()) {
                    tagHtml.append("<span style=\"color:").append(TAG)
                           .append(";font-size:14px;margin-right:10px;\">#")
                           .append(escapeText(tag)).append("</span>");
                }
            }
            tagHtml.append("</p>");
        }

        String note =
                "<div style=\"max-width:390px;margin:0 auto;background:" + CARD_BG + ";border-radius:18px;"
                + "overflow:hidden;box-shadow:0 2px 16px rgba(0,0,0,0.08);\">"
                // Image carousel — the hero, image-first.
                + "<div style=\"display:flex;overflow-x:auto;scroll-snap-type:x mandatory;gap:8px;"
                + "padding:10px 10px 0;-webkit-overflow-scrolling:touch;\">" + cards + "</div>"
                + swipeHint
                // Copy — supporting, beneath the images.
                + "<div style=\"padding:6px 16px 20px;\">"
                + "<h1 style=\"font-size:18px;font-weight:700;line-height:1.5;color:" + INK + ";margin:12px 0 0;\">"
                + escapeText(title) + "</h1>"
                + bodyHtml + tagHtml
                + "</div></div>";

        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escapeText(title) + "</title></head>"
                + "<body style=\"margin:0;padding:20px 12px;background:" + PAGE_BG + ";"
                + "font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif;\">"
                + note + "</body></html>";
    }

    private String buildCopy(@Nullable String title, @Nullable String body, @Nullable String tags) {
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

    private byte[] buildZip(String copy, List<ResolvedImg> imgs) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("文案.txt"));
            zos.write(copy.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (int i = 0; i < imgs.size(); i++) {
                zos.putNextEntry(new ZipEntry(String.format("%02d.%s", i + 1, imgs.get(i).ext())));
                zos.write(imgs.get(i).bytes());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * Resolve an image reference to bytes + extension + a servable URL. Generated
     * ids resolve via the cache (self-healing a name-based reference by filename);
     * http(s) URLs are downloaded; workspace files are read and re-stored so the
     * preview has a servable URL to embed.
     */
    private ResolvedImg resolveImage(String ref, @Nullable ToolContext ctx) throws Exception {
        Matcher m = GeneratedFileCache.GENERATED_URL_PATTERN.matcher(ref);
        if (m.find()) {
            String id = m.group(1);
            Optional<GeneratedFileCache.Entry> entry = cache.get(id);
            if (entry.isEmpty() || entry.get().bytes() == null || entry.get().bytes().length == 0) {
                // Self-heal: the ref may point at the file's name, not its id.
                Optional<String> healed = cache.findIdByFilename(lastSegment(ref), "image/");
                if (healed.isPresent()) {
                    id = healed.get();
                    entry = cache.get(id);
                }
            }
            if (entry.isEmpty() || entry.get().bytes() == null || entry.get().bytes().length == 0) {
                throw new IllegalStateException("生成文件已过期或不存在");
            }
            return new ResolvedImg(entry.get().bytes(),
                    extFromMime(entry.get().mimeType(), "png"), cache.downloadUrl(id, ctx));
        }
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            UrlSafetyChecker.check(ref);
            byte[] bytes = HttpUtil.downloadBytes(ref);
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("下载为空");
            }
            return new ResolvedImg(bytes, extFromUrl(ref), ref);
        }
        // Workspace file path — read, then re-store so the preview can serve it.
        Path path = WorkspacePathGuard.validatePath(ref);
        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw new IllegalStateException("文件不存在");
        }
        byte[] bytes = Files.readAllBytes(path);
        String ext = extFromUrl(ref);
        String id = cache.put(bytes, path.getFileName().toString(), mimeFromExt(ext));
        return new ResolvedImg(bytes, ext, cache.downloadUrl(id, ctx));
    }

    private String store(byte[] bytes, String name, String mime, @Nullable ToolContext ctx) {
        return cache.downloadUrl(cache.put(bytes, name, mime), ctx);
    }

    private String guideText() {
        return """
            📮 小红书发布步骤（手动，小红书无官方发布 API）：
            1. 下载素材包并解压。
            2. 打开创作平台 %s （需已登录），点「上传图文」。
            3. 按 01、02… 顺序上传卡片图（首图即封面）。
            4. 从「文案.txt」复制标题、正文、话题标签，粘贴到对应输入框。
            5. 核对无违禁词后自行发布。""".formatted(CREATOR_URL);
    }

    private static Long workspaceFromContext(@Nullable ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        return origin != null && origin.workspaceId() != null ? origin.workspaceId() : 1L;
    }

    /** Last path segment of a reference, minus any {@code ?query} / {@code #fragment}. */
    private static String lastSegment(String ref) {
        String s = ref.split("[?#]")[0];
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
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

    private static String mimeFromExt(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "image/png";
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

    private static String nl2br(String s) {
        return escapeText(s).replace("\n", "<br/>");
    }

    private static String escapeText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escapeText(s).replace("\"", "&quot;");
    }

    private record ResolvedImg(byte[] bytes, String ext, String url) {}
}
