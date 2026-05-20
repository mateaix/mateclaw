package vip.mate.channel.feishu.cards.tool_guard;

import vip.mate.channel.cards.CardOversizedException;
import vip.mate.channel.feishu.cards.FeishuCardRenderer;
import vip.mate.channel.notification.ApprovalNotice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build the Feishu Schema-2.0 button-card payload from an
 * {@link ApprovalNotice}.
 *
 * <p>Card structure (Schema 2.0 interactive card):
 * <pre>
 * {
 *   "schema": "2.0",
 *   "header": {"title": {"tag": "plain_text", "content": "🛡️ 工具审批"}, "template": "orange"},
 *   "body": {
 *     "elements": [
 *       {"tag": "markdown", "content": "&lt;tool / risk / args summary&gt;"},
 *       {"tag": "action", "actions": [
 *         {"tag": "button", "text": {...}, "type": "primary", "value": &lt;approve payload&gt;},
 *         {"tag": "button", "text": {...}, "type": "danger",  "value": &lt;deny payload&gt;}
 *       ]}
 *     ]
 *   }
 * }
 * </pre>
 *
 * <p>If either button.value would exceed the size ceiling, the encoder
 * throws {@link CardOversizedException} and the calling adapter falls
 * back to the {@link vip.mate.channel.AbstractChannelAdapter} text-
 * approval path.
 */
public class ToolGuardCardRenderer implements FeishuCardRenderer {

    private final ToolGuardButtonValue buttonValue;

    public ToolGuardCardRenderer(ToolGuardButtonValue buttonValue) {
        this.buttonValue = buttonValue;
    }

    @Override
    public Map<String, Object> render(ApprovalNotice notice) throws CardOversizedException {
        String pendingId = notice.pendingId();
        String toolName = nullSafe(notice.toolName(), "tool");
        String severity = nullSafe(notice.maxSeverity(), "MEDIUM");

        // Encode button values FIRST so a size overflow throws before
        // we build any cosmetic body.
        Map<String, Object> approveValue = buttonValue.encode(
                ToolGuardButtonValue.Action.APPROVE, pendingId, toolName, severity);
        Map<String, Object> denyValue = buttonValue.encode(
                ToolGuardButtonValue.Action.DENY, pendingId, toolName, severity);

        // Header
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("tag", "plain_text");
        title.put("content", "🛡️ 工具审批");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", title);
        header.put("template", severityToTemplate(severity));

        // Markdown summary
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("tag", "markdown");
        markdown.put("content", buildSummaryMarkdown(notice, toolName, severity));

        // Buttons
        Map<String, Object> approveBtn = new LinkedHashMap<>();
        approveBtn.put("tag", "button");
        approveBtn.put("text", plainText("批准"));
        approveBtn.put("type", "primary");
        approveBtn.put("value", approveValue);

        Map<String, Object> denyBtn = new LinkedHashMap<>();
        denyBtn.put("tag", "button");
        denyBtn.put("text", plainText("拒绝"));
        denyBtn.put("type", "danger");
        denyBtn.put("value", denyValue);

        Map<String, Object> actionElement = new LinkedHashMap<>();
        actionElement.put("tag", "action");
        actionElement.put("actions", List.of(approveBtn, denyBtn));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("elements", List.of(markdown, actionElement));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("header", header);
        card.put("body", body);
        return card;
    }

    /**
     * Build the resolved-state card (same task_id semantics — Feishu
     * cards are updated in-place by message_id; no task_id needed).
     *
     * @param title       headline like "✅ 已批准 by 张三"
     * @param desc        optional detail line
     * @param template    "green" / "red" / "grey" / "blue" — header colour
     */
    public static Map<String, Object> buildResolvedCard(String title, String desc, String template) {
        Map<String, Object> titleObj = new LinkedHashMap<>();
        titleObj.put("tag", "plain_text");
        titleObj.put("content", title == null ? "" : title);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", titleObj);
        header.put("template", template == null ? "grey" : template);

        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("tag", "markdown");
        markdown.put("content", desc == null ? "" : desc);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("elements", List.of(markdown));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("header", header);
        card.put("body", body);
        return card;
    }

    private static String buildSummaryMarkdown(ApprovalNotice notice, String toolName, String severity) {
        StringBuilder sb = new StringBuilder();
        sb.append("**工具**: `").append(toolName).append("`\n");
        sb.append("**风险等级**: ").append(severityLabel(severity)).append("\n");
        if (notice.summary() != null && !notice.summary().isBlank()) {
            sb.append("**摘要**: ").append(notice.summary()).append("\n");
        }
        if (notice.argumentsPreview() != null && !notice.argumentsPreview().isBlank()) {
            String args = notice.argumentsPreview();
            if (args.length() > 200) args = args.substring(0, 200) + "…";
            sb.append("**参数**: `").append(args).append("`");
        }
        return sb.toString();
    }

    private static String severityLabel(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> "🔴 CRITICAL";
            case "HIGH" -> "🟠 HIGH";
            case "MEDIUM" -> "🟡 MEDIUM";
            case "LOW" -> "🔵 LOW";
            case "INFO" -> "⚪ INFO";
            default -> severity;
        };
    }

    private static String severityToTemplate(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL", "HIGH" -> "orange";
            case "MEDIUM" -> "yellow";
            case "LOW", "INFO" -> "blue";
            default -> "orange";
        };
    }

    private static Map<String, Object> plainText(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "plain_text");
        m.put("content", content);
        return m;
    }

    private static String nullSafe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
