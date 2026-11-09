package vip.mate.tool.document;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cell typing for renderXlsx. Identifiers such as {@code 007} or a 19-digit
 * order number must survive the round trip verbatim; anything converted to an
 * Excel number is re-displayed by the spreadsheet, and Excel cannot hold
 * leading zeros or more than 15 significant digits.
 */
class MarkdownXlsxRendererTest {

    private static Cell firstColumnCell(String cell) throws Exception {
        String markdown = "# Sheet\n| value | note |\n| --- | --- |\n| " + cell + " | x |\n";
        byte[] bytes = new MarkdownXlsxRenderer().render(markdown);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            return sheet.getRow(1).getCell(0);
        }
    }

    @Test
    void keepsIdentifiersWithLeadingZerosAsText() throws Exception {
        Cell cell = firstColumnCell("007");
        assertEquals(CellType.STRING, cell.getCellType(), "leading zeros are significant in identifiers");
        assertEquals("007", cell.getStringCellValue());
    }

    @Test
    void keepsDecimalIdentifiersWithLeadingZerosAsText() throws Exception {
        Cell cell = firstColumnCell("0012.5");
        assertEquals(CellType.STRING, cell.getCellType());
        assertEquals("0012.5", cell.getStringCellValue());
    }

    @Test
    void keepsIntegersBeyondDoublePrecisionAsText() throws Exception {
        String orderNo = "123456789012345678901";
        Cell cell = firstColumnCell(orderNo);
        assertEquals(CellType.STRING, cell.getCellType(), "Excel numbers hold at most 15 significant digits");
        assertEquals(orderNo, cell.getStringCellValue());
    }

    @Test
    void keepsHighPrecisionDecimalsAsText() throws Exception {
        String decimal = "0.12345678901234567";
        Cell cell = firstColumnCell(decimal);
        assertEquals(CellType.STRING, cell.getCellType());
        assertEquals(decimal, cell.getStringCellValue());
    }

    @Test
    void keepsTinyNonzeroDecimalsAsText() throws Exception {
        String decimal = "0." + "0".repeat(325) + "1";
        Cell cell = firstColumnCell(decimal);
        assertEquals(CellType.STRING, cell.getCellType());
        assertEquals(decimal, cell.getStringCellValue());
    }

    @Test
    void keepsDashOnlyDataAfterSingleColumnSeparator() throws Exception {
        String markdown = "| item |\n| --- |\n| ---- |\n| after |\n";
        byte[] bytes = new MarkdownXlsxRenderer().render(markdown);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals(2, sheet.getLastRowNum());
            assertEquals("----", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("after", sheet.getRow(2).getCell(0).getStringCellValue());
        }
    }

    @Test
    void preservesEscapedPipeAtRowEndAfterTwoBackslashes() throws Exception {
        String markdown = "| first | last |\n| --- | --- |\n| a | b\\\\|\n";
        byte[] bytes = new MarkdownXlsxRenderer().render(markdown);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals("b\\|", wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
        }
    }

    /** The convention itself must survive: plain numbers still become numbers. */
    @Test
    void stillStoresPlainNumbersAsNumbers() throws Exception {
        assertNumeric("12.5", 12.5d);
        assertNumeric("-3", -3d);
        assertNumeric("0", 0d);
        assertNumeric("0.5", 0.5d);
        assertNumeric("12.5000000000000000", 12.5d);
        assertNumeric("123456789012345", 123456789012345d);
    }

    private static void assertNumeric(String cell, double expected) throws Exception {
        Cell rendered = firstColumnCell(cell);
        assertEquals(CellType.NUMERIC, rendered.getCellType(), cell + " should stay numeric");
        assertEquals(expected, rendered.getNumericCellValue());
    }

    /**
     * GFM lets a cell hold a literal pipe by escaping it ({@code a \| b}).
     * Splitting on every {@code |} broke that: the cell became two, the rest of
     * the row slid one column right (so the data sat under the wrong header)
     * and the leftover backslash was written into the sheet.
     */
    @Test
    void escapedPipeStaysInsideOneCell() throws Exception {
        String markdown = "# Sheet\n| command | note |\n| --- | --- |\n| a \\| b | x |\n";
        byte[] bytes = new MarkdownXlsxRenderer().render(markdown);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            var row = sheet.getRow(1);
            assertEquals(2, (int) row.getLastCellNum(), "the escaped pipe must not open a third column");
            assertEquals("a | b", row.getCell(0).getStringCellValue());
            assertEquals("x", row.getCell(1).getStringCellValue(), "the cell after it must not shift right");
        }
    }

    /** Only {@code \|} is an escape; other backslashes are ordinary text. */
    @Test
    void backslashThatDoesNotEscapeAPipeSurvives() throws Exception {
        assertEquals("C:\\temp\\", firstColumnCell("C:\\temp\\").getStringCellValue());
    }

    /** A one-column table is still a table; its separator row is metadata, not data. */
    @Test
    void singleColumnTableHasNoSeparatorRow() throws Exception {
        String markdown = "# Sheet\n| item |\n| --- |\n| one |\n| two |\n";
        byte[] bytes = new MarkdownXlsxRenderer().render(markdown);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals(2, sheet.getLastRowNum(), "header + 2 data rows, no separator row");
            assertEquals("item", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("one", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("two", sheet.getRow(2).getCell(0).getStringCellValue());
        }
    }
}
