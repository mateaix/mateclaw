package vip.mate.agent.delegation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.conversation.ConversationService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubagentControllerTest {

    private SubagentRegistry registry;
    private ConversationService conversationService;
    private AuditEventService auditEventService;
    private SubagentController controller;
    private Authentication ownerAuth;
    private Authentication outsiderAuth;

    @BeforeEach
    void setUp() {
        registry = new SubagentRegistry();
        conversationService = mock(ConversationService.class);
        auditEventService = mock(AuditEventService.class);
        ObjectMapper mapper = new ObjectMapper();
        controller = new SubagentController(registry, conversationService, auditEventService, mapper);

        ownerAuth = mock(Authentication.class);
        when(ownerAuth.getName()).thenReturn("alice");
        outsiderAuth = mock(Authentication.class);
        when(outsiderAuth.getName()).thenReturn("mallory");

        // Default: alice owns parent-1, mallory does not.
        when(conversationService.isConversationOwner(eq("parent-1"), eq("alice"))).thenReturn(true);
        when(conversationService.isConversationOwner(eq("parent-1"), eq("mallory"))).thenReturn(false);
    }

    @Test
    @DisplayName("interrupt — owner gets 200 with interrupted=true and an audit row")
    void interruptOwner() {
        String sid = registry.register("parent-1", "child-1", 7L, "do thing", null);

        R<Map<String, Object>> response = controller.interrupt(sid, ownerAuth);

        assertThat(response.getCode()).isEqualTo(200); // ResultCode.SUCCESS
        assertThat(response.getData()).containsEntry("interrupted", true);
        assertThat(registry.get(sid).orElseThrow().status().get()).isEqualTo("interrupted");
        verify(auditEventService).record(eq("subagent.interrupt"), eq("subagent"),
                eq(sid), anyString(), anyString());
        // Denial audit must NOT have fired on the owner path.
        verify(auditEventService, never()).record(eq("subagent.interrupt.denied"),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("interrupt — non-owner is denied (403) and a denial audit is written")
    void interruptDeniedForNonOwner() {
        String sid = registry.register("parent-1", "child-1", 7L, "do thing", null);

        assertThatThrownBy(() -> controller.interrupt(sid, outsiderAuth))
                .isInstanceOf(MateClawException.class)
                .matches(t -> ((MateClawException) t).getCode() == 403);

        verify(auditEventService).record(eq("subagent.interrupt.denied"), eq("subagent"),
                eq(sid), anyString(), anyString());
        // Status must remain unchanged for the non-owner path.
        assertThat(registry.get(sid).orElseThrow().status().get()).isEqualTo("running");
    }

    @Test
    @DisplayName("interrupt — missing subagent throws 404")
    void interruptNotFound() {
        assertThatThrownBy(() -> controller.interrupt("sa-does-not-exist", ownerAuth))
                .isInstanceOf(MateClawException.class)
                .matches(t -> ((MateClawException) t).getCode() == 404);

        verify(auditEventService, never()).record(eq("subagent.interrupt"),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("spawn-pause — missing parentConversationId throws 400")
    void spawnPauseMissingParent() {
        Map<String, Object> body = new HashMap<>();
        body.put("paused", true);

        assertThatThrownBy(() -> controller.setPaused(body, ownerAuth))
                .isInstanceOf(MateClawException.class)
                .matches(t -> ((MateClawException) t).getCode() == 400);

        // Empty body also fails the same way.
        assertThatThrownBy(() -> controller.setPaused(new HashMap<>(), ownerAuth))
                .isInstanceOf(MateClawException.class)
                .matches(t -> ((MateClawException) t).getCode() == 400);
    }

    @Test
    @DisplayName("spawn-pause — owner toggles flag and audit captures decision")
    void spawnPauseOwnerToggle() {
        Map<String, Object> body = new HashMap<>();
        body.put("parentConversationId", "parent-1");
        body.put("paused", true);

        R<Map<String, Object>> resp = controller.setPaused(body, ownerAuth);
        assertThat(resp.getData()).containsEntry("paused", true);
        assertThat(registry.isSpawnPaused("parent-1")).isTrue();
        verify(auditEventService).record(eq("subagent.spawn-pause"), eq("conversation"),
                eq("parent-1"), eq("parent-1"), anyString());

        body.put("paused", false);
        controller.setPaused(body, ownerAuth);
        assertThat(registry.isSpawnPaused("parent-1")).isFalse();
    }

    @Test
    @DisplayName("spawn-pause — non-owner gets 403, flag is not changed")
    void spawnPauseNonOwnerForbidden() {
        Map<String, Object> body = new HashMap<>();
        body.put("parentConversationId", "parent-1");
        body.put("paused", true);

        assertThatThrownBy(() -> controller.setPaused(body, outsiderAuth))
                .isInstanceOf(MateClawException.class)
                .matches(t -> ((MateClawException) t).getCode() == 403);

        assertThat(registry.isSpawnPaused("parent-1")).isFalse();
    }

    @Test
    @DisplayName("listActive — missing parentConversationId throws 400")
    void listActiveMissingParent() {
        assertThatThrownBy(() -> controller.listActive(null, ownerAuth))
                .isInstanceOf(MateClawException.class)
                .matches(t -> ((MateClawException) t).getCode() == 400);
        assertThatThrownBy(() -> controller.listActive("", ownerAuth))
                .isInstanceOf(MateClawException.class)
                .matches(t -> ((MateClawException) t).getCode() == 400);
    }

    @Test
    @DisplayName("listActive — owner sees only their own subagents in the response")
    void listActiveOwnerScoped() {
        registry.register("parent-1", "child-1", 7L, "g", null);
        registry.register("parent-1", "child-2", 7L, "g2", null);
        registry.register("other-parent", "child-x", 8L, "g3", null);

        R<Map<String, Object>> resp = controller.listActive("parent-1", ownerAuth);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subagents = (List<Map<String, Object>>) resp.getData().get("subagents");
        assertThat(subagents).hasSize(2);
        assertThat(subagents).allSatisfy(dto -> {
            assertThat(dto.get("parentConversationId")).isEqualTo("parent-1");
            // Disposable + raw atomic refs must not leak into the wire DTO.
            assertThat(dto).doesNotContainKey("disposable");
        });
    }

    @Test
    @DisplayName("listActive — non-owner is denied 403")
    void listActiveNonOwnerForbidden() {
        registry.register("parent-1", "child-1", 7L, "g", null);

        assertThatThrownBy(() -> controller.listActive("parent-1", outsiderAuth))
                .isInstanceOf(MateClawException.class)
                .matches(t -> ((MateClawException) t).getCode() == 403);
    }
}
