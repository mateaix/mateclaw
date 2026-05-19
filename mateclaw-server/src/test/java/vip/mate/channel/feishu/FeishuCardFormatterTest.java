package vip.mate.channel.feishu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.channel.feishu.FeishuCardFormatter.ContentFormat.*;

class FeishuCardFormatterTest {

    // ==================== detect() ====================

    @Test
    void detect_nullAndBlank_returnsPlainText() {
        assertEquals(PLAIN_TEXT, FeishuCardFormatter.detect(null));
        assertEquals(PLAIN_TEXT, FeishuCardFormatter.detect(""));
        assertEquals(PLAIN_TEXT, FeishuCardFormatter.detect("   "));
    }

    @Test
    void detect_nonEmptyJsonObject_returnsJson() {
        assertEquals(JSON, FeishuCardFormatter.detect("{\"key\": \"value\"}"));
    }

    @Test
    void detect_jsonArrayOfObjects_returnsJson() {
        assertEquals(JSON, FeishuCardFormatter.detect("[{\"a\": 1, \"b\": 2}]"));
    }

    @Test
    void detect_emptyJsonObject_doesNotReturnJson() {
        assertNotEquals(JSON, FeishuCardFormatter.detect("{}"));
    }

    @Test
    void detect_primitiveArray_doesNotReturnJson() {
        assertNotEquals(JSON, FeishuCardFormatter.detect("[1, 2, 3]"));
        assertNotEquals(JSON, FeishuCardFormatter.detect("[\"a\", \"b\"]"));
    }

    @Test
    void detect_invalidJsonStartingWithBrace_doesNotReturnJson() {
        assertNotEquals(JSON, FeishuCardFormatter.detect("{invalid json}"));
        assertNotEquals(JSON, FeishuCardFormatter.detect("[引用消息: 你好]"));
    }

    @Test
    void detect_jsonOverSizeLimit_doesNotReturnJson() {
        String big = "{\"k\":\"" + "x".repeat(32_000) + "\"}";
        assertNotEquals(JSON, FeishuCardFormatter.detect(big));
    }

    @Test
    void detect_codeBlock_returnsMarkdown() {
        assertEquals(MARKDOWN, FeishuCardFormatter.detect("看这段代码：\n```java\nint x = 1;\n```"));
    }

    @Test
    void detect_h2Header_returnsMarkdown() {
        assertEquals(MARKDOWN, FeishuCardFormatter.detect("## 标题\n正文内容"));
    }

    @Test
    void detect_h1Header_returnsMarkdown() {
        assertEquals(MARKDOWN, FeishuCardFormatter.detect("# 一级标题"));
    }

    @Test
    void detect_tableSeparatorRow_returnsMarkdown() {
        assertEquals(MARKDOWN, FeishuCardFormatter.detect("| A | B |\n|---|---|\n| 1 | 2 |"));
    }

    @Test
    void detect_hrTripleDash_doesNotReturnMarkdown() {
        assertEquals(PLAIN_TEXT, FeishuCardFormatter.detect("---"));
    }

    @Test
    void detect_twoBulletItems_returnsMarkdown() {
        assertEquals(MARKDOWN, FeishuCardFormatter.detect("- 第一条\n- 第二条"));
    }

    @Test
    void detect_oneBulletItem_doesNotReturnMarkdown() {
        assertNotEquals(MARKDOWN, FeishuCardFormatter.detect("- 只有一条"));
    }

    @Test
    void detect_inlineDashNotBullet_doesNotReturnMarkdown() {
        String text = "价格 - 折扣 = 净价\n成本 - 税 = 实际";
        assertNotEquals(MARKDOWN, FeishuCardFormatter.detect(text));
    }

    @Test
    void detect_longTextWithDoubleNewline_returnsLongText() {
        String text = "x".repeat(150) + "\n\n" + "y".repeat(155);
        assertEquals(LONG_TEXT, FeishuCardFormatter.detect(text));
    }

    @Test
    void detect_longTextWithoutDoubleNewline_returnsPlainText() {
        assertEquals(PLAIN_TEXT, FeishuCardFormatter.detect("x".repeat(400)));
    }

    @Test
    void detect_shortPlainText_returnsPlainText() {
        assertEquals(PLAIN_TEXT, FeishuCardFormatter.detect("好的，明白了。"));
    }
}
