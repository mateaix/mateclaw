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

    /** The convention itself must survive: plain numbers still become numbers. */
    @Test
    void stillStoresPlainNumbersAsNumbers() throws Exception {
        assertNumeric("12.5", 12.5d);
        assertNumeric("-3", -3d);
        assertNumeric("0", 0d);
        assertNumeric("0.5", 0.5d);
        assertNumeric("123456789012345", 123456789012345d);
    }

    private static void assertNumeric(String cell, double expected) throws Exception {
        Cell rendered = firstColumnCell(cell);
        assertEquals(CellType.NUMERIC, rendered.getCellType(), cell + " should stay numeric");
        assertEquals(expected, rendered.getNumericCellValue());
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
