package vip.mate.tool.builtin;

import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.tool.browser.UrlSafetyChecker;
import vip.mate.tool.document.GeneratedFileCache;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Built-in tool: assemble a finished WeChat Official Account (公众号) image-text
 * article from compact Markdown and deliver it as an online preview plus a
 * downloadable material bundle.
 *
 * <p>Why Markdown in, HTML out: emitting a full inline-styled article HTML as a
 * single tool-call argument is fragile — on streaming providers the large,
 * escape-heavy string can be truncated during argument aggregation, yielding
 * invalid JSON that gets dropped and sending the agent into a retry loop. The
 * caller therefore passes the body as Markdown (compact, few escapes); this tool
 * converts it to WeChat-editor-compatible <b>inline-styled</b> HTML server-side
 * (公众号 ignores {@code <style>} blocks and classes), so styling never rides on
 * the model's token stream.
 *
 * <p>Outputs, Manus-style:
 * <ul>
 *   <li><b>Online preview</b> — the rendered HTML is stored as {@code text/html}
 *       and served inline (behind a strict CSP), so the link opens the finished
 *       article in the browser.</li>
 *   <li><b>Material bundle</b> — a {@code .zip} with {@code article.html},
 *       {@code article.md} and the cover image for one-click download.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GzhPackageTool {

    // Palette mirrors references/gzh_layout.html so packaged articles match the skill's template.
    private static final String INK = "#1a1a1a";
    private static final String MUTED = "#6b6b6b";
    private static final String FAINT = "#9a9a9a";
    private static final String ACCENT = "#2f6fed";
    private static final String HAIRLINE = "#ececec";
    private static final String CODE_BG = "#f6f8fa";

    private final GeneratedFileCache cache;

    @Tool(name = "gzh_package", description = """
        Package a finished WeChat Official Account (公众号) article and return an
        online preview link plus a downloadable material bundle.

        Pass the article body as **Markdown** (headings, paragraphs, lists, quotes,
        fenced code, tables). Do NOT hand-write a big inline-styled HTML string —
        this tool builds the 公众号-compatible inline-styled HTML server-side, which
        avoids the tool-argument truncation that large HTML blobs cause.

        Returns:
          - 在线预览 link (opens the rendered article in the browser),
          - 素材下载 .zip (article.html + article.md + cover image),
          - the inline-styled HTML to paste into the 公众号 editor.

        Use this as the delivery step of gzh_article instead of write_file +
        render_html_image on a hand-built HTML file.
        """)
    public String gzh_package(
            @ToolParam(description = "Article title")
            String title,
            @ToolParam(description = "Article body in Markdown")
            String markdown,
            @ToolParam(description = "Cover image reference: a render_html_image / image URL (/api/v1/files/generated/<id>), http(s) URL, or omit", required = false)
            String coverImageUrl,
            @ToolParam(description = "Author / source name", required = false)
            String author,
            @Nullable ToolContext ctx) {

        if (title == null || title.isBlank()) {
            return "Error: title is required.";
        }
        if (markdown == null || markdown.isBlank()) {
            return "Error: markdown body is required.";
        }

        // 1. Markdown -> HTML fragment, then inline every style (公众号 drops <style>/class).
        String innerHtml = markdownToInlineHtml(markdown);
        String coverTag = (coverImageUrl != null && !coverImageUrl.isBlank())
                ? "<img src=\"" + escapeAttr(coverImageUrl.trim()) + "\" alt=\"cover\" "
                  + "style=\"width:100%;border-radius:8px;margin:0 0 20px;display:block;\" />"
                : "";
        String meta = (author != null && !author.isBlank())
                ? "<p style=\"color:" + FAINT + ";font-size:14px;margin:0 0 20px;\">" + escapeText(author.trim()) + "</p>"
                : "";
        String container =
                "<div style=\"max-width:677px;margin:0 auto;padding:0 4px;"
                + "font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "font-size:16px;line-height:1.8;color:" + INK + ";word-break:break-word;\">"
                + "<h1 style=\"font-size:22px;font-weight:700;line-height:1.4;margin:0 0 12px;color:" + INK + ";\">"
                + escapeText(title.trim()) + "</h1>"
                + meta + coverTag + innerHtml
                + "<p style=\"margin:28px 0 0;padding-top:16px;border-top:1px solid " + HAIRLINE + ";"
                + "color:" + MUTED + ";font-size:14px;\">如果这篇对你有帮助，欢迎点赞、在看、转发，也欢迎关注。</p>"
                + "</div>";

        // 2. Online preview — a full HTML doc served inline (text/html + CSP).
        String previewDoc = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escapeText(title.trim()) + "</title></head>"
                + "<body style=\"margin:0;padding:20px 12px;background:#fff;\">" + container + "</body></html>";
        String previewUrl = store(previewDoc.getBytes(StandardCharsets.UTF_8), "公众号预览.html", "text/html", ctx);

        // 3. Material bundle — zip {article.html, article.md, cover.png?}.
        String zipUrl;
        String coverNote;
        try {
            byte[] coverBytes = resolveCover(coverImageUrl);
            // For the offline bundle, point the cover at the local file.
            String bundleContainer = coverBytes != null
                    ? container.replaceFirst("<img src=\"[^\"]*\"", "<img src=\"cover.png\"")
                    : container;
            String bundleHtml = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                    + "<title>" + escapeText(title.trim()) + "</title></head>"
                    + "<body style=\"margin:0;padding:20px 12px;background:#fff;\">" + bundleContainer + "</body></html>";
            byte[] zip = buildZip(bundleHtml, markdown, coverBytes);
            zipUrl = store(zip, "公众号素材.zip", "application/zip", ctx);
            coverNote = coverBytes != null ? "含封面图" : "未附封面（未提供或无法下载）";
        } catch (Exception e) {
            log.warn("[GzhPackage] bundle build failed: {}", e.getMessage());
            zipUrl = null;
            coverNote = "打包失败：" + e.getMessage();
        }

        StringBuilder out = new StringBuilder();
        out.append("✅ 公众号图文已打包完成。\n\n");
        out.append("🔍 在线预览（浏览器打开即渲染）：").append(previewUrl).append('\n');
        if (zipUrl != null) {
            out.append("📦 素材下载（article.html + article.md + 封面，").append(coverNote).append("）：")
               .append(zipUrl).append('\n');
        }
        out.append("\n可将下面的内联样式 HTML 直接粘贴进公众号编辑器（如需直接进草稿箱，用 gzh_publish）：\n");
        out.append("```html\n").append(container).append("\n```");
        log.info("[GzhPackage] packaged '{}' ({} md chars, cover={})", title, markdown.length(), coverImageUrl != null);
        return out.toString();
    }

    /** Convert Markdown to an inline-styled HTML fragment (no &lt;style&gt;/class). */
    private String markdownToInlineHtml(String markdown) {
        List<org.commonmark.Extension> ext = List.of(TablesExtension.create(), StrikethroughExtension.create());
        Parser parser = Parser.builder().extensions(ext).build();
        HtmlRenderer renderer = HtmlRenderer.builder().extensions(ext).build();
        String rawHtml = renderer.render(parser.parse(markdown));

        Document doc = Jsoup.parseBodyFragment(rawHtml);
        for (Element el : doc.body().getAllElements()) {
            switch (el.tagName()) {
                case "h1", "h2" -> el.attr("style", "font-size:19px;font-weight:700;line-height:1.5;margin:28px 0 12px;color:" + INK + ";");
                case "h3" -> el.attr("style", "font-size:17px;font-weight:600;margin:22px 0 10px;color:" + INK + ";");
                case "h4", "h5", "h6" -> el.attr("style", "font-size:16px;font-weight:600;margin:18px 0 8px;color:" + INK + ";");
                case "p" -> el.attr("style", "margin:0 0 18px;color:" + INK + ";");
                case "a" -> el.attr("style", "color:" + ACCENT + ";text-decoration:none;");
                case "strong", "b" -> el.attr("style", "font-weight:700;color:" + INK + ";");
                case "em", "i" -> el.attr("style", "font-style:italic;");
                case "ul", "ol" -> el.attr("style", "margin:0 0 18px;padding-left:22px;");
                case "li" -> el.attr("style", "margin:0 0 8px;");
                case "blockquote" -> el.attr("style", "margin:0 0 18px;padding:10px 16px;border-left:3px solid " + ACCENT
                        + ";background:#f5f7ff;color:" + MUTED + ";");
                case "pre" -> el.attr("style", "margin:0 0 18px;padding:14px 16px;background:" + CODE_BG
                        + ";border-radius:8px;overflow-x:auto;font-size:14px;line-height:1.6;"
                        + "font-family:Consolas,Menlo,Monaco,monospace;color:#24292f;white-space:pre;");
                case "code" -> {
                    // Inline code only; code inside <pre> inherits the block style.
                    if (el.parent() == null || !"pre".equals(el.parent().tagName())) {
                        el.attr("style", "background:" + CODE_BG + ";border-radius:4px;padding:1px 5px;"
                                + "font-family:Consolas,Menlo,Monaco,monospace;font-size:14px;color:#d6336c;");
                    }
                }
                case "img" -> el.attr("style", "max-width:100%;border-radius:8px;display:block;margin:8px 0;");
                case "hr" -> el.attr("style", "border:none;border-top:1px solid " + HAIRLINE + ";margin:24px 0;");
                case "table" -> el.attr("style", "border-collapse:collapse;width:100%;margin:0 0 18px;font-size:14px;");
                case "th" -> el.attr("style", "border:1px solid " + HAIRLINE + ";padding:8px 10px;background:" + CODE_BG
                        + ";text-align:left;font-weight:600;");
                case "td" -> el.attr("style", "border:1px solid " + HAIRLINE + ";padding:8px 10px;");
                default -> { /* leave other elements unstyled */ }
            }
        }
        return doc.body().html();
    }

    private byte[] buildZip(String html, String markdown, @Nullable byte[] cover) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("article.html"));
            zos.write(html.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("article.md"));
            zos.write(markdown.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            if (cover != null) {
                zos.putNextEntry(new ZipEntry("cover.png"));
                zos.write(cover);
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** Resolve the cover reference to bytes: generated-file id, http(s) URL, else null. */
    @Nullable
    private byte[] resolveCover(@Nullable String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String r = ref.trim();
        try {
            Matcher m = GeneratedFileCache.GENERATED_URL_PATTERN.matcher(r);
            if (m.find()) {
                Optional<GeneratedFileCache.Entry> e = cache.get(m.group(1));
                return e.map(GeneratedFileCache.Entry::bytes).orElse(null);
            }
            if (r.startsWith("http://") || r.startsWith("https://")) {
                UrlSafetyChecker.check(r);
                byte[] b = HttpUtil.downloadBytes(r);
                return (b != null && b.length > 0) ? b : null;
            }
        } catch (Exception e) {
            log.warn("[GzhPackage] cover resolve failed: {}", e.getMessage());
        }
        return null;
    }

    private String store(byte[] bytes, String name, String mime, @Nullable ToolContext ctx) {
        String id = cache.put(bytes, name, mime);
        return cache.downloadUrl(id, ctx);
    }

    private static String escapeText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escapeText(s).replace("\"", "&quot;");
    }
}
