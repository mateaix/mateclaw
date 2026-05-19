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
            // Web and HTML sources can still carry raw markup, so they go through
            // normalizeHtml, which strips script/style/nav/footer noise. normalizeHtml
            // also recognizes text that was already tag-stripped upstream (an extracted
            // .html/.htm upload re-entering the normalizer) and leaves that text's line
            // structure intact instead of re-collapsing its recovered headings.
            case "url", "html", "htm" -> normalizeHtml(rawText);
            // PDF text from DocumentExtractTool may already contain "--- Page N ---"
            // markers; we keep them so the preprocessor can map char offsets to pages.
            case "pdf" -> collapseBlankLines(rawText);
            case "docx", "doc", "pptx", "ppt", "xlsx", "xls" -> collapseBlankLines(rawText);
            case "markdown", "md", "text", "paste" -> collapseBlankLines(rawText);
            default -> collapseBlankLines(rawText);
        };
    }

    /**
     * Strip nav/footer/script/style/aside and ad-like classes from HTML, then
     * return readable text.
     * <p>
     * The output never carries markup: input that still contains element
     * structure is parsed and stripped; input with no element structure is
     * reduced to its text content. When the document cannot be parsed (too
     * large, or jsoup throws) it is run through {@link #stripMarkupLossy(String)} so that
     * script/style bodies and tags are dropped without a full parse — the raw
     * markup is never passed through verbatim.
     */
    private String normalizeHtml(String rawHtml) {
        if (rawHtml.length() > MAX_HTML_LEN) {
            log.warn("[WikiContentNormalizer] HTML payload exceeds {} bytes, stripping markup without a full parse", MAX_HTML_LEN);
            return stripMarkupLossy(rawHtml);
        }
        try {
            Document doc = Jsoup.parse(rawHtml);

            // Distinguish genuine HTML markup from text that was already tag-stripped
            // upstream (an extracted .html/.htm upload re-entering the normalizer).
            // jsoup synthesizes an <html>/<head>/<body> skeleton even for plain text,
            // so the absence of any element children means there is no real markup.
            // Plain text must skip the element walker below: that walker relies on
            // Element.ownText(), which collapses newlines and would merge a heading
            // line into the paragraph that follows it.
            Element body = doc.body();
            Element head = doc.head();
            boolean hasMarkup = (body != null && !body.children().isEmpty())
                    || (head != null && !head.children().isEmpty());
            if (!hasMarkup) {
                // No element structure — text that was already tag-stripped upstream
                // (an extracted .html/.htm upload). Return its whole text rather than
                // the raw string: wholeText() keeps the original line breaks (so ATX
                // headings stay on their own lines) but carries no tags, no attributes
                // and no comment nodes. The raw string must never be returned here —
                // a bare <html>/<body> skeleton can still hold event-handler
                // attributes that would otherwise leak through verbatim.
                Element textRoot = body != null ? body : doc;
                return collapseBlankLines(textRoot.wholeText());
            }

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
            if (!out.isBlank()) {
                return collapseBlankLines(out);
            }
            // The walker produced nothing — the document was pure script/style/nav
            // noise. Fall back to jsoup's plain-text extraction, never the raw markup:
            // returning rawHtml here would carry <script>/<style> straight through into
            // stored wiki content. Element.text() reads TextNodes only, and the noise
            // elements were already removed above, so the result is markup-free.
            String plain = doc.text();
            return plain.isBlank() ? "" : collapseBlankLines(plain);
        } catch (Exception e) {
            log.warn("[WikiContentNormalizer] HTML parse failed, stripping markup without a full parse: {}", e.getMessage());
            return stripMarkupLossy(rawHtml);
        }
    }

    /** Matches a {@code <script>}/{@code <style>} block including its text body. */
    private static final Pattern SCRIPT_STYLE_BLOCK =
            Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    /** Matches any remaining HTML/XML tag. */
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]*>");

    /**
     * Last-resort sanitizer for HTML that could not be parsed with the element
     * walker (oversized payloads, or a parser failure). Drops the text body of
     * {@code <script>}/{@code <style>} blocks, then removes every remaining tag.
     * Lossy by design — document structure is gone — but the result carries no
     * markup, so it is safe to store and render downstream.
     */
    private String stripMarkupLossy(String rawHtml) {
        String noScript = SCRIPT_STYLE_BLOCK.matcher(rawHtml).replaceAll(" ");
        String noTags = ANY_TAG.matcher(noScript).replaceAll(" ");
        return collapseBlankLines(noTags);
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
