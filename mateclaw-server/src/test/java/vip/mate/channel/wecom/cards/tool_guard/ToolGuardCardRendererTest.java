package vip.mate.channel.wecom.cards.tool_guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.notification.ApprovalNotice;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin the WeCom button_interaction approval card payload shape.
 *
 * <p>The structure is server-validated — any drift (rename a field,
 * change button_list location, omit task_id prefix) silently fails on
 * the WeCom side at runtime. These tests catch that at compile-test
 * time so renames don't ship without protocol awareness.
 */
class ToolGuardCardRendererTest {

    private final ToolGuardButtonKey buttonKey = new ToolGuardButtonKey(new ObjectMapper());
    private final ToolGuardCardRenderer renderer = new ToolGuardCardRenderer(buttonKey);

    @Test
    @DisplayName("approval card has the WeCom button_interaction shape")
    @SuppressWarnings("unchecked")
    void approvalCardShape() {
        ApprovalNotice notice = new ApprovalNotice(
                "abc12345def67890",
                "shell_exec",
                "Run system command",
                "rm -rf /tmp/cache",
                "HIGH",
                List.of(),
                "/approve abc",
                "/deny abc"
        );

        Map<String, Object> card = renderer.render(notice);

        assertEquals("button_interaction", card.get("card_type"));
        assertEquals("tg_approval_abc12345def67890", card.get("task_id"),
                "task_id must carry the tg_approval_ prefix so the inbound dispatcher can route the click");

        Map<String, Object> mainTitle = (Map<String, Object>) card.get("main_title");
        assertNotNull(mainTitle);
        assertEquals("🛡️ 工具审批", mainTitle.get("title"));
        String desc = (String) mainTitle.get("desc");
        assertTrue(desc.contains("shell_exec"), "subtitle must include tool name; got: " + desc);

        List<Map<String, Object>> buttons = (List<Map<String, Object>>) card.get("button_list");
        assertNotNull(buttons);
        assertEquals(2, buttons.size());

        Map<String, Object> approve = buttons.get(0);
        assertEquals("批准", approve.get("text"));
        assertEquals(1, approve.get("style"));
        String approveKey = (String) approve.get("key");
        ToolGuardButtonKey.Decoded a = buttonKey.decode(approveKey);
        assertNotNull(a);
        assertEquals(ToolGuardButtonKey.Action.APPROVE, a.action());
        assertEquals("abc12345def67890", a.pendingId());

        Map<String, Object> deny = buttons.get(1);
        assertEquals("拒绝", deny.get("text"));
        assertEquals(2, deny.get("style"));
        ToolGuardButtonKey.Decoded d = buttonKey.decode((String) deny.get("key"));
        assertNotNull(d);
        assertEquals(ToolGuardButtonKey.Action.DENY, d.action());
    }

    @Test
    @DisplayName("resolved card uses text_notice + carries non-zero card_action.type")
    @SuppressWarnings("unchecked")
    void resolvedCardShape() {
        Map<String, Object> resolved = ToolGuardCardRenderer.buildResolvedCard(
                "tg_approval_abc", "✅ 已批准", "Tool x 已批准 by 张三");

        assertEquals("text_notice", resolved.get("card_type"));
        assertEquals("tg_approval_abc", resolved.get("task_id"));

        Map<String, Object> cardAction = (Map<String, Object>) resolved.get("card_action");
        assertNotNull(cardAction, "WeCom rejects text_notice cards without card_action");
        assertEquals(1, cardAction.get("type"),
                "card_action.type must be 1 or 2; type=0 is rejected by the bot endpoint");
        assertNotNull(cardAction.get("url"));
    }

    @Test
    @DisplayName("resolved card truncates over-long desc to ~30 chars + ellipsis")
    @SuppressWarnings("unchecked")
    void resolvedDescTruncated() {
        String longDesc = "a".repeat(100);
        Map<String, Object> resolved = ToolGuardCardRenderer.buildResolvedCard(
                "tg_approval_x", "✅", longDesc);

        Map<String, Object> mainTitle = (Map<String, Object>) resolved.get("main_title");
        String desc = (String) mainTitle.get("desc");
        assertTrue(desc.length() <= 30, "desc must be ≤30 chars after truncation, got " + desc.length());
        assertTrue(desc.endsWith("…"), "truncation marker must be present; got: " + desc);
    }
}
