package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-047 P1: Unit tests for WikiBatchCreateParser covering 7 edge cases.
 */
class WikiBatchCreateParserTest {

    private final WikiBatchCreateParser parser = new WikiBatchCreateParser();

    @Test
    @DisplayName("Parse two well-formed FILE blocks")
    void twoWellFormedBlocks() {
        String input = """
                ---FILE: slug-one---
                {"slug":"slug-one","title":"One","summary":"s","content":"c"}
                ---END FILE---

                ---FILE: slug-two---
                {"slug":"slug-two","title":"Two","summary":"s2","content":"c2"}
                ---END FILE---
                """;
        List<WikiBatchCreateParser.ParsedPage> pages = parser.parse(input);
        assertEquals(2, pages.size());
        assertEquals("slug-one", pages.get(0).slug());
        assertEquals("slug-two", pages.get(1).slug());
        assertTrue(pages.get(0).rawJson().contains("\"title\":\"One\""));
    }

    @Test
    @DisplayName("Preamble text before first FILE block is ignored")
    void preambleIgnored() {
        String input = """
                Here are the pages I generated for you:

                ---FILE: alpha---
                {"slug":"alpha","title":"Alpha","summary":"s","content":"c"}
                ---END FILE---
                """;
        List<WikiBatchCreateParser.ParsedPage> pages = parser.parse(input);
        assertEquals(1, pages.size());
        assertEquals("alpha", pages.get(0).slug());
    }

    @Test
    @DisplayName("Null or blank response returns empty list")
    void nullAndBlankInput() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("   \n  ").isEmpty());
    }

    @Test
    @DisplayName("Unclosed FILE block at EOF is discarded")
    void unclosedBlockDiscarded() {
        String input = """
                ---FILE: dangling---
                {"slug":"dangling","title":"D","summary":"s","content":"c"}
                """;
        List<WikiBatchCreateParser.ParsedPage> pages = parser.parse(input);
        assertTrue(pages.isEmpty());
    }

    @Test
    @DisplayName("Missing END FILE recovers at next FILE start")
    void missingEndFileRecovery() {
        String input = """
                ---FILE: bad---
                {"slug":"bad","title":"Bad","summary":"s","content":"c"}
                ---FILE: good---
                {"slug":"good","title":"Good","summary":"s","content":"c"}
                ---END FILE---
                """;
        List<WikiBatchCreateParser.ParsedPage> pages = parser.parse(input);
        // "bad" block has no END FILE before next FILE — discarded; "good" is captured
        assertEquals(1, pages.size());
        assertEquals("good", pages.get(0).slug());
    }

    @Test
    @DisplayName("Blank JSON body inside FILE block is skipped")
    void blankJsonBodySkipped() {
        String input = """
                ---FILE: empty-body---

                ---END FILE---

                ---FILE: ok---
                {"slug":"ok","title":"Ok","summary":"s","content":"c"}
                ---END FILE---
                """;
        List<WikiBatchCreateParser.ParsedPage> pages = parser.parse(input);
        assertEquals(1, pages.size());
        assertEquals("ok", pages.get(0).slug());
    }

    @Test
    @DisplayName("Slug extracted correctly from FILE header with trailing ---")
    void slugExtractionFormats() {
        String input = """
                ---FILE: my-cool-slug---
                {"slug":"my-cool-slug","title":"T","summary":"s","content":"c"}
                ---END FILE---
                """;
        List<WikiBatchCreateParser.ParsedPage> pages = parser.parse(input);
        assertEquals(1, pages.size());
        assertEquals("my-cool-slug", pages.get(0).slug());
    }
}
