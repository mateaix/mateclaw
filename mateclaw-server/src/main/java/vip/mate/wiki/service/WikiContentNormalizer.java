package vip.mate.wiki.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import vip.mate.wiki.dto.ImageRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC-051 PR-1c: source-type-specific text normalization.
 * <p>
 * Goal is structural cleanup before chunking — strip noise from HTML,
 * normalize line endings, trim duplicate whitespace — without touching the
 * actual semantic content. Heading and page-marker extraction lives in
 * {@link DocumentPreprocessService} because it needs the offsets of the
 * surviving text, not just the text itself.
 *
 * <p>Tika is intentionally not pulled in: existing {@code DocumentExtractTool}
 * already covers PDF/Office via pdftotext / pdfplumber / Java fallbacks.
 */
@Slf4j
@Component
public class WikiContentNormalizer {

    private static final int MAX_HTML_LEN = 8 * 1024 * 1024; // 8 MB safety cap

    /**
     * Normalize raw text by source type. Returns the input unchanged when the
     * source type is unknown so we never lose content silently.
     *
     * @param sourceType {@code WikiRawMaterialEntity.sourceType} value
     *                   (text / pdf / docx / xlsx / pptx / url / paste / markdown)
     * @param rawText    extracted text content
     * @return cleaned text, never {@code null}
     */
    public String normalize(String sourceType, String rawText) {
        if (rawText == null) return "";
        String type = sourceType == null ? "" : sourceType.toLowerCase();
        return switch (type) {
            // 'url' is raw fetched web HTML and still carries markup, so it needs
            // tag stripping here. 'html' file uploads are tag-stripped upstream by
            // DocumentExtractTool (jsoup) before reaching the normalizer — running
            // normalizeHtml again would re-collapse the recovered heading structure,
            // so they fall through to the plain-text branch.
            case "url" -> normalizeHtml(rawText);
            // PDF text from DocumentExtractTool may already contain "--- Page N ---"
            // markers; we keep them so the preprocessor can map char offsets to pages.
            case "pdf" -> collapseBlankLines(rawText);
            case "docx", "doc", "pptx", "ppt", "xlsx", "xls", "html", "htm" -> collapseBlankLines(rawText);
            case "markdown", "md", "text", "paste" -> collapseBlankLines(rawText);
            default -> collapseBlankLines(rawText);
        };
    }

    /**
     * Strip nav/footer/script/style/aside and ad-like classes from HTML, then
     * return readable text. Falls through to the raw input when the document
     * is too large to parse safely or jsoup throws.
     */
    private String normalizeHtml(String rawHtml) {
        if (rawHtml.length() > MAX_HTML_LEN) {
            log.warn("[WikiContentNormalizer] HTML payload exceeds {} bytes, skipping cleanup", MAX_HTML_LEN);
            return collapseBlankLines(rawHtml);
        }
        try {
            Document doc = Jsoup.parse(rawHtml);
            // Drop structural noise.
            doc.select("script, style, noscript, nav, header, footer, aside, form, iframe").remove();
            // Drop common ad / share / cookie banners by class hint.
            Elements adNodes = doc.select(
                    "[class*=ad-], [class*=ads], [class^=ad_], [id*=ads], " +
                    "[class*=cookie-banner], [class*=share-], [class*=related-posts]");
            adNodes.remove();
            // Strip aria-hidden / display:none nodes — these are usually skip links / overlays.
            for (Element hidden : doc.select("[aria-hidden=true], [hidden]")) hidden.remove();

            // Convert to text. Jsoup .text() collapses whitespace; we want headings on
            // their own lines so the preprocessor can detect them. Walk children manually
            // to preserve heading boundaries.
            StringBuilder sb = new StringBuilder(Math.min(rawHtml.length(), 256 * 1024));
            for (Element el : doc.body() != null ? doc.body().getAllElements() : doc.getAllElements()) {
                String tag = el.tagName();
                String text = el.ownText();
                if (text.isBlank()) continue;
                if (tag.matches("h[1-6]")) {
                    int level = Integer.parseInt(tag.substring(1));
                    sb.append('\n').append("#".repeat(level)).append(' ').append(text.trim()).append('\n');
                } else {
                    sb.append(text.trim()).append('\n');
                }
            }
            String out = sb.toString();
            return out.isBlank() ? collapseBlankLines(rawHtml) : collapseBlankLines(out);
        } catch (Exception e) {
            log.warn("[WikiContentNormalizer] HTML parse failed, falling back to raw text: {}", e.getMessage());
            return collapseBlankLines(rawHtml);
        }
    }

    /**
     * Collapse 3+ consecutive blank lines down to 2, normalize CRLF to LF.
     * Cheap, lossless cleanup that helps chunk boundary detection.
     */
    private String collapseBlankLines(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        return unified.replaceAll("\n{3,}", "\n\n");
    }

    /** Markdown {@code ![alt](url)} pattern. {@code url} captures up to the first
     *  whitespace or closing paren so titles like {@code ![alt](url "title")} stop
     *  at the URL boundary; the title segment is intentionally discarded. */
    private static final Pattern MD_IMAGE_REF = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)\\)");

    /**
     * Extracts every {@code ![alt](url)} occurrence in the input as an
     * {@link ImageRef}. URLs are deduplicated — the first occurrence wins
     * so the alt text closest to the document start is preserved when the
     * same image appears multiple times.
     *
     * @param markdown raw markdown body; null/blank input returns an empty list
     * @return ordered, URL-deduplicated list of references, never null
     */
    public List<ImageRef> extractImageRefs(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<ImageRef> refs = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Matcher m = MD_IMAGE_REF.matcher(markdown);
        while (m.find()) {
            String url = m.group(2);
            if (url == null || url.isBlank()) continue;
            if (!seenUrls.add(url)) continue;
            refs.add(new ImageRef(m.group(0), m.group(1), url));
        }
        return refs;
    }
}
