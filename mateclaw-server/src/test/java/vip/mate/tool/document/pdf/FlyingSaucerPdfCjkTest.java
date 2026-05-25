package vip.mate.tool.document.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test for the in-process PDF backend's CJK rendering. The
 * historical bug we are guarding against: registering the font under the
 * alias {@code "CJK"} (or any other override name) succeeded silently but
 * the CSS lookup missed it and the body fell back to Times-Roman, leaving
 * Chinese characters rendered as {@code .notdef} blank boxes.
 *
 * <p>This test renders a markdown body containing Chinese, then uses PDFBox
 * to inspect the resulting PDF's embedded fonts. The assertion is that at
 * least one font in the document has a name matching a known CJK family —
 * Times-Roman alone is a regression.
 */
class FlyingSaucerPdfCjkTest {

    /**
     * Substrings that, when present in a font's PostScript / BaseFont name,
     * indicate a CJK-capable font has been embedded. The list covers the
     * default CjkFontResolver candidates on macOS, Windows, and common
     * Linux distros.
     */
    private static final List<String> CJK_FONT_MARKERS = List.of(
            "STHeiti", "Heiti", "PingFang", "Songti",
            "Microsoft YaHei", "MicrosoftYaHei", "MSYH",
            "SimHei", "SimSun", "SongTi", "Song",
            "NotoSans", "NotoSansCJK",
            "HarmonyOS", "Harmony",
            "SourceHan", "SourceHanSans",
            "WQY", "WenQuanYi", "AR PL", "ArialUnicode"
    );

    @Test
    @EnabledOnOs(OS.MAC)
    @DisplayName("Chinese markdown renders with an embedded CJK font (not just Times-Roman)")
    void chineseRendersWithEmbeddedCjkFont() throws Exception {
        PdfProperties properties = new PdfProperties(null, PdfProperties.Engine.HTML, null);
        FlyingSaucerPdfBackend backend = new FlyingSaucerPdfBackend(properties);

        // Plain string concatenation, NOT a Java text block: text block's
        // relative-indent normalisation makes the empty-line vs body-line
        // common-prefix rule unpredictable, and a 4+ space prefix is treated
        // as an indented code block by CommonMark — that strips out every
        // body line and leaves only the H1, which then renders into a
        // 1.3 KB blank-looking PDF.
        String markdown =
                "# 季度业务回顾\n\n"
              + "这是一份**中文**测试文档。\n\n"
              + "- 第一条要点：业务增长 30%\n"
              + "- 第二条要点：用户达到 100 万\n"
              + "- 第三条要点：新增三个企业客户\n\n"
              + "## 详细内容\n\n"
              + "这里有更多的中文段落，用来验证字体嵌入是否生效。\n";

        PdfRenderRequest request = new PdfRenderRequest(
                markdown, PdfFrontmatter.parseOrSynthesise(markdown),
                "A4", PdfProperties.Engine.HTML);

        // Reflectively peek at the intermediate HTML the renderer feeds to
        // OpenPDF — when the produced PDF is suspiciously small (just the
        // catalog header), the failure is upstream of OpenPDF, in either
        // commonmark parsing or wrapHtml's template substitution.
        java.lang.reflect.Method wrapHtmlMethod = FlyingSaucerPdfBackend.class
                .getDeclaredMethod("wrapHtml", String.class, PdfRenderRequest.class, String.class);
        wrapHtmlMethod.setAccessible(true);
        java.lang.reflect.Method renderMdMethod = FlyingSaucerPdfBackend.class
                .getDeclaredMethod("renderMarkdownToHtml", String.class);
        renderMdMethod.setAccessible(true);

        String bodyHtml = (String) renderMdMethod.invoke(backend, markdown);
        String fullHtml = (String) wrapHtmlMethod.invoke(backend, bodyHtml, request, "Heiti TC");

        java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/mateclaw-pdf-cjk-test.html"), fullHtml);
        System.out.println("[probe] body html length=" + bodyHtml.length()
                + " sample=" + bodyHtml.substring(0, Math.min(200, bodyHtml.length())));
        System.out.println("[probe] full html length=" + fullHtml.length());

        byte[] pdfBytes = backend.render(request);
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0, "renderer produced no output");

        // Dump for manual inspection — useful when the assertion fails so the
        // tester can `strings` / `pdftotext` the output without re-running.
        java.nio.file.Path dump = java.nio.file.Path.of("/tmp/mateclaw-pdf-cjk-test.pdf");
        java.nio.file.Files.write(dump, pdfBytes);
        System.out.println("[probe] wrote " + pdfBytes.length + " bytes to " + dump);

        // Cross-check the raw bytes too. PDFBox's font enumeration sometimes
        // misses Type0 + CIDFontType2 wired by OpenPDF; the raw `/BaseFont`
        // markers in the byte stream are easier to verify.
        String rawText = new String(pdfBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("/BaseFont\\s*/([A-Za-z0-9+\\-]+)")
                .matcher(rawText);
        Set<String> rawFontNames = new HashSet<>();
        while (matcher.find()) rawFontNames.add(matcher.group(1));
        System.out.println("[probe] raw /BaseFont names: " + rawFontNames);

        Set<String> fontNames = collectFontNames(pdfBytes);
        System.out.println("[probe] PDFBox-enumerated fonts: " + fontNames);

        // Combine both sources before asserting — this lets the test pass
        // even if PDFBox's enumeration is incomplete, while still failing
        // when the document only carries Times-Roman / Helvetica.
        Set<String> allFontNames = new HashSet<>();
        allFontNames.addAll(fontNames);
        allFontNames.addAll(rawFontNames);
        fontNames = allFontNames;
        assertFalse(fontNames.isEmpty(), "PDF has no embedded fonts at all (raw or via PDFBox)");

        boolean hasCjk = fontNames.stream()
                .anyMatch(name -> CJK_FONT_MARKERS.stream()
                        .anyMatch(marker -> name.toLowerCase().contains(marker.toLowerCase())));

        assertTrue(hasCjk,
                "No CJK font embedded in the PDF — Chinese will render as blanks. "
                        + "Fonts found: " + fontNames);
    }

    /**
     * Walk every page's resources and collect the BaseFont names of every
     * referenced font. Includes Type0 (composite) fonts for CJK plus their
     * descendant CIDFontType2 fonts, where the actual TrueType glyph data
     * lives.
     */
    private static Set<String> collectFontNames(byte[] pdfBytes) throws Exception {
        Set<String> names = new HashSet<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) continue;
                List<COSName> fontKeys = new ArrayList<>();
                resources.getFontNames().forEach(fontKeys::add);
                for (COSName key : fontKeys) {
                    PDFont font = resources.getFont(key);
                    if (font == null) continue;
                    String baseFont = font.getName();
                    if (baseFont != null) names.add(baseFont);
                    // Walk descendant fonts of Type0 composite fonts (where CJK lives).
                    COSDictionary dict = font.getCOSObject();
                    Object descendants = dict.getDictionaryObject(COSName.getPDFName("DescendantFonts"));
                    if (descendants != null) names.add(descendants.toString());
                }
            }
        }
        return names;
    }
}
