package vip.mate.tool.builtin;

import cn.hutool.json.JSONUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DocumentExtractToolSpreadsheetTest {
    @Test
    void largeSpreadsheetReturnsBoundedPreviewWithTruncation(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("large.xlsx");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            var sheet = workbook.createSheet("Data");
            Random random = new Random(635);
            byte[] bytes = new byte[750];
            for (int row = 0; row < 10_000; row++) {
                random.nextBytes(bytes);
                sheet.createRow(row).createCell(0).setCellValue(
                        "row-" + row + "-" + Base64.getEncoder().encodeToString(bytes));
            }
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        assertTrue(Files.size(file) > 7_000_000, "exercise a real 7 MB+ XLSX upload");
        for (String options : new String[]{null, "{\"method\":\"tika\"}"}) {
            var result = JSONUtil.parseObj(new DocumentExtractTool().extractTrustedDocument(file.toString(), options));
            assertTrue(result.getBool("success"), result.toString());
            assertTrue(result.getBool("truncated"));
            String text = result.getStr("text");
            assertTrue(text.contains("row-0-"));
            assertFalse(text.contains("row-9999-"));
            assertTrue(text.length() < 501_000);
            assertTrue(text.contains("总长度至少: 500001"), "parse must stop at the response budget");
        }
    }
}
