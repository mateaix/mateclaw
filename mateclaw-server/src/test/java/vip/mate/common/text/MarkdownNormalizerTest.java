package vip.mate.common.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarkdownNormalizer} 单元测试。
 */
class MarkdownNormalizerTest {

    @Test
    @DisplayName("null / 空串原样返回")
    void nullAndEmpty() {
        assertNull(MarkdownNormalizer.normalize(null));
        assertEquals("", MarkdownNormalizer.normalize(""));
    }

    @Test
    @DisplayName("ATX 标题缺空格补空格")
    void headingMissingSpace() {
        assertEquals("## 二、美股", MarkdownNormalizer.normalize("##二、美股"));
        assertEquals("### 已完成 ✅", MarkdownNormalizer.normalize("###已完成 ✅"));
    }

    @Test
    @DisplayName("已规范标题保持不变")
    void compliantHeadingUnchanged() {
        assertEquals("## 一、核心结论", MarkdownNormalizer.normalize("## 一、核心结论"));
    }

    @Test
    @DisplayName("数字开头的 #5 / #1 视为引用，不补空格")
    void headingDigitGuard() {
        assertEquals("#5 bolt", MarkdownNormalizer.normalize("#5 bolt"));
        assertEquals("#1 优先级", MarkdownNormalizer.normalize("#1 优先级"));
    }

    @Test
    @DisplayName("--- 与后续内容粘连时拆行")
    void thematicBreakGlued() {
        assertEquals("---\n\n# 全球", MarkdownNormalizer.normalize("---#全球"));
        assertEquals("---\n\n## 二、美股", MarkdownNormalizer.normalize("---##二、美股"));
    }

    @Test
    @DisplayName("--- 粘连在行内容之后（mid-line）且后接标题时拆行")
    void thematicBreakGluedMidLine() {
        assertEquals(
                "*来源：雪球 · 2026-06-01*\n\n---\n\n### 二、供应链与产能",
                MarkdownNormalizer.normalize("*来源：雪球 · 2026-06-01*---### 二、供应链与产能"));
    }

    @Test
    @DisplayName("行内容 + --- + 标题 + 表格四重粘连全部拆开")
    void midLineHrHeadingTableChain() {
        String input = "- 数据中心收入逾 **90%**---### 综合判断🔍| 维度 |信号 | 评级|\n"
                + "|------|------|\n"
                + "| 产品 | 强 |";
        String out = MarkdownNormalizer.normalize(input);
        assertTrue(out.contains("- 数据中心收入逾 **90%**"), "前缀正文应保留");
        assertTrue(out.contains("\n---\n"), "--- 应独占一行");
        assertTrue(out.contains("### 综合判断🔍"), "标题应从表格拆出");
        assertTrue(out.contains("| 维度 | 信号 | 评级 |"), "表头应对齐");
        assertFalse(out.contains("**90%**---"), "--- 不应再粘连前缀");
        assertFalse(out.contains("🔍| 维度"), "标题不应再粘连表格");
    }

    @Test
    @DisplayName("散文中的 em-dash 风格 --- 不被误拆（无后接标题）")
    void midLineHrWithoutHeadingUntouched() {
        String input = "他停顿了一下---然后继续说。";
        assertEquals(input, MarkdownNormalizer.normalize(input));
    }

    @Test
    @DisplayName("表格单元格与分隔行对齐")
    void tableCellAndSeparator() {
        String input = "|指数 |涨跌| 解读 |\n"
                + "|---| --- | --- |\n"
                + "| 道琼斯 | +1.73% | 强势 |";
        String expected = "| 指数 | 涨跌 | 解读 |\n"
                + "| --- | --- | --- |\n"
                + "| 道琼斯 | +1.73% | 强势 |";
        assertEquals(expected, MarkdownNormalizer.normalize(input));
    }

    @Test
    @DisplayName("分隔行保留对齐冒号")
    void separatorAlignmentColons() {
        String input = "| a | b | c |\n"
                + "|:--|:-:|--:|\n"
                + "| 1 | 2 | 3 |";
        String expected = "| a | b | c |\n"
                + "| :--- | :---: | ---: |\n"
                + "| 1 | 2 | 3 |";
        assertEquals(expected, MarkdownNormalizer.normalize(input));
    }

    @Test
    @DisplayName("标题与表格粘连时拆行")
    void headingGluedToTable() {
        String input = "## 五、大宗商品：回调| 商品 |最新价 |涨跌 |\n"
                + "| --- | --- | ---|";
        String expected = "## 五、大宗商品：回调\n"
                + "\n"
                + "| 商品 | 最新价 | 涨跌 |\n"
                + "| --- | --- | --- |";
        assertEquals(expected, MarkdownNormalizer.normalize(input));
    }

    @Test
    @DisplayName("标题与表格之间补空行")
    void blankLineBetweenHeadingAndTable() {
        String input = "## 表格\n"
                + "| a | b |\n"
                + "| --- | --- |\n"
                + "| 1 | 2 |";
        String expected = "## 表格\n"
                + "\n"
                + "| a | b |\n"
                + "| --- | --- |\n"
                + "| 1 | 2 |";
        assertEquals(expected, MarkdownNormalizer.normalize(input));
    }

    @Test
    @DisplayName("代码块内部原样保留，不被规范化")
    void codeFenceProtected() {
        String input = "```python\n"
                + "##notheading\n"
                + "x = a|b|c\n"
                + "---glued\n"
                + "```";
        assertEquals(input, MarkdownNormalizer.normalize(input));
    }

    @Test
    @DisplayName("散文中的散落管道符不被当作表格")
    void prosePipesUntouched() {
        String input = "这是 a | b | c 的一句话。\n另一行普通文本。";
        assertEquals(input, MarkdownNormalizer.normalize(input));
    }

    @Test
    @DisplayName("幂等：规范化两次结果一致")
    void idempotent() {
        String sample = "---#全球资产行情整体分析\n"
                + "##二、美股\n"
                + "|指数 |涨跌| 解读 |\n"
                + "|---| --- | --- |\n"
                + "| 道琼斯 | +1.73% | 强势 |\n"
                + "## 五、大宗商品：回调| 商品 |最新价 |\n"
                + "| --- | --- |\n"
                + "```\n"
                + "##code\n"
                + "|x|y|\n"
                + "```";
        String once = MarkdownNormalizer.normalize(sample);
        String twice = MarkdownNormalizer.normalize(once);
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("综合样本：关键缺陷被修复")
    void realWorldSampleProperties() {
        String sample = "---#全球资产行情整体分析\n"
                + "---##二、美股：道指强、纳指弱\n"
                + "|指数 |涨跌| 解读 |\n"
                + "|---| --- | --- |\n"
                + "| 道琼斯 | +1.73% | 强势 |";
        String out = MarkdownNormalizer.normalize(sample);

        assertFalse(out.contains("---#"), "--- 不应再与标题粘连");
        assertTrue(out.contains("# 全球资产行情整体分析"), "一级标题应补空格");
        assertTrue(out.contains("## 二、美股"), "二级标题应补空格");
        assertTrue(out.contains("| 指数 | 涨跌 | 解读 |"), "表头应对齐");
        assertTrue(out.contains("| --- | --- | --- |"), "分隔行应规范");
    }
}
