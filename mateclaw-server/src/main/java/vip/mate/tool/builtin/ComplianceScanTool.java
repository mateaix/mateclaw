package vip.mate.tool.builtin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Built-in tool: server-side compliance scan for 公众号 / 小红书 copy. Deterministic
 * backstop for the model's skill-side self-check — catches 广告法 极限词, WeChat 诱导
 * words, 承诺收益/效果 and 医疗功效 claims. Run it before packaging or publishing.
 */
@Slf4j
@Component
public class ComplianceScanTool {

    @Tool(name = "compliance_scan", description = """
        Scan 公众号/小红书 copy (title + body) for policy violations before publishing:
        广告法 极限词 (最/第一/唯一/国家级/100%…), WeChat 诱导 words (集赞/助力/分享解锁/
        关注才能看…), 承诺收益/效果 (保本/稳赚/包过…), and 医疗功效 (治愈/抗癌…).

        Returns a report listing each hit by category and whether it's high-risk.
        High-risk hits (极限词 / 诱导 / 承诺收益) should be replaced before publishing —
        the 公众号 publish path hard-blocks a mass-send on them.
        """)
    public String compliance_scan(
            @ToolParam(description = "Text to scan (title + body)")
            String text) {
        ComplianceScanner.Result result = ComplianceScanner.scan(text);
        log.info("[ComplianceScan] hits={}, highRisk={}", result.hits().size(), result.hasHighRisk());
        return ComplianceScanner.report(result);
    }
}
