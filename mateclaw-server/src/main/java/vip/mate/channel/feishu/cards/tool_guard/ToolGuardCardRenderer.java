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
        header.put("template", severityToTemplate(severity));
        header.put("title", title);

        // Markdown summary
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("tag", "markdown");
        markdown.put("content", buildSummaryMarkdown(notice, toolName, severity));

        // Schema 1.0 button row — {tag:"action", actions:[buttons]}.
        // We use Schema 1.0 throughout (not Schema 2.0) so the callback
        // response can update this same card without a schema-version
        // mismatch error. Schema 2.0 is supported by im/v1/message.create
        // BUT the callback response validator only accepts Schema 1.0
        // inline (type="raw") — once we commit to Schema 1.0 here the
        // resolved-state card update lands cleanly. QwenPaw's
        // production Feishu integration uses the same Schema 1.0 path.
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

        Map<String, Object> actionRow = new LinkedHashMap<>();
        actionRow.put("tag", "action");
        actionRow.put("actions", List.of(approveBtn, denyBtn));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("wide_screen_mode", true);

        // Schema 1.0 layout — elements at root, no "schema" / "body" wrapper.
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", config);
        card.put("header", header);
        card.put("elements", List.of(markdown, actionRow));
        return card;
    }

    /**
     * Build the resolved-state card for the {@code
     * P2CardActionTriggerResponse.card} payload — <b>Schema 1.0</b>
     * inline format ({@code config / header / elements} all at root,
     * no {@code "schema"} field, no {@code "body"} nesting).
     *
     * <p><b>Why Schema 1.0 here</b>: the Feishu callback-response
     * validator is the legacy validator and rejects Schema 2.0 cards
     * with error code 200672 "卡片内容格式错误". This is different from
     * {@code im/v1/message.create msg_type=interactive} and
     * {@code cardkit/v1 card.create}, both of which DO accept Schema
     * 2.0. So we keep the original approval card (sent via message
     * create) in Schema 2.0 for the column_set button layout, but the
     * resolved-state update has to be Schema 1.0. QwenPaw's production
     * Feishu integration uses the same split.
     *
     * <p>Caller passes the resulting Map to a {@code CallBackCard}
     * with {@code type="raw"} (NOT {@code card_json}).
     *
     * @param title    headline like "✅ 已批准 by 张三"
     * @param desc     optional detail line (markdown)
     * @param template "green" / "red" / "grey" / "blue" — header colour
     */
    public static Map<String, Object> buildResolvedCard(String title, String desc, String template) {
        Map<String, Object> titleObj = new LinkedHashMap<>();
        titleObj.put("tag", "plain_text");
        titleObj.put("content", title == null ? "" : title);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("template", template == null ? "grey" : template);
        header.put("title", titleObj);

        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("tag", "markdown");
        markdown.put("content", desc == null ? "" : desc);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("wide_screen_mode", true);

        // Schema 1.0 layout — elements at root, no schema/body wrapper.
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", config);
        card.put("header", header);
        card.put("elements", List.of(markdown));
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
