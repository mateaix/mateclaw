package vip.mate.channel.feishu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeishuTextSplitTest {

    @Test
    @DisplayName("short content returns single-element list unchanged")
    void shortContent() {
        List<String> chunks = FeishuChannelAdapter.splitTextForFeishu("hello world", 100);
        assertEquals(List.of("hello world"), chunks);
    }

    @Test
    @DisplayName("null/empty returns empty list")
    void nullEmpty() {
        assertTrue(FeishuChannelAdapter.splitTextForFeishu(null, 100).isEmpty());
        assertTrue(FeishuChannelAdapter.splitTextForFeishu("", 100).isEmpty());
    }

    @Test
    @DisplayName("split prefers paragraph (\\n\\n) boundary over hard cut")
    void prefersParagraphBoundary() {
        String content = "first paragraph here\n\nsecond paragraph here";
        // maxChars chosen so the paragraph boundary lands in the second half
        List<String> chunks = FeishuChannelAdapter.splitTextForFeishu(content, 30);
        assertEquals(2, chunks.size());
        assertEquals("first paragraph here\n\n", chunks.get(0));
        assertEquals("second paragraph here", chunks.get(1));
    }

    @Test
    @DisplayName("split falls through to line boundary when no paragraph break")
    void fallsThroughToLine() {
        String content = "line1 with content\nline2 with content\nline3 with content";
        List<String> chunks = FeishuChannelAdapter.splitTextForFeishu(content, 25);
        assertTrue(chunks.size() >= 2);
        // each chunk should end at \n boundary or be the final chunk
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertTrue(chunks.get(i).endsWith("\n"),
                    "Non-final chunk should end at line boundary: '" + chunks.get(i) + "'");
        }
        assertEquals(content, String.join("", chunks),
                "Concatenation must reconstruct the original");
    }

    @Test
    @DisplayName("oversized single line falls through to whitespace boundary")
    void fallsThroughToWhitespace() {
        String content = "word ".repeat(200); // 1000 chars, no \n
        List<String> chunks = FeishuChannelAdapter.splitTextForFeishu(content, 100);
        assertTrue(chunks.size() >= 10);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 100,
                    "Each chunk must be within limit, got " + chunk.length());
        }
        assertEquals(content, String.join("", chunks));
    }

    @Test
    @DisplayName("zero-boundary content (no spaces, no newlines) hard-cuts")
    void hardCutWhenNoBoundary() {
        String content = "x".repeat(1000);
        List<String> chunks = FeishuChannelAdapter.splitTextForFeishu(content, 250);
        assertEquals(4, chunks.size());
        for (String chunk : chunks) {
            assertEquals(250, chunk.length());
        }
        assertEquals(content, String.join("", chunks));
    }

    @Test
    @DisplayName("default 4000-char limit holds for very long markdown answer")
    void realisticLongAnswer() {
        // Simulate a 12K-char LLM answer with paragraph breaks every ~400 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("段落 ").append(i).append("：")
              .append("这里是一些内容，模拟一个真实的长回答。".repeat(10))
              .append("\n\n");
        }
        String content = sb.toString();

        List<String> chunks = FeishuChannelAdapter.splitTextForFeishu(
                content, FeishuChannelAdapter.MAX_TEXT_MESSAGE_CHARS);
        assertTrue(chunks.size() >= 2,
                "12K-char answer should split into multiple chunks, got " + chunks.size());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= FeishuChannelAdapter.MAX_TEXT_MESSAGE_CHARS,
                    "Chunk exceeds limit: " + chunk.length());
        }
        assertEquals(content, String.join("", chunks),
                "Reconstruction lossless");
    }
}
