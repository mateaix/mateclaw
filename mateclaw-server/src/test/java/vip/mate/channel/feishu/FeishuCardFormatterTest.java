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
        var table = elems.get(0);
        assertEquals("table", table.get("tag"));

        var columns = (java.util.List<java.util.Map<String, Object>>) table.get("columns");
        assertEquals(2, columns.size());
        assertEquals("a", columns.get(0).get("name"));
        assertEquals("b", columns.get(1).get("name"));

        var rows = (java.util.List<java.util.Map<String, Object>>) table.get("rows");
        assertEquals(2, rows.size());
        assertEquals("1", rows.get(0).get("a"));
        assertEquals("2", rows.get(0).get("b"));
        assertEquals("3", rows.get(1).get("a"));
        assertEquals("4", rows.get(1).get("b"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void render_jsonArrayManyColumns_usesDivPerItem() {
        // >4 字段 → 列表卡片（每条 item 一个 div）
        var card = FeishuCardFormatter.render(
                "[{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5}]", JSON);

        var body = (java.util.Map<String, Object>) card.get("body");
        var elems = (java.util.List<java.util.Map<String, Object>>) body.get("elements");
        var div = elems.get(0);
        assertEquals("div", div.get("tag"));

        var text = (java.util.Map<String, Object>) div.get("text");
        assertEquals("lark_md", text.get("tag"));
        String content = (String) text.get("content");
        assertTrue(content.contains("**a**:"), "content should contain **a**: field");
        assertTrue(content.contains("**b**:"), "content should contain **b**: field");
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

    // ==================== detect() — Markdown 内嵌 JSON ====================

    @Test
    void detect_markdownWithJsonObjectCodeBlock_returnsJson() {
        String md = "上海今天天气如下：\n\n```json\n{\"city\":\"上海\",\"temp\":24}\n```";
        assertEquals(JSON, FeishuCardFormatter.detect(md));
    }

    @Test
    void detect_markdownWithBareCodeBlockContainingJson_returnsJson() {
        // 无 json 标注的代码块，内容是 JSON 对象也识别
        String md = "结果：\n```\n{\"status\":\"ok\"}\n```";
        assertEquals(JSON, FeishuCardFormatter.detect(md));
    }

    @Test
    void detect_markdownWithJsonArrayCodeBlock_returnsJson() {
        String md = "列表：\n```json\n[{\"a\":1},{\"a\":2}]\n```";
        assertEquals(JSON, FeishuCardFormatter.detect(md));
    }

    @Test
    void detect_markdownWithPrimitiveArrayCodeBlock_returnsMarkdown() {
        // 原始类型数组不识别为 JSON
        String md = "数据：\n```json\n[1,2,3]\n```";
        assertEquals(MARKDOWN, FeishuCardFormatter.detect(md));
    }

    @Test
    void detect_markdownWithNonJsonCodeBlock_returnsMarkdown() {
        // Python 代码块不识别为 JSON
        String md = "代码：\n```python\nprint('hello')\n```";
        assertEquals(MARKDOWN, FeishuCardFormatter.detect(md));
    }

    @Test
    void detect_markdownWithEmptyJsonObjectCodeBlock_returnsMarkdown() {
        // 空对象 {} 不识别为 JSON
        String md = "空：\n```json\n{}\n```";
        assertEquals(MARKDOWN, FeishuCardFormatter.detect(md));
    }

    // ==================== render() — Markdown 内嵌 JSON ====================

    @Test
    @SuppressWarnings("unchecked")
    void render_markdownWithJsonCodeBlock_rendersAsSummaryCard() {
        String md = "天气结果：\n```json\n{\"city\":\"上海\",\"temp\":24}\n```";
        var card = FeishuCardFormatter.render(md, JSON);

        assertEquals("2.0", card.get("schema"));
        assertNull(card.get("header")); // JSON object card has no header
        var body = (java.util.Map<String, Object>) card.get("body");
        var elems = (java.util.List<java.util.Map<String, Object>>) body.get("elements");
        assertEquals(2, elems.size()); // 2 fields → 2 column_sets
        assertEquals("column_set", elems.get(0).get("tag"));
    }

    @Test
    void detect_markdownWithJsonBlockSecond_returnsJson() {
        // JSON 对象代码块在原始数组块后面，应该仍能识别
        // 原始数组 [1,2,3] 不是有效 JSON，但 JSON 对象 {"ok":true} 是
        String md = "示例：\n```\n[1,2,3]\n```\n\n结果：\n```json\n{\"ok\":true,\"count\":5}\n```";
        assertEquals(JSON, FeishuCardFormatter.detect(md));
    }
}
