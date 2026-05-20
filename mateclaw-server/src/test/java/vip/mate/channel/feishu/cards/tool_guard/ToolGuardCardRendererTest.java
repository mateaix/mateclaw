package vip.mate.channel.feishu.cards.tool_guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.notification.ApprovalNotice;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the shape of the rendered Schema-2.0 button card so a Feishu
 * spec tweak (button format change, action element rename) breaks
 * loudly here rather than in production.
 */
class ToolGuardCardRendererTest {

    private final ToolGuardCardRenderer renderer = new ToolGuardCardRenderer(
            new ToolGuardButtonValue(new ObjectMapper()));

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("rendered card carries schema 2.0, header, summary markdown, and two buttons")
    void cardShape() {
        ApprovalNotice notice = new ApprovalNotice(
                "pend-1", "feishu_doc_create", "Create a new Doc",
                "{\"title\":\"meeting notes\"}", "HIGH",
                List.of(Map.of("severity", "HIGH", "title", "Mutating Feishu doc")),
                "/approve pend-1", "/deny pend-1");

        Map<String, Object> card = renderer.render(notice);

        assertEquals("2.0", card.get("schema"));
        Map<String, Object> header = (Map<String, Object>) card.get("header");
        assertNotNull(header);
        Map<String, Object> body = (Map<String, Object>) card.get("body");
        List<Map<String, Object>> elements = (List<Map<String, Object>>) body.get("elements");
        assertEquals(2, elements.size(), "expect markdown + action elements");

        Map<String, Object> md = elements.get(0);
        assertEquals("markdown", md.get("tag"));
        String content = (String) md.get("content");
        assertTrue(content.contains("feishu_doc_create"), "tool name in summary");
        assertTrue(content.contains("HIGH"), "severity in summary");

        Map<String, Object> actions = elements.get(1);
        assertEquals("action", actions.get("tag"));
        List<Map<String, Object>> buttons = (List<Map<String, Object>>) actions.get("actions");
        assertEquals(2, buttons.size());
        assertEquals("primary", buttons.get(0).get("type"));
        assertEquals("danger", buttons.get(1).get("type"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("approve / deny buttons carry round-trippable action values")
    void buttonsCarryRoundTrippableValues() {
        ApprovalNotice notice = new ApprovalNotice(
                "pend-42", "feishu_calendar_create_event", "schedule meeting",
                "{}", "MEDIUM", List.of(), "/approve pend-42", "/deny pend-42");

        Map<String, Object> card = renderer.render(notice);
        List<Map<String, Object>> elements = (List<Map<String, Object>>) ((Map<String, Object>) card.get("body")).get("elements");
        List<Map<String, Object>> buttons = (List<Map<String, Object>>) elements.get(1).get("actions");

        Map<String, Object> approveValue = (Map<String, Object>) buttons.get(0).get("value");
        assertEquals(ToolGuardButtonValue.ACTION_APPROVE, approveValue.get("action"));
        assertEquals("pend-42", approveValue.get("rid"));

        Map<String, Object> denyValue = (Map<String, Object>) buttons.get(1).get("value");
        assertEquals(ToolGuardButtonValue.ACTION_DENY, denyValue.get("action"));
        assertEquals("pend-42", denyValue.get("rid"));
    }

    @Test
    @DisplayName("buildResolvedCard returns a no-action body with the given title + template")
    @SuppressWarnings("unchecked")
    void buildResolvedCardShape() {
        Map<String, Object> card = ToolGuardCardRenderer.buildResolvedCard(
                "✅ 已批准", "tool foo approved by Alice", "green");

        assertEquals("2.0", card.get("schema"));
        Map<String, Object> header = (Map<String, Object>) card.get("header");
        assertEquals("green", header.get("template"));
        Map<String, Object> title = (Map<String, Object>) header.get("title");
        assertEquals("✅ 已批准", title.get("content"));

        Map<String, Object> body = (Map<String, Object>) card.get("body");
        List<Map<String, Object>> elements = (List<Map<String, Object>>) body.get("elements");
        assertEquals(1, elements.size());
        assertEquals("markdown", elements.get(0).get("tag"));
        assertTrue(((String) elements.get(0).get("content")).contains("approved by Alice"));
    }
}
