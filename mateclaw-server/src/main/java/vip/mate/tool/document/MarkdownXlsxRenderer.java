package vip.mate.tool.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Render a Markdown string into an Excel .xlsx byte array using Apache POI.
 *
 * <p>Convention: each ATX H1 ({@code # Sheet Name}) starts a new sheet. The
 * pipe-style table that follows becomes the sheet body. The first table row
 * is treated as the header (bold, light-grey fill, frozen). Numeric-looking
 * cells are stored as numbers — except when doing so would change the text the
 * user sees; see {@link #isStorableNumber(String)}.
 *
 * <p>Markdown without an explicit {@code # heading} produces a single sheet
 * named {@code Sheet1}. Markdown without any {@code | table |} rows produces
 * an empty workbook with one blank sheet (rendering still succeeds).
 */
@Slf4j
@Component
public class MarkdownXlsxRenderer {

    /**
     * Detects the markdown table separator row, e.g. {@code | --- | :---: |}.
     *
     * <p>The column group is optional ({@code *}, not {@code +}) so a
     * one-column table — {@code | item |} over {@code | --- |} — is recognised
     * too; it used to fall through and the separator row landed in the sheet as
     * data.
     */
    private static final Pattern TABLE_SEPARATOR =
            Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");

    /** Detects a sheet boundary {@code # Sheet Name}. ## / ### are NOT boundaries. */
    private static final Pattern SHEET_BOUNDARY = Pattern.compile("^#\\s+(.+)$");

    /** Cells that look like numbers (optional sign, digits, optional decimal). */
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    /**
     * Excel stores numbers as IEEE-754 doubles, which keep at most 15
     * significant decimal digits. Past that the workbook shows a different
     * value than the report asked for.
     */
    private static final int MAX_EXACT_INTEGER_DIGITS = 15;

    public byte[] render(String markdown) throws IOException {
        if (markdown == null) markdown = "";

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            CellStyle headerStyle = buildHeaderStyle(wb);

            List<SheetSpec> sheets = parseSheets(markdown);
            if (sheets.isEmpty()) {
                // Always produce a non-empty workbook so the file is openable.
                wb.createSheet("Sheet1");
            } else {
                // Track names lowercased — Excel sheet uniqueness is
                // case-insensitive ("Sales" and "sales" collide).
                Set<String> usedLower = new HashSet<>(sheets.size());
                int seq = 1;
                for (SheetSpec spec : sheets) {
                    String safe = sanitizeSheetName(spec.name(), seq++);
                    String unique = uniqueSheetName(safe, usedLower);
                    Sheet sheet = wb.createSheet(unique);
                    writeSheetBody(sheet, spec.rows(), headerStyle);
                }
            }

            wb.write(baos);
            return baos.toByteArray();
        }
    }

    private record SheetSpec(String name, List<List<String>> rows) {}

    private List<SheetSpec> parseSheets(String markdown) {
        List<SheetSpec> sheets = new ArrayList<>();
        String currentName = null;
        List<List<String>> currentRows = new ArrayList<>();

        for (String rawLine : markdown.split("\\R", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;

            var sheetMatch = SHEET_BOUNDARY.matcher(line);
            if (sheetMatch.matches()) {
                if (currentName != null || !currentRows.isEmpty()) {
                    sheets.add(new SheetSpec(currentName, currentRows));
                }
                currentName = sheetMatch.group(1).strip();
                currentRows = new ArrayList<>();
                continue;
            }

            if (TABLE_SEPARATOR.matcher(line).matches()) {
                continue;
            }

            // Strict markdown-table detection: a row must be wrapped in pipes,
            // otherwise prose lines like "A | B 是数据库主键" or file paths like
            // "src/main/java/Foo|Bar" would be silently swallowed into the sheet.
            // GFM technically allows pipe-less leading/trailing pipes for tables,
            // but the rendered LLM output overwhelmingly uses the wrapped form,
            // and being strict avoids false positives that pollute the workbook.
            if (line.startsWith("|") && line.endsWith("|") && line.length() >= 2) {
                List<String> cells = splitTableRow(line);
                if (!cells.isEmpty()) {
                    currentRows.add(cells);
                }
            }
            // Other content (paragraphs, sub-headings) is intentionally ignored —
            // xlsx is tabular and there is nowhere sensible to render free prose.
        }

        if (currentName != null || !currentRows.isEmpty()) {
            sheets.add(new SheetSpec(currentName, currentRows));
        }
        return sheets;
    }

    /**
     * Split a pipe-table row into cells, honouring GFM's pipe escape.
     *
     * <p>A pipe that belongs to a cell is written {@code \|} — it is content,
     * not a column boundary, so a row like {@code | a \| b | x |} has two
     * cells. Splitting on every pipe produced three: the backslash stayed in
     * the text and everything after it moved one column to the right, which in
     * the sheet means the values sat under the wrong header without any error.
     */
    private List<String> splitTableRow(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();

        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        // The trailing boundary pipe is not part of the last cell, but an
        // escaped one (…\|) is content.
        if (end > 0 && line.charAt(end - 1) == '|' && !isEscaped(line, end - 1)) end--;

        int start = 0;
        while (start < end && Character.isWhitespace(line.charAt(start))) start++;
        if (start < end && line.charAt(start) == '|') start++;

        for (int i = start; i < end; i++) {
            char ch = line.charAt(i);
            if (ch == '\\' && i + 1 < end && line.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (ch == '|') {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString().strip());
        return cells;
    }

    /** True when the character at {@code idx} is preceded by an odd run of backslashes. */
    private static boolean isEscaped(String line, int idx) {
        int backslashes = 0;
        for (int i = idx - 1; i >= 0 && line.charAt(i) == '\\'; i--) backslashes++;
        return backslashes % 2 == 1;
    }

    /**
     * Whether the cell text can be written as an Excel number without changing
     * what the reader sees.
     *
     * <p>Excel numbers cannot hold a leading zero ({@code 007} comes back as
     * {@code 7}) and are IEEE-754 doubles, so an integer wider than 15 digits
     * loses its tail (a 19-digit order number is displayed as
     * {@code 1.23457E+20}). Both shapes are identifiers in practice: part
     * numbers, batch codes, order numbers, phone numbers. So they stay text.
     * Decimal numbers and integers that fit are still stored as numbers, which
     * keeps them summable and sortable.
     */
    static boolean isStorableNumber(String value) {
        if (value == null || !NUMERIC.matcher(value).matches()) {
            return false;
        }
        String digits = value.startsWith("-") ? value.substring(1) : value;
        int dot = digits.indexOf('.');
        String integerPart = dot < 0 ? digits : digits.substring(0, dot);
        if (integerPart.length() > 1 && integerPart.charAt(0) == '0') {
            return false;
        }
        return integerPart.length() <= MAX_EXACT_INTEGER_DIGITS;
    }

    private void writeSheetBody(Sheet sheet, List<List<String>> rows, CellStyle headerStyle) {
        if (rows.isEmpty()) return;

        int maxCols = 0;
        for (int r = 0; r < rows.size(); r++) {
            List<String> rowData = rows.get(r);
            Row row = sheet.createRow(r);
            for (int c = 0; c < rowData.size(); c++) {
                Cell cell = row.createCell(c);
                String value = rowData.get(c);
                if (isStorableNumber(value)) {
                    cell.setCellValue(Double.parseDouble(value));
                } else {
                    cell.setCellValue(value);
                }
                if (r == 0) cell.setCellStyle(headerStyle);
            }
            if (rowData.size() > maxCols) maxCols = rowData.size();
        }

        // Freeze the header row and auto-size columns. autoSizeColumn is O(n*m)
        // but agent-generated workbooks are small, so the cost is negligible.
        sheet.createFreezePane(0, 1);
        for (int c = 0; c < maxCols; c++) {
            try {
                sheet.autoSizeColumn(c);
            } catch (Exception e) {
                log.debug("autoSizeColumn({}) failed (likely missing fonts on a headless host): {}",
                        c, e.getMessage());
            }
        }
    }

    private CellStyle buildHeaderStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    /**
     * Resolve duplicate sheet names by appending {@code  (2)}, {@code  (3)}…
     * within the 31-char Excel limit. POI throws on collision, which would
     * otherwise abort the entire render when an LLM emits two sheets with the
     * same heading or two long headings whose first 31 chars happen to match.
     *
     * <p>Excel sheet uniqueness is case-INsensitive, so {@code "Sales"} and
     * {@code "sales"} collide. We track names lowercased while still passing
     * the original casing into {@link Sheet#createSheet(String)} — so the
     * displayed tab keeps the user's casing.
     */
    private String uniqueSheetName(String candidate, Set<String> usedLower) {
        if (usedLower.add(candidate.toLowerCase(Locale.ROOT))) return candidate;
        for (int i = 2; i < 1000; i++) {
            String suffix = " (" + i + ")";
            int maxBase = 31 - suffix.length();
            String base = candidate.length() > maxBase
                    ? candidate.substring(0, maxBase)
                    : candidate;
            String trial = base + suffix;
            if (usedLower.add(trial.toLowerCase(Locale.ROOT))) return trial;
        }
        // Pathological: 1000 collisions. Fall back to a guaranteed-unique tag
        // built from nanoTime so the render still succeeds.
        String fallback = ("Sheet_" + System.nanoTime());
        if (fallback.length() > 31) fallback = fallback.substring(0, 31);
        usedLower.add(fallback.toLowerCase(Locale.ROOT));
        return fallback;
    }

    /**
     * Excel sheet names are limited to 31 chars and cannot contain {@code : / \ ? * [ ]},
     * cannot be blank, and must be unique. Uniqueness is enforced separately by
     * {@link #uniqueSheetName(String, Set)} so this method stays single-shot.
     */
    private String sanitizeSheetName(String raw, int seq) {
        if (raw == null || raw.isBlank()) return "Sheet" + seq;
        StringBuilder sb = new StringBuilder(raw.length());
        for (char ch : raw.toCharArray()) {
            if (ch == ':' || ch == '/' || ch == '\\' || ch == '?'
                    || ch == '*' || ch == '[' || ch == ']') {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        String cleaned = sb.toString().strip();
        if (cleaned.isEmpty()) cleaned = "Sheet" + seq;
        if (cleaned.length() > 31) cleaned = cleaned.substring(0, 31);
        return cleaned;
    }
}
