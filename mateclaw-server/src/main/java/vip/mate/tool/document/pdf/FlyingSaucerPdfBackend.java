package vip.mate.tool.document.pdf;

import org.openpdf.text.pdf.BaseFont;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * In-process PDF rendering: markdown → flexmark XHTML → Flying Saucer (XHTMLRenderer)
 * → OpenPDF.
 *
 * <p>This backend is always available and is the only one that supports cover
 * pages, page headers, and page footers (driven by YAML frontmatter; see
 * {@link PdfFrontmatter}). It uses CSS3 paged-media features that Flying Saucer
 * implements: {@code @page}, {@code counter(page)}, {@code counter(pages)},
 * {@code @top-center}, {@code @bottom-center}, and {@code page-break-before}.
 *
 * <p>Flying Saucer requires strict XHTML, so flexmark's HTML output is wrapped
 * in an XHTML envelope. Self-closing void elements ({@code <br>}, {@code <hr>},
 * {@code <img>}) are normalised by flexmark when generating the body, so we do
 * not need a post-processor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlyingSaucerPdfBackend implements PdfBackend {

    private final PdfProperties properties;

    @Override
    public String name() { return "flying-saucer"; }

    @Override
    public byte[] render(PdfRenderRequest request) throws Exception {
        String bodyHtml = renderMarkdownToHtml(request.markdown());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            // Register the CJK font BEFORE building the HTML, because the CSS we
            // emit references the font's actual family name (read from the font
            // file). Aliases via ITextFontResolver's 5-arg overload proved
            // unreliable on .ttc collections: the API accepts the override but
            // the lookup map silently misses it, leaving the body to fall back
            // to Times-Roman and Chinese to render as .notdef boxes.
            String cjkFamily = registerCjkFont(renderer.getFontResolver());
            String fullHtml = wrapHtml(bodyHtml, request, cjkFamily);
            log.debug("[FlyingSaucerPdf] HTML length={}, body length={}, cjkFamily={}",
                    fullHtml.length(), bodyHtml.length(), cjkFamily);
            try {
                renderer.setDocumentFromString(fullHtml);
                renderer.layout();
                renderer.createPDF(baos);
            } catch (Throwable t) {
                log.error("[FlyingSaucerPdf] ITextRenderer failed: {}: {}",
                        t.getClass().getName(), t.getMessage(), t);
                throw t;
            }
            return baos.toByteArray();
        }
    }

    private String renderMarkdownToHtml(String markdown) {
        List<org.commonmark.Extension> extensions = List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
                YamlFrontMatterExtension.create());
        Parser parser = Parser.builder().extensions(extensions).build();
        // Flying Saucer requires strict XHTML, so void elements (<br>, <hr>,
        // <img>) must be self-closed. The xhtml renderer flavour does this.
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .build();
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }

    /**
     * Register the resolved CJK font with Flying Saucer and return the
     * font's actual {@code font-family} name so the inline stylesheet can
     * reference it. Returns {@code null} if no font was found or the
     * registration failed — callers must tolerate Chinese rendering as
     * blank boxes in that case.
     *
     * <p>Why we read the real family name instead of using the 5-arg
     * {@code addFont(... fontFamilyNameOverride ...)} overload: that override
     * succeeds in the call but does not get added to the renderer's
     * {@code _fontFamilies} lookup map for {@code .ttc} collections, so the
     * CSS declaration {@code font-family: "CJK"} still misses and the body
     * falls back to Times-Roman. Reading the font's intrinsic family name
     * via OpenPDF's {@link BaseFont#getFamilyFontName()} sidesteps that
     * map entirely.
     */
    private String registerCjkFont(ITextFontResolver fonts) {
        Optional<Path> fontPath = CjkFontResolver.resolve(properties.fontPath());
        if (fontPath.isEmpty()) {
            log.error("[FlyingSaucerPdf] No CJK font registered. Chinese characters "
                    + "in this PDF will render as blank boxes. Set mateclaw.pdf.font-path "
                    + "to the absolute path of a CJK-capable .ttf / .ttc / .otf file.");
            return null;
        }
        // BaseFont.IDENTITY_H + EMBEDDED is what makes CJK actually appear in
        // the output PDF — without IDENTITY_H glyph indexing, Chinese characters
        // render as blanks even when the font file is found.
        //
        // OpenPDF has a known weakness with Apple-style .ttc font
        // collections (PingFang.ttc, STHeiti.ttc, Songti.ttc on macOS): the
        // load succeeds but the cmap is empty, charExists returns false even
        // for ASCII, and the rendered PDF is a blank page. We probe the font
        // with charExists below; if it cannot render the characters we need,
        // we DO NOT register it and return null so the document keeps
        // falling back to the next family in the CSS chain.
        String fontKey = fontFileWithSubfontIndex(fontPath.get());
        BaseFont probe;
        try {
            probe = BaseFont.createFont(fontKey, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (Throwable t) {
            log.error("[FlyingSaucerPdf] BaseFont.createFont failed for {} — Chinese "
                    + "will render as blank boxes. {}: {}",
                    fontKey, t.getClass().getSimpleName(), t.getMessage());
            return null;
        }
        if (!probe.charExists('你') || !probe.charExists('A')) {
            log.error("[FlyingSaucerPdf] Font {} loaded but cmap is empty "
                    + "(charExists '你'={} 'A'={}). This is the known OpenPDF Apple-.ttc "
                    + "limitation — install a .ttf CJK font (e.g. HarmonyOS Sans SC, "
                    + "Noto Sans SC) and either drop it under ~/Library/Fonts/ or set "
                    + "mateclaw.pdf.font-path to its absolute path.",
                    fontKey, probe.charExists('你'), probe.charExists('A'));
            return null;
        }
        String realFamily = readFamilyName(probe, fontKey);
        try {
            fonts.addFont(fontKey, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            log.info("[FlyingSaucerPdf] registered CJK font: {} (family=\"{}\", cmap OK)",
                    fontKey, realFamily);
            return realFamily;
        } catch (Exception e) {
            log.error("[FlyingSaucerPdf] failed to register CJK font {} — Chinese "
                    + "characters in this PDF will render as blank boxes. {}: {}",
                    fontKey, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Pull a usable family name out of the loaded font. Some fonts
     * (HarmonyOS Sans SC) leave {@code getFamilyFontName} empty and
     * carry the name only in {@code getPostscriptFontName}, so we fall
     * back to that.
     */
    private static String readFamilyName(BaseFont probe, String fontKey) {
        try {
            String[][] familyNames = probe.getFamilyFontName();
            if (familyNames != null && familyNames.length > 0) {
                String fallback = null;
                for (String[] row : familyNames) {
                    if (row == null || row.length < 4 || row[3] == null || row[3].isBlank()) continue;
                    if (fallback == null) fallback = row[3];
                    if ("3".equals(row[0]) && "1033".equals(row[2])) {
                        return row[3];
                    }
                }
                if (fallback != null) return fallback;
            }
            String psName = probe.getPostscriptFontName();
            if (psName != null && !psName.isBlank()) return psName;
        } catch (Throwable t) {
            log.warn("[FlyingSaucerPdf] could not read family name from {}: {}",
                    fontKey, t.getMessage());
        }
        return "Helvetica"; // benign fallback
    }

    private static String fontFileWithSubfontIndex(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ttc") || name.endsWith(".otc")) {
            return path.toString() + ",0";
        }
        return path.toString();
    }

    /**
     * Wrap the rendered markdown body in an XHTML envelope plus a CSS @page
     * stylesheet that drives cover / header / footer / page numbers.
     *
     * @param cjkFamily the actual family name of the registered CJK font as
     *                  reported by OpenPDF, or {@code null} if no font was
     *                  registered. Injected verbatim into the body
     *                  {@code font-family} declaration; when absent we fall
     *                  through directly to Helvetica.
     */
    private String wrapHtml(String bodyHtml, PdfRenderRequest request, String cjkFamily) {
        PdfFrontmatter fm = request.frontmatter();
        String pageSize = request.pageSize();
        String cjkFamilyDecl = cjkFamily == null
                ? ""
                : "\"" + cssEscape(cjkFamily) + "\", ";

        // Only render a real cover page when the user explicitly asked for one
        // via YAML frontmatter. A synthesised cover (H1 promoted into title)
        // would otherwise duplicate the heading: once on the cover and again
        // as the first body H1.
        String coverHtml = fm.hasExplicitCover()
                ? "<div class=\"cover\">"
                + "<h1 class=\"cover-title\">" + escape(fm.title()) + "</h1>"
                + (fm.subtitleOpt().isPresent()
                    ? "<p class=\"cover-subtitle\">" + escape(fm.subtitle()) + "</p>"
                    : "")
                + "</div>"
                : "";

        // Page margin boxes do NOT inherit `font-family` from body — Flying
        // Saucer treats them as detached generated content boxes. If we don't
        // give them a CJK-capable font here, header/footer Chinese characters
        // silently drop ("Tech Daily · 每日科技精选" → "Tech Daily ·") because
        // the default Helvetica has no CJK glyphs. We thread the same family
        // we registered for body text through here so the rendering is
        // consistent across the document.
        String marginBoxFontDecl = "font-family: " + cjkFamilyDecl
                + "\"Helvetica\", sans-serif; font-size: 9pt; color: #888;";
        String headerCss = fm.hasHeader()
                ? "@top-center { content: \"" + cssEscape(fm.header()) + "\"; "
                  + marginBoxFontDecl + " }"
                : "";
        String footerCss = "@bottom-center { content: " + footerContent(fm)
                + "; " + marginBoxFontDecl + " }";

        // No <!DOCTYPE>: Flying Saucer's default EntityResolver tries to fetch
        // the W3C XHTML DTD over the network during setDocumentFromString().
        // On any host with no internet (or with W3C throttling) the document
        // load silently fails and we emit a 1.3 KB blank PDF. Plain XHTML
        // without a DOCTYPE renders just fine.
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                <title>document</title>
                <style type="text/css">
                @page {
                    size: %s;
                    margin: 25mm 20mm 22mm 20mm;
                    %s
                    %s
                }
                @page :first {
                    @top-center { content: ""; }
                    @bottom-center { content: ""; }
                }
                body {
                    font-family: %s"Helvetica", "Arial", sans-serif;
                    font-size: 11pt;
                    line-height: 1.6;
                    color: #222;
                }
                .cover {
                    page-break-after: always;
                    text-align: center;
                    padding-top: 60mm;
                }
                .cover-title {
                    font-size: 36pt;
                    margin-bottom: 12mm;
                    font-weight: bold;
                    /* The cover-title element is an h1, so we must explicitly
                       cancel the body-h1 rule below — otherwise its
                       `page-break-before: always` and bottom border would
                       split the cover into a blank leading page plus the
                       title underlined like a section heading. */
                    page-break-before: auto;
                    border-bottom: none;
                    padding-bottom: 0;
                }
                .cover-subtitle { font-size: 18pt; color: #666; margin: 0; }
                h1 {
                    font-size: 22pt;
                    margin: 18pt 0 10pt;
                    page-break-before: always;
                    border-bottom: 1pt solid #ccc;
                    padding-bottom: 4pt;
                }
                /* The first H1 inside the body must NOT force a fresh page when
                   we did not render a cover, otherwise the document opens on a
                   blank first page. */
                .content > h1:first-child { page-break-before: auto; }
                h2 { font-size: 16pt; margin: 14pt 0 6pt; }
                h3 { font-size: 13pt; margin: 12pt 0 4pt; color: #333; }
                p { margin: 6pt 0; }
                ul, ol { margin: 6pt 0 6pt 18pt; padding: 0; }
                li { margin: 2pt 0; }
                table {
                    border-collapse: collapse;
                    width: 100%%;
                    margin: 8pt 0;
                    font-size: 10pt;
                }
                th, td {
                    border: 0.5pt solid #888;
                    padding: 4pt 6pt;
                    vertical-align: top;
                }
                th { background: #eee; font-weight: bold; text-align: left; }
                code {
                    font-family: "Menlo", "Consolas", monospace;
                    background: #f6f6f6;
                    padding: 1px 4px;
                    font-size: 10pt;
                }
                pre {
                    background: #f6f6f6;
                    padding: 8pt 10pt;
                    border-left: 3pt solid #ccc;
                    font-size: 10pt;
                    white-space: pre-wrap;
                    word-break: break-all;
                }
                pre code { background: none; padding: 0; }
                blockquote {
                    border-left: 3pt solid #ccc;
                    padding: 0 12pt;
                    color: #555;
                    margin: 6pt 0;
                }
                hr { border: none; border-top: 1pt solid #ddd; margin: 12pt 0; }
                a { color: #205493; text-decoration: none; }
                </style>
                </head>
                <body>
                %s
                <div class="content">%s</div>
                </body>
                </html>
                """.formatted(pageSize, headerCss, footerCss, cjkFamilyDecl, coverHtml, bodyHtml);
    }

    private String footerContent(PdfFrontmatter fm) {
        // Always show page numbers; concatenate user footer ahead if provided.
        String pageCounter = "\"" + cssEscape("第 ") + "\" counter(page) "
                + "\" / \" counter(pages) \"" + cssEscape(" 页") + "\"";
        if (fm.hasFooter()) {
            return "\"" + cssEscape(fm.footer()) + "  \" " + pageCounter;
        }
        return pageCounter;
    }

    /** Escape user text for placement inside an HTML element. */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Escape user text for placement inside a CSS string literal. */
    private String cssEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ");
    }
}
