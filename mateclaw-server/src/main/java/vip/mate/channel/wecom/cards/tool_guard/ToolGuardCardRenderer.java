package vip.mate.channel.wecom.cards.tool_guard;

import vip.mate.channel.notification.ApprovalNotice;
import vip.mate.channel.cards.CardOversizedException;
import vip.mate.channel.wecom.cards.WeComCardRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build the WeCom {@code button_interaction} approval card payload from
 * an {@link ApprovalNotice}.
 *
 * <p>Card structure (matches the WeCom official protocol):
 * <pre>
 * {
 *   "card_type": "button_interaction",
 *   "task_id": "tg_approval_&lt;pendingId&gt;",
 *   "main_title": {
 *     "title": "🛡️ 工具审批",
 *     "desc":  "&lt;toolName&gt; | &lt;severityLabel&gt;"
 *   },
 *   "button_list": [
 *     { "text": "批准", "style": 1, "key": "&lt;encoded JSON&gt;" },
 *     { "text": "拒绝", "style": 2, "key": "&lt;encoded JSON&gt;" }
 *   ]
 * }
 * </pre>
 *
 * <p>If either button.key would exceed the 1024-byte WeCom limit, the
 * encoder throws {@link CardOversizedException} and the calling adapter
 * falls back to the abstract-class text-approval path.
 */
public class ToolGuardCardRenderer implements WeComCardRenderer {

    /**
     * Prefix on the card's {@code task_id}; the inbound dispatcher matches
     * this to find the right handler. Same value as
     * {@link ToolGuardCardKindFactory#TASK_ID_PREFIX}.
     */
    public static final String TASK_ID_PREFIX = "tg_approval_";

    private final ToolGuardButtonKey buttonKey;

    public ToolGuardCardRenderer(ToolGuardButtonKey buttonKey) {
        this.buttonKey = buttonKey;
    }

    @Override
    public Map<String, Object> render(ApprovalNotice notice) throws CardOversizedException {
        String pendingId = notice.pendingId();
        String toolName = nullSafe(notice.toolName(), "tool");
        String severity = nullSafe(notice.maxSeverity(), "MEDIUM");

        // Encode buttons first so a 1024-byte overflow throws BEFORE we
        // build any of the cosmetic card structure. Same payload shape on
        // both buttons differs only in the action wire value.
        String approveKey = buttonKey.encode(
                ToolGuardButtonKey.Action.APPROVE, pendingId, toolName, severity);
        String denyKey = buttonKey.encode(
                ToolGuardButtonKey.Action.DENY, pendingId, toolName, severity);

        // Use LinkedHashMap so the JSON serialisation order is stable —
        // helps when log-grepping outbound frames against snapshots.
        Map<String, Object> mainTitle = new LinkedHashMap<>();
        mainTitle.put("title", "🛡️ 工具审批");
        mainTitle.put("desc", buildSubtitle(toolName, severity));

        Map<String, Object> approveBtn = new LinkedHashMap<>();
        approveBtn.put("text", "批准");
        approveBtn.put("style", 1);
        approveBtn.put("key", approveKey);

        Map<String, Object> denyBtn = new LinkedHashMap<>();
        denyBtn.put("text", "拒绝");
        denyBtn.put("style", 2);
        denyBtn.put("key", denyKey);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("card_type", "button_interaction");
        card.put("task_id", TASK_ID_PREFIX + pendingId);
        card.put("main_title", mainTitle);
        card.put("button_list", List.of(approveBtn, denyBtn));
        return card;
    }

    /**
     * Build a {@code text_notice} resolved-state card to update the
     * original button card after a click. WeCom's card protocol requires
     * {@code text_notice} cards to carry a {@code card_action} of type 1
     * or 2 (type 0 is rejected by the bot endpoint), so we provide a
     * harmless project URL.
     *
     * @param taskId          same task_id as the original card so the
     *                       update targets the right message
     * @param title          one-line headline (e.g. "✅ 已批准 by 张三")
     * @param desc           optional detail line; truncated to 30 chars
     *                       to stay inside WeCom's main_title.desc limit
     */
    public static Map<String, Object> buildResolvedCard(String taskId, String title, String desc) {
        Map<String, Object> mainTitle = new LinkedHashMap<>();
        mainTitle.put("title", title == null ? "" : title);
        mainTitle.put("desc", truncate(desc == null ? "" : desc, 30));

        Map<String, Object> cardAction = new LinkedHashMap<>();
        cardAction.put("type", 1);
        cardAction.put("url", "https://mateclaw.vip");

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("card_type", "text_notice");
        card.put("task_id", taskId);
        card.put("main_title", mainTitle);
        card.put("card_action", cardAction);
        return card;
    }

    private static String buildSubtitle(String toolName, String severity) {
        // Keep the subtitle short — WeCom truncates aggressively. Format:
        // "<tool> | <severity>". Translate severity to a single-word Chinese
        // label so it reads naturally in the card.
        return toolName + " | " + severityShortLabel(severity);
    }

    private static String severityShortLabel(String severity) {
        if (severity == null) return "MEDIUM";
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> "🔴 极高";
            case "HIGH" -> "🟠 高";
            case "MEDIUM" -> "🟡 中";
            case "LOW" -> "🔵 低";
            case "INFO" -> "⚪ 提示";
            default -> severity;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        if (max <= 1) return s.substring(0, max);
        return s.substring(0, max - 1) + "…";
    }

    private static String nullSafe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
