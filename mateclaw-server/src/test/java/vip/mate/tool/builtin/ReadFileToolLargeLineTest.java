package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.i18n.I18nService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for reading oversized content with {@link ReadFileTool}.
 * <p>
 * The original bug: a file whose only line exceeds the output budget returned
 * empty content with readLines=0 and a continuation hint (startLine) that never
 * advanced — an infinite retry loop. These tests assert that the tool always
 * makes progress, clearly flags truncation, and lets a caller page through both
 * a very long single line (via nextStartColumn) and subsequent normal lines
 * (via nextStartLine).
 */
class ReadFileToolLargeLineTest {

    private ReadFileTool tool;

    @BeforeEach
    void setUp() {
        // Default answer echoes msg(key, ...) back as the key, so assertions stay
        // locale-agnostic and the answer covers every varargs arity uniformly.
        I18nService i18n = mock(I18nService.class, inv ->
                "msg".equals(inv.getMethod().getName()) ? inv.getArgument(0) : null);
        tool = new ReadFileTool(i18n);
        // Ensure no workspace boundary is active so absolute temp paths validate.
        ToolExecutionContext.clear();
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    /** Build a single-line JSON array of {@code n} string elements. */
    private static String oneLineJsonArray(int n) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) json.append(',');
            json.append("\"item-").append(i).append("\"");
        }
        return json.append(']').toString();
    }

    /** Strip the "%6d\t" line-number prefixes and the truncation marker from content. */
    private static String stripDecorations(String content) {
        StringBuilder out = new StringBuilder();
        for (String l : content.split("\n", -1)) {
            if (l.isEmpty()) continue;
            int tab = l.indexOf('\t');
            String body = tab >= 0 ? l.substring(tab + 1) : l;
            int marker = body.indexOf("tool.read_file.line_truncated_marker");
            if (marker >= 0) body = body.substring(0, marker);
            out.append(body);
        }
        return out.toString();
    }

    @Test
    @DisplayName("single line larger than the 30KB budget returns clipped content, not empty + infinite loop")
    void singleOversizedLine_returnsClippedContent(@TempDir Path dir) throws Exception {
        String json = oneLineJsonArray(4000); // ~40KB on one physical line
        Path file = dir.resolve("big.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);

        String raw = tool.read_file(file.toString(), null, null, null, null);
        JSONObject res = JSONUtil.parseObj(raw);

        assertFalse(res.getBool("error", false), "should not be an error result");
        assertTrue(res.getBool("truncated"), "should be marked truncated");
        assertTrue(res.getBool("lineTruncated", false), "should flag in-line truncation");
        assertEquals(1, res.getInt("readLines"), "must count the clipped line as read");
        assertTrue(res.getStr("content").length() > 1000, "content must carry the clipped line, not be empty");
        // Continuation must advance into the same line, not loop on column 1.
        assertEquals(1, res.getInt("nextStartLine"));
        assertTrue(res.getInt("nextStartColumn") > 1, "nextStartColumn must advance past the head");
    }

    @Test
    @DisplayName("a very long single line can be fully read by paging through nextStartColumn")
    void oversizedLine_pagesToCompletionViaColumn(@TempDir Path dir) throws Exception {
        String json = oneLineJsonArray(10000); // big enough to need several windows
        Path file = dir.resolve("huge.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);

        StringBuilder reassembled = new StringBuilder();
        Integer startLine = null;
        Integer startColumn = null;
        int guard = 0;
        while (true) {
            String raw = tool.read_file(file.toString(), startLine, null, startColumn, null);
            JSONObject res = JSONUtil.parseObj(raw);
            assertFalse(res.getBool("error", false), "no error while paging");
            reassembled.append(stripDecorations(res.getStr("content")));
            if (!res.getBool("truncated")) {
                break;
            }
            startLine = res.getInt("nextStartLine");
            startColumn = res.containsKey("nextStartColumn") ? res.getInt("nextStartColumn") : 1;
            assertTrue(++guard < 50, "must terminate, not loop forever");
        }
        assertEquals(json, reassembled.toString(), "paging through columns must reconstruct the whole line");
    }

    @Test
    @DisplayName("multi-line file with a huge first line still lets the caller reach later normal lines")
    void hugeFirstLine_thenNormalLines_offerNextLine(@TempDir Path dir) throws Exception {
        String first = oneLineJsonArray(4000); // oversized line 1
        Path file = dir.resolve("mixed.txt");
        Files.writeString(file, first + "\nsecond-line\nthird-line\n", StandardCharsets.UTF_8);

        // First read clips line 1 and must point both at the line's tail and the next line.
        String raw = tool.read_file(file.toString(), null, null, null, null);
        JSONObject res = JSONUtil.parseObj(raw);
        assertTrue(res.getBool("truncated"));
        assertTrue(res.getBool("lineTruncated", false));
        assertEquals(3, res.getInt("totalLines"));

        // The caller can skip the rest of the giant line and read the normal lines.
        String raw2 = tool.read_file(file.toString(), 2, null, null, null);
        JSONObject res2 = JSONUtil.parseObj(raw2);
        assertFalse(res2.getBool("truncated"));
        assertEquals(2, res2.getInt("readLines"));
        assertTrue(res2.getStr("content").contains("second-line"));
        assertTrue(res2.getStr("content").contains("third-line"));
    }

    @Test
    @DisplayName("single-line spill-style JSON {\"stdout\":\"...\"} is windowed, not dropped")
    void spillStyleStdoutJson_isWindowed(@TempDir Path dir) throws Exception {
        String payload = "x".repeat(50 * 1024); // 50KB payload on one line
        String line = "{\"stdout\":\"" + payload + "\"}";
        Path file = dir.resolve("spill.json");
        Files.writeString(file, line, StandardCharsets.UTF_8);

        String raw = tool.read_file(file.toString(), null, null, null, null);
        JSONObject res = JSONUtil.parseObj(raw);
        assertTrue(res.getBool("truncated"));
        assertTrue(res.getBool("lineTruncated", false));
        assertTrue(res.getStr("content").contains("{\"stdout\":"), "head of the line must be present");
        assertTrue(res.getInt("nextStartColumn") > 1);
    }

    @Test
    @DisplayName("normal truncation at a line boundary advertises nextStartLine for continuation")
    void manyNormalLines_truncateAtLineBoundary(@TempDir Path dir) throws Exception {
        // 2000 lines of ~50 chars each well exceeds the 30KB budget but no single
        // line is oversized, so truncation must happen at a clean line boundary.
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 2000; i++) {
            sb.append("line-").append(i).append("-").append("y".repeat(40)).append('\n');
        }
        Path file = dir.resolve("many.txt");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        String raw = tool.read_file(file.toString(), null, null, null, null);
        JSONObject res = JSONUtil.parseObj(raw);
        assertTrue(res.getBool("truncated"));
        assertFalse(res.getBool("lineTruncated", false), "no individual line is oversized");
        int next = res.getInt("nextStartLine");
        assertEquals(res.getInt("endLine") + 1, next, "continuation must resume right after the last read line");
        assertFalse(res.containsKey("nextStartColumn"), "line-boundary truncation has no column");
    }

    @Test
    @DisplayName("normal multi-line file reads fully without truncation")
    void smallFile_readsFully(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("small.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);

        String raw = tool.read_file(file.toString(), null, null, null, null);
        JSONObject res = JSONUtil.parseObj(raw);

        assertFalse(res.getBool("truncated"), "small file should not truncate");
        assertEquals(3, res.getInt("readLines"));
        assertTrue(res.getStr("content").contains("beta"));
    }
}
