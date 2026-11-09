package vip.mate.tool.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Render a Markdown string into a Word .docx byte array using Apache POI,
 * entirely in-process. Replaces the docx-js Node.js subprocess used by
 * {@code skills/docx} for the "create new document" code path.
 *
 * <p>Supported elements: ATX headings (# / ## / ###), bold (**...**),
 * bullet lists (- / *), numbered lists (1. / 2. ...), pipe-style tables, and
 * plain paragraphs. Empty Markdown lines are preserved as empty paragraphs.
 *
 * <p>Inline parsing is intentionally minimal: only **bold** is recognized.
 * For more advanced layouts (images, headers/footers, exact OOXML edits), the
 * {@code skills/docx} unpack/edit/pack workflow remains the right choice.
 */
@Slf4j
@Component
public class MarkdownDocxRenderer {

    /** Matches **bold** spans (non-greedy, refuses empty content). */
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    private static final Pattern UNORDERED_ITEM = Pattern.compile("^\\s*[-*]\\s+(.*)$");
    private static final Pattern ORDERED_ITEM = Pattern.compile("^\\s*\\d+\\.\\s+(.*)$");
    /**
     * Separator row of a pipe table. The column group is optional so a
     * one-column table ({@code | item |} / {@code | --- |}) is recognised;
     * otherwise the block fell through to the paragraph renderer and the whole
     * table came out as text. A bare {@code ---} still cannot start a table
     * because the header line must begin with {@code |}.
     */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");

    /**
     * Image-only line, e.g. {@code ![alt text](path/to/file.png)}. Whitespace around
     * the syntax is allowed but inline images mixed with other text in the same
     * paragraph are intentionally NOT recognized — they would require splitting
     * a single paragraph into multiple POI runs with image positioning that the
     * markdown subset doesn't otherwise support.
     */
    private static final Pattern IMAGE_LINE = Pattern.compile(
            "^\\s*!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)\\s*$");

    /**
     * Page width in EMU after default A4 margins (page width 11906 twips - left
     * 1800 - right 1800 = 8306 twips ≈ 5.77 inches). POI's image API works in EMU
     * (1 inch = 914400 EMU); precomputing the maximum width keeps oversized images
     * from spilling outside the printable area while still allowing small images
     * to render at native size.
     */
    private static final int MAX_IMAGE_WIDTH_EMU = Units.toEMU(5.77 * 72);

    private static final String LATIN_FONT = "Arial";
    private static final String CJK_BODY_FONT = "FangSong"; // 仿宋
    private static final String CJK_HEADING_FONT = "SimHei"; // 黑体

    public byte[] render(String markdown, String pageSize) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            configurePageSize(doc, pageSize);
            BigInteger bulletNumId = configureNumbering(doc, true);
            BigInteger decimalNumId = configureNumbering(doc, false);

            List<String> lines = splitLines(markdown == null ? "" : markdown);
            int i = 0;
            while (i < lines.size()) {
                String line = lines.get(i);
                String stripped = line.strip();

                // Table block: header line + separator + body rows
                if (stripped.startsWith("|") && i + 1 < lines.size()
                        && TABLE_SEPARATOR.matcher(lines.get(i + 1)).matches()) {
                    int end = i + 2;
                    while (end < lines.size() && lines.get(end).strip().startsWith("|")) {
                        end++;
                    }
                    renderTable(doc, lines.subList(i, end));
                    i = end;
                    continue;
                }

                Matcher imageMatch = IMAGE_LINE.matcher(line);
                if (imageMatch.matches()) {
                    renderImage(doc, imageMatch.group(1), imageMatch.group(2));
                } else if (stripped.startsWith("### ")) {
                    renderHeading(doc, stripped.substring(4), 3);
                } else if (stripped.startsWith("## ")) {
                    renderHeading(doc, stripped.substring(3), 2);
                } else if (stripped.startsWith("# ")) {
                    renderHeading(doc, stripped.substring(2), 1);
                } else {
                    Matcher ul = UNORDERED_ITEM.matcher(line);
                    Matcher ol = ORDERED_ITEM.matcher(line);
                    if (ul.matches()) {
                        renderListItem(doc, ul.group(1), bulletNumId);
                    } else if (ol.matches()) {
                        renderListItem(doc, ol.group(1), decimalNumId);
                    } else if (stripped.isEmpty()) {
                        doc.createParagraph();
                    } else {
                        renderParagraph(doc, line);
                    }
                }
                i++;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return baos.toByteArray();
        }
    }

    // ==================== page setup ====================

    private void configurePageSize(XWPFDocument doc, String pageSize) {
        CTSectPr sectPr = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();

        CTPageSz pgSz = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        boolean letter = pageSize != null && pageSize.equalsIgnoreCase("LETTER");
        if (letter) {
            pgSz.setW(BigInteger.valueOf(12240));
            pgSz.setH(BigInteger.valueOf(15840));
        } else {
            // A4 default
            pgSz.setW(BigInteger.valueOf(11906));
            pgSz.setH(BigInteger.valueOf(16838));
        }

        CTPageMar pgMar = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        pgMar.setTop(BigInteger.valueOf(1440));
        pgMar.setBottom(BigInteger.valueOf(1440));
        pgMar.setLeft(BigInteger.valueOf(1800));
        pgMar.setRight(BigInteger.valueOf(1800));
        pgMar.setHeader(BigInteger.valueOf(720));
        pgMar.setFooter(BigInteger.valueOf(720));
        pgMar.setGutter(BigInteger.ZERO);
    }

    // ==================== numbering ====================

    private BigInteger configureNumbering(XWPFDocument doc, boolean bullet) {
        XWPFNumbering numbering = doc.createNumbering();
        CTAbstractNum abstractNum = CTAbstractNum.Factory.newInstance();
        // Temporary id; XWPFAbstractNum will assign the real one when added.
        abstractNum.setAbstractNumId(BigInteger.ZERO);

        CTLvl lvl = abstractNum.addNewLvl();
        lvl.setIlvl(BigInteger.ZERO);
        lvl.addNewStart().setVal(BigInteger.ONE);
        if (bullet) {
            lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
            lvl.addNewLvlText().setVal("•");
        } else {
            lvl.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
            lvl.addNewLvlText().setVal("%1.");
        }
        CTPPrGeneral ppr = lvl.addNewPPr();
        CTInd ind = ppr.addNewInd();
        ind.setLeft(BigInteger.valueOf(720));
        ind.setHanging(BigInteger.valueOf(360));

        XWPFAbstractNum xwpfAbstractNum = new XWPFAbstractNum(abstractNum);
        BigInteger absNumId = numbering.addAbstractNum(xwpfAbstractNum);
        return numbering.addNum(absNumId);
    }

    // ==================== headings & paragraphs ====================

    private void renderHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        // Spacing before/after, in twentieths of a point.
        switch (level) {
            case 1 -> { p.setSpacingBefore(240); p.setSpacingAfter(120); }
            case 2 -> { p.setSpacingBefore(160); p.setSpacingAfter(80); }
            default -> { p.setSpacingBefore(120); p.setSpacingAfter(60); }
        }
        renderInline(p, text, true, level);
    }

    private void renderParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.LEFT);
        renderInline(p, text, false, 0);
    }

    private void renderListItem(XWPFDocument doc, String text, BigInteger numId) {
        XWPFParagraph p = doc.createParagraph();
        p.setNumID(numId);
        renderInline(p, text, false, 0);
    }

    // ==================== images ====================

    /**
     * Render an {@code ![alt](path)} line as an embedded image. Falls back to
     * showing the alt text in italics on any failure (file missing, unsupported
     * format, SVG conversion error) so the rest of the document still renders.
     * <p>
     * Path resolution: the markdown is treated as living in the workspace root,
     * so a path like {@code assets/x.png} resolves relative to the JVM working
     * directory. Absolute paths are accepted as-is. {@code .svg} files are
     * rasterized to PNG via Apache Batik before embedding because OOXML images
     * must be a raster format.
     */
    private void renderImage(XWPFDocument doc, String alt, String rawPath) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = p.createRun();

        Path path;
        try {
            path = Paths.get(rawPath);
            if (!path.isAbsolute()) {
                path = Paths.get(".").resolve(rawPath).normalize();
            }
        } catch (Exception e) {
            renderImageFallback(run, alt, "invalid path: " + e.getMessage());
            return;
        }

        if (!Files.exists(path) || !Files.isReadable(path)) {
            renderImageFallback(run, alt, "file not found: " + path);
            return;
        }

        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int format;
        byte[] imageBytes;
        try {
            if (lower.endsWith(".svg")) {
                imageBytes = svgToPng(Files.readAllBytes(path));
                format = Document.PICTURE_TYPE_PNG;
            } else if (lower.endsWith(".png")) {
                imageBytes = Files.readAllBytes(path);
                format = Document.PICTURE_TYPE_PNG;
            } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                imageBytes = Files.readAllBytes(path);
                format = Document.PICTURE_TYPE_JPEG;
            } else if (lower.endsWith(".gif")) {
                imageBytes = Files.readAllBytes(path);
                format = Document.PICTURE_TYPE_GIF;
            } else if (lower.endsWith(".bmp")) {
                imageBytes = Files.readAllBytes(path);
                format = Document.PICTURE_TYPE_BMP;
            } else {
                renderImageFallback(run, alt, "unsupported image format: " + lower);
                return;
            }
        } catch (Exception e) {
            log.warn("[MarkdownDocxRenderer] failed to read image {}: {}", path, e.getMessage());
            renderImageFallback(run, alt, "read failed: " + e.getMessage());
            return;
        }

        // Choose width: scale to MAX_IMAGE_WIDTH_EMU. POI's addPicture expects
        // EMU; we don't know the source image's intrinsic size cheaply, so
        // pin width and let height scale proportionally via height=0 → POI
        // does not infer height for us, so use a reasonable height ratio
        // (4:3 default) to avoid stretching extremely wide diagrams.
        int width = MAX_IMAGE_WIDTH_EMU;
        int height = (int) (MAX_IMAGE_WIDTH_EMU * 0.6);
        try (ByteArrayInputStream in = new ByteArrayInputStream(imageBytes)) {
            run.addPicture(in, format, path.getFileName().toString(), width, height);
        } catch (Exception e) {
            log.warn("[MarkdownDocxRenderer] addPicture failed for {}: {}",
                    path, e.getMessage());
            renderImageFallback(run, alt, "embed failed: " + e.getMessage());
        }
    }

    /**
     * Convert an SVG byte array to PNG using Batik's PNGTranscoder. Width is
     * pinned so the rasterized output matches the docx page-width target;
     * height scales proportionally per the SVG's own viewBox.
     */
    private byte[] svgToPng(byte[] svgBytes) throws IOException {
        PNGTranscoder t = new PNGTranscoder();
        // Roughly 1400px wide → renders crisply at our docx target width.
        t.addTranscodingHint(PNGTranscoder.KEY_WIDTH, 1400f);
        TranscoderInput input = new TranscoderInput(new ByteArrayInputStream(svgBytes));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TranscoderOutput output = new TranscoderOutput(out);
        try {
            t.transcode(input, output);
        } catch (Exception e) {
            throw new IOException("SVG transcode failed: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    private void renderImageFallback(XWPFRun run, String alt, String reason) {
        run.setItalic(true);
        run.setText("[image: " + (alt == null || alt.isBlank() ? "(no alt)" : alt)
                + " — " + reason + "]");
    }

    // ==================== inline (bold) ====================

    private void renderInline(XWPFParagraph p, String text, boolean heading, int headingLevel) {
        if (text == null || text.isEmpty()) {
            // Make sure even empty headings still produce a run so style applies.
            createRun(p, "", heading, headingLevel, false);
            return;
        }
        Matcher m = BOLD.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                createRun(p, text.substring(last, m.start()), heading, headingLevel, false);
            }
            createRun(p, m.group(1), heading, headingLevel, true);
            last = m.end();
        }
        if (last < text.length()) {
            createRun(p, text.substring(last), heading, headingLevel, false);
        }
    }

    private void createRun(XWPFParagraph p, String text, boolean heading, int headingLevel, boolean bold) {
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setUnderline(UnderlinePatterns.NONE);

        // Font sizes per RFC §4.2.
        int halfPoints;
        if (heading) {
            halfPoints = switch (headingLevel) {
                case 1 -> 40; // 20pt
                case 2 -> 32; // 16pt
                default -> 28; // 14pt
            };
            run.setBold(true);
        } else {
            halfPoints = 24; // 12pt
            run.setBold(bold);
        }
        run.setFontSize(halfPoints / 2);

        // Latin + East-Asian fonts. Each run is freshly created, so we always
        // append a brand new <w:rFonts/> child rather than try to reuse one.
        CTRPr rPr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = rPr.sizeOfRFontsArray() > 0 ? rPr.getRFontsArray(0) : rPr.addNewRFonts();
        fonts.setAscii(LATIN_FONT);
        fonts.setHAnsi(LATIN_FONT);
        fonts.setCs(LATIN_FONT);
        fonts.setEastAsia(heading ? CJK_HEADING_FONT : CJK_BODY_FONT);
    }

    // ==================== tables ====================

    private void renderTable(XWPFDocument doc, List<String> tableLines) {
        List<String[]> rows = new ArrayList<>(tableLines.size());
        for (int idx = 0; idx < tableLines.size(); idx++) {
            if (idx == 1) continue; // skip the |---|---| separator
            rows.add(parseTableRow(tableLines.get(idx)));
        }
        if (rows.isEmpty()) return;

        int cols = 0;
        for (String[] row : rows) cols = Math.max(cols, row.length);

        XWPFTable table = doc.createTable(rows.size(), cols);
        styleTableBorders(table);

        for (int r = 0; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            XWPFTableRow row = table.getRow(r);
            for (int c = 0; c < cols; c++) {
                XWPFTableCell cell = row.getCell(c);
                String value = c < cells.length ? cells[c] : "";

                // POI auto-creates an empty paragraph in each new cell — reuse it.
                cell.removeParagraph(0);
                XWPFParagraph p = cell.addParagraph();
                renderInline(p, value, false, 0);

                if (r == 0) {
                    shadeHeaderCell(cell);
                    for (XWPFRun run : p.getRuns()) {
                        run.setBold(true);
                    }
                }
            }
        }
    }

    /**
     * Split a pipe-table row into cells, honouring GFM's pipe escape.
     *
     * <p>A pipe that belongs to a cell is written {@code \|} — it is content,
     * not a column boundary, so a row like {@code | a \| b | x |} has two
     * cells. Splitting on every pipe produced three, which widened the whole
     * table by a column and left the backslash visible in the cell text.
     */
    private String[] parseTableRow(String line) {
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
        return cells.toArray(new String[0]);
    }

    /** True when the character at {@code idx} is preceded by an odd run of backslashes. */
    private static boolean isEscaped(String line, int idx) {
        int backslashes = 0;
        for (int i = idx - 1; i >= 0 && line.charAt(i) == '\\'; i--) backslashes++;
        return backslashes % 2 == 1;
    }

    private void styleTableBorders(XWPFTable table) {
        CTTblPr tblPr = table.getCTTbl().getTblPr() != null
                ? table.getCTTbl().getTblPr()
                : table.getCTTbl().addNewTblPr();
        CTTblBorders borders = tblPr.isSetTblBorders() ? tblPr.getTblBorders() : tblPr.addNewTblBorders();
        applyBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop());
        applyBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom());
        applyBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft());
        applyBorder(borders.isSetRight() ? borders.getRight() : borders.addNewRight());
        applyBorder(borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH());
        applyBorder(borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV());
    }

    private void applyBorder(CTBorder border) {
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(4));
        border.setColor("999999");
    }

    private void shadeHeaderCell(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc().getTcPr() != null ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill("E0E0E0");
    }

    // ==================== utils ====================

    private List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                int end = i;
                if (end > start && text.charAt(end - 1) == '\r') end--;
                out.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start <= text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }
}
