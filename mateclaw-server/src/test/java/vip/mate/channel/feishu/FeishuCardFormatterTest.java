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

    // ==================== render() ====================

    @Test
    @SuppressWarnings("unchecked")
    void render_markdown_hasSchema20AndLarkMdElement() {
        String md = "## 标题\n- 第一条\n- 第二条";
        var card = FeishuCardFormatter.render(md, MARKDOWN);

        assertEquals("2.0", card.get("schema"));
        assertNotNull(card.get("header"));
        var body = (java.util.Map<String, Object>) card.get("body");
        var elems = (java.util.List<java.util.Map<String, Object>>) body.get("elements");
        assertEquals("div", elems.get(0).get("tag"));
        var text = (java.util.Map<String, Object>) elems.get(0).get("text");
        assertEquals("lark_md", text.get("tag"));
        assertEquals(md, text.get("content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void render_longText_hasNoHeaderAndPlainTextElement() {
        String content = "x".repeat(150) + "\n\n" + "y".repeat(155);
        var card = FeishuCardFormatter.render(content, LONG_TEXT);

        assertEquals("2.0", card.get("schema"));
        assertNull(card.get("header"));
        var body = (java.util.Map<String, Object>) card.get("body");
        var elems = (java.util.List<java.util.Map<String, Object>>) body.get("elements");
        var text = (java.util.Map<String, Object>) elems.get(0).get("text");
        assertEquals("plain_text", text.get("tag"));
        assertEquals(content, text.get("content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void render_jsonObject_hasColumnSetPerField() {
        var card = FeishuCardFormatter.render("{\"name\":\"Alice\",\"score\":95}", JSON);

        assertEquals("2.0", card.get("schema"));
        assertNull(card.get("header")); // 摘要卡片无 header
        var body = (java.util.Map<String, Object>) card.get("body");
        var elems = (java.util.List<java.util.Map<String, Object>>) body.get("elements");
        assertEquals(2, elems.size()); // 2 个字段 → 2 个 column_set
        assertEquals("column_set", elems.get(0).get("tag"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void render_jsonArrayFewColumns_usesTableComponent() {
        var card = FeishuCardFormatter.render("[{\"a\":1,\"b\":2},{\"a\":3,\"b\":4}]", JSON);

        var body = (java.util.Map<String, Object>) card.get("body");
        var elems = (java.util.List<java.util.Map<String, Object>>) body.get("elements");
        assertEquals("table", elems.get(0).get("tag"));
        assertNotNull(elems.get(0).get("columns"));
        assertNotNull(elems.get(0).get("rows"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void render_jsonArrayManyColumns_usesDivPerItem() {
        // >4 字段 → 列表卡片（每条 item 一个 div）
        var card = FeishuCardFormatter.render(
                "[{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5}]", JSON);

        var body = (java.util.Map<String, Object>) card.get("body");
        var elems = (java.util.List<java.util.Map<String, Object>>) body.get("elements");
        assertEquals("div", elems.get(0).get("tag"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void render_plainText_fallsBackToLongTextLayout() {
        // PLAIN_TEXT 传入 render()（"always" 模式下会发生）→ 应渲染为 plain_text div
        var card = FeishuCardFormatter.render("简单的一句话", PLAIN_TEXT);
        var body = (java.util.Map<String, Object>) card.get("body");
        var elems = (java.util.List<java.util.Map<String, Object>>) body.get("elements");
        var text = (java.util.Map<String, Object>) elems.get(0).get("text");
        assertEquals("plain_text", text.get("tag"));
    }
}
