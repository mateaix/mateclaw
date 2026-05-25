package vip.mate.tool.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests for {@link MarkdownDocxRenderer}. Verifies that the renderer
 * produces a syntactically valid .docx that POI can re-open and that the
 * required Markdown elements actually map to the right OOXML structures.
 */
class MarkdownDocxRendererTest {

    private final MarkdownDocxRenderer renderer = new MarkdownDocxRenderer();

    @Test
    @DisplayName("Empty markdown still produces a valid, openable .docx")
    void emptyMarkdownIsValid() throws Exception {
        byte[] bytes = renderer.render("", "A4");
        assertNotNull(bytes);
        assertTrue(bytes.length > 0, "should produce some bytes");
        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertNotNull(reopened);
        }
    }

    @Test
    @DisplayName("Headings, bold, lists, and tables all round-trip")
    void mixedMarkdownRoundTrips() throws Exception {
        String md = """
                # Title

                ## Subtitle

                ### Section

                A normal paragraph with **bold inside** it.

                - bullet one
                - bullet two

                1. step one
                2. step two

                | Name | Score |
                | ---- | ----- |
                | Alice | 90 |
                | Bob | 85 |
                """;

        byte[] bytes = renderer.render(md, "A4");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            assertFalse(paragraphs.isEmpty(), "should have paragraphs");

            assertTrue(containsParagraphText(paragraphs, "Title"));
            assertTrue(containsParagraphText(paragraphs, "Subtitle"));
            assertTrue(containsParagraphText(paragraphs, "Section"));
            assertTrue(containsParagraphText(paragraphs, "bold inside"));
            assertTrue(containsParagraphText(paragraphs, "bullet one"));
            assertTrue(containsParagraphText(paragraphs, "step one"));

            assertEquals("Heading1", styleOf(paragraphs, "Title"));
            assertEquals("Heading2", styleOf(paragraphs, "Subtitle"));
            assertEquals("Heading3", styleOf(paragraphs, "Section"));

            assertTrue(boldRunPresent(paragraphs, "bold inside"),
                    "**bold inside** should produce a bold run");

            List<XWPFTable> tables = doc.getTables();
            assertEquals(1, tables.size(), "exactly one table expected");
            XWPFTable table = tables.get(0);
            assertEquals(3, table.getRows().size(), "header + 2 data rows");
            assertEquals("Name", table.getRow(0).getCell(0).getText().trim());
            assertEquals("Alice", table.getRow(1).getCell(0).getText().trim());
        }
    }

    @Test
    @DisplayName("LETTER page size sets the right page width")
    void letterPageSizeSetsWidth() throws Exception {
        byte[] bytes = renderer.render("# Hello", "LETTER");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var sectPr = doc.getDocument().getBody().getSectPr();
            assertNotNull(sectPr);
            assertEquals(BigInteger.valueOf(12240), sectPr.getPgSz().getW());
            assertEquals(BigInteger.valueOf(15840), sectPr.getPgSz().getH());
        }
    }

    @Test
    @DisplayName("Default A4 sets the right page width")
    void defaultPageSizeIsA4() throws Exception {
        byte[] bytes = renderer.render("# Hello", null);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var sectPr = doc.getDocument().getBody().getSectPr();
            assertNotNull(sectPr);
            assertEquals(BigInteger.valueOf(11906), sectPr.getPgSz().getW());
            assertEquals(BigInteger.valueOf(16838), sectPr.getPgSz().getH());
        }
    }

    // ==================== helpers ====================

    private boolean containsParagraphText(List<XWPFParagraph> paragraphs, String needle) {
        for (XWPFParagraph p : paragraphs) {
            if (p.getText() != null && p.getText().contains(needle)) return true;
        }
        return false;
    }

    private String styleOf(List<XWPFParagraph> paragraphs, String needle) {
        for (XWPFParagraph p : paragraphs) {
            if (p.getText() != null && p.getText().contains(needle)) return p.getStyle();
        }
        return null;
    }

    private boolean boldRunPresent(List<XWPFParagraph> paragraphs, String needle) {
        for (XWPFParagraph p : paragraphs) {
            if (p.getText() == null || !p.getText().contains(needle)) continue;
            for (var run : p.getRuns()) {
                if (run.isBold() && needle.equals(run.getText(0))) return true;
            }
        }
        return false;
    }
}
