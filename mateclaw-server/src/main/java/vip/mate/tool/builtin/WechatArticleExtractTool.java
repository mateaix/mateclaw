package vip.mate.tool.builtin;

import cn.hutool.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.tool.browser.UrlSafetyChecker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Built-in tool: fetch a WeChat Official Account article and return its cleaned,
 * structured body (title / author / publish time / markdown text / image URLs).
 *
 * <p>WeChat article pages ({@code mp.weixin.qq.com/s/...}) are largely static
 * HTML: the body lives in {@code #js_content} and images lazy-load through a
 * {@code data-src} attribute. A plain HTTP GET plus a jsoup cleanup is therefore
 * enough for the common case, which is far cheaper and more reliable than
 * driving a headless browser. Callers that need to summarise several reference
 * articles ("参考公众号信息抓取汇总") get clean text instead of a raw page
 * snapshot.
 *
 * <p>The URL is constrained to the {@code mp.weixin.qq.com} host and validated
 * through {@link UrlSafetyChecker} so the tool cannot be used for SSRF.
 */
@Slf4j
@Component
public class WechatArticleExtractTool {

    private static final String ALLOWED_HOST_SUFFIX = "mp.weixin.qq.com";
    private static final int FETCH_TIMEOUT_MS = 15_000;
    private static final int MAX_BODY_CHARS = 20_000;
    private static final int MAX_IMAGES = 30;
    private static final String USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.0";

    @Tool(name = "wechat_article_extract", description = """
        Fetch a WeChat Official Account (微信公众号) article by URL and return its
        cleaned content: title, author, publish time, body as Markdown, and the
        list of image URLs.

        Use this to gather and summarise reference 公众号 articles before writing
        (参考公众号信息抓取汇总). It returns clean readable text rather than a raw
        page snapshot, so it is preferred over browser_use for mp.weixin.qq.com
        article pages.

        Only https://mp.weixin.qq.com/... URLs are accepted. Content is meant for
        reference and summarisation — produce original, differentiated writing and
        cite the source; do not copy verbatim.
        """)
    public String wechat_article_extract(
            @ToolParam(description = "Full WeChat article URL, e.g. https://mp.weixin.qq.com/s/xxxxxxxx")
            String url) {

        if (url == null || url.isBlank()) {
            return "Error: url is required.";
        }
        String trimmed = url.trim();

        // SSRF guard + host allowlisting: only public mp.weixin.qq.com pages.
        try {
            UrlSafetyChecker.check(trimmed);
        } catch (SecurityException e) {
            return "Error: unsafe URL — " + e.getMessage();
        }
        String host = java.net.URI.create(trimmed).getHost();
        if (host == null || !(host.equals(ALLOWED_HOST_SUFFIX) || host.endsWith("." + ALLOWED_HOST_SUFFIX))) {
            return "Error: only mp.weixin.qq.com article URLs are supported (got host: " + host + ").";
        }

        String html;
        try {
            html = HttpRequest.get(trimmed)
                    .header("User-Agent", USER_AGENT)
                    .timeout(FETCH_TIMEOUT_MS)
                    .execute()
                    .body();
        } catch (Exception e) {
            log.warn("[WechatExtract] fetch failed for {}: {}", trimmed, e.getMessage());
            return "Error: failed to fetch the article — " + e.getMessage();
        }
        if (html == null || html.isBlank()) {
            return "Error: empty response from the article URL.";
        }

        Document doc = Jsoup.parse(html, trimmed);

        String title = firstNonBlank(
                text(doc, "#activity-name"),
                text(doc, "h1.rich_media_title"),
                text(doc, "meta[property=og:title]", "content"),
                doc.title());
        String author = firstNonBlank(
                text(doc, "#js_author_name"),
                text(doc, "#js_name"),
                text(doc, "a#js_name"),
                text(doc, "meta[name=author]", "content"));
        String publishTime = firstNonBlank(
                text(doc, "#publish_time"),
                text(doc, "em#publish_time"));

        Element content = doc.selectFirst("#js_content");
        if (content == null) {
            // The article may be intercepted (verification / deleted / anti-scrape).
            return "Error: could not locate the article body (#js_content). "
                    + "The page may require verification or has been removed. "
                    + "Try browser_use as a fallback.\nTitle: " + safe(title);
        }

        // Drop non-content noise before walking the tree.
        content.select("script, style, noscript").remove();

        List<String> imageUrls = collectImages(content);
        String bodyMarkdown = toMarkdown(content);
        if (bodyMarkdown.length() > MAX_BODY_CHARS) {
            bodyMarkdown = bodyMarkdown.substring(0, MAX_BODY_CHARS)
                    + "\n\n…（正文超长已截断 / body truncated）";
        }

        StringBuilder out = new StringBuilder();
        out.append("# ").append(safe(title)).append('\n');
        if (!author.isBlank()) {
            out.append("**作者/来源**：").append(author).append('\n');
        }
        if (!publishTime.isBlank()) {
            out.append("**发布时间**：").append(publishTime).append('\n');
        }
        out.append("**原文链接**：").append(trimmed).append("\n\n");
        out.append("---\n\n");
        out.append(bodyMarkdown.isBlank() ? "（未提取到正文文本）" : bodyMarkdown);
        if (!imageUrls.isEmpty()) {
            out.append("\n\n---\n**图片素材（").append(imageUrls.size()).append("）**：\n");
            for (String img : imageUrls) {
                out.append("- ").append(img).append('\n');
            }
        }
        log.info("[WechatExtract] extracted '{}' ({} chars, {} images) from {}",
                safe(title), bodyMarkdown.length(), imageUrls.size(), trimmed);
        return out.toString();
    }

    /** Walk the article body, emitting headings as ATX Markdown and keeping paragraph breaks. */
    private String toMarkdown(Element content) {
        StringBuilder sb = new StringBuilder();
        for (Element el : content.getAllElements()) {
            String tag = el.tagName();
            String own = el.ownText();
            if (own.isBlank()) {
                continue;
            }
            if (tag.length() == 2 && tag.charAt(0) == 'h'
                    && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
                int level = tag.charAt(1) - '0';
                sb.append('\n').append("#".repeat(level)).append(' ').append(own.trim()).append('\n');
            } else {
                sb.append(own.trim()).append('\n');
            }
        }
        // Collapse runs of blank lines.
        return sb.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    /** WeChat lazy-loads images via data-src; fall back to src. */
    private List<String> collectImages(Element content) {
        Set<String> urls = new LinkedHashSet<>();
        Elements imgs = content.select("img");
        for (Element img : imgs) {
            String src = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
            if (src != null && src.startsWith("http")) {
                urls.add(src);
            }
            if (urls.size() >= MAX_IMAGES) {
                break;
            }
        }
        return new ArrayList<>(urls);
    }

    private static String text(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? "" : el.text().trim();
    }

    private static String text(Document doc, String selector, String attr) {
        Element el = doc.selectFirst(selector);
        return el == null ? "" : el.attr(attr).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String safe(String v) {
        return v == null || v.isBlank() ? "(untitled)" : v.trim();
    }
}
