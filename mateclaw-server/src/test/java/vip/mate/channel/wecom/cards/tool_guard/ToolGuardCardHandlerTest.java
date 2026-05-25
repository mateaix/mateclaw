package vip.mate.channel.wecom.cards.tool_guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import vip.mate.approval.ApprovalService;
import vip.mate.approval.PendingApproval;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.wecom.WeComChannelAdapter;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for the validate-before-render invariant (RFC-32 v2.1 / R-5).
 *
 * <p>The earlier draft (v2.0) did "render resolved card → inject /approve →
 * router rejects unauthorized" — meaning a Lee click on Zhang's pending
 * would briefly show "✅ 已批准 by 李四" on the card before the router
 * silently dropped the command. v2.1 reorders to validate first, then
 * render the resolved card matching the validation result, then inject
 * the command only when authorized.
 */
class ToolGuardCardHandlerTest {

    private ApprovalService approvalService;
    private WeComChannelAdapter adapter;
    private ToolGuardButtonKey buttonKey;
    private ToolGuardCardHandler handler;

    @BeforeEach
    void setUp() {
        approvalService = Mockito.mock(ApprovalService.class);
        adapter = Mockito.mock(WeComChannelAdapter.class);
        buttonKey = new ToolGuardButtonKey(new ObjectMapper());
        handler = new ToolGuardCardHandler(approvalService, buttonKey);
    }

    @Test
    @DisplayName("unauthorized click renders 'unauthorized' card and does NOT inject command")
    void unauthorizedClickDoesNotInject() {
        // Given: a pending whose original requester is "alice"
        PendingApproval pending = pendingFor("pid_xyz", "alice", "shell_exec");
        when(approvalService.getPending("pid_xyz")).thenReturn(Optional.of(pending));

        // When: bob (NOT alice) clicks "approve"
        Map<String, Object> frame = inboundFrame("evt_req_1", buttonKey.encode(
                ToolGuardButtonKey.Action.APPROVE, "pid_xyz", "shell_exec", "HIGH"));
        handler.handle(adapter, frame, tce(frame), fromBlock("bob"));

        // Then: card was updated to "unauthorized" state…
        ArgumentCaptor<Map<String, Object>> cardCaptor = cardArgCaptor();
        verify(adapter, times(1)).updateTemplateCard(eq("evt_req_1"), cardCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> mainTitle = (Map<String, Object>) cardCaptor.getValue().get("main_title");
        assertNotNull(mainTitle);
        String title = (String) mainTitle.get("title");
        assertTrue(title.contains("仅原请求者"),
                "unauthorized card must say '仅原请求者可审批'; got: " + title);

        // …and CRITICALLY, no /approve command was injected
        verify(adapter, never()).injectSyntheticMessage(any(ChannelMessage.class));
    }

    @Test
    @DisplayName("expired pending renders 'expired' card and does NOT inject command")
    void expiredPendingShowsExpiredCard() {
        when(approvalService.getPending("pid_old")).thenReturn(Optional.empty());

        Map<String, Object> frame = inboundFrame("evt_req_2", buttonKey.encode(
                ToolGuardButtonKey.Action.APPROVE, "pid_old", "shell_exec", "MEDIUM"));
        handler.handle(adapter, frame, tce(frame), fromBlock("alice"));

        ArgumentCaptor<Map<String, Object>> cardCaptor = cardArgCaptor();
        verify(adapter, times(1)).updateTemplateCard(eq("evt_req_2"), cardCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> mainTitle = (Map<String, Object>) cardCaptor.getValue().get("main_title");
        assertTrue(((String) mainTitle.get("title")).contains("过期"),
                "expired card title must mention 过期; got: " + mainTitle.get("title"));
        verify(adapter, never()).injectSyntheticMessage(any(ChannelMessage.class));
    }

    @Test
    @DisplayName("authorized approve click: render resolved card AND inject /approve")
    void authorizedApproveInjectsCommand() {
        PendingApproval pending = pendingFor("pid_ok", "alice", "shell_exec");
        when(approvalService.getPending("pid_ok")).thenReturn(Optional.of(pending));

        Map<String, Object> frame = inboundFrame("evt_req_3", buttonKey.encode(
                ToolGuardButtonKey.Action.APPROVE, "pid_ok", "shell_exec", "HIGH"));
        handler.handle(adapter, frame, tce(frame), fromBlock("alice"));

        ArgumentCaptor<Map<String, Object>> cardCaptor = cardArgCaptor();
        verify(adapter, times(1)).updateTemplateCard(eq("evt_req_3"), cardCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> mainTitle = (Map<String, Object>) cardCaptor.getValue().get("main_title");
        assertTrue(((String) mainTitle.get("title")).contains("已批准"),
                "title must announce success; got: " + mainTitle.get("title"));

        // Synthetic command should be injected with the right text
        ArgumentCaptor<ChannelMessage> msgCaptor = ArgumentCaptor.forClass(ChannelMessage.class);
        verify(adapter, times(1)).injectSyntheticMessage(msgCaptor.capture());
        ChannelMessage injected = msgCaptor.getValue();
        assertEquals("/approve pid_ok", injected.getContent());
        assertEquals("alice", injected.getSenderId());
        assertEquals("text", injected.getContentType());
    }

    @Test
    @DisplayName("authorized deny click: injects /deny")
    void authorizedDenyInjectsCommand() {
        PendingApproval pending = pendingFor("pid_d", "alice", "shell_exec");
        when(approvalService.getPending("pid_d")).thenReturn(Optional.of(pending));

        Map<String, Object> frame = inboundFrame("evt_req_4", buttonKey.encode(
                ToolGuardButtonKey.Action.DENY, "pid_d", "shell_exec", "HIGH"));
        handler.handle(adapter, frame, tce(frame), fromBlock("alice"));

        ArgumentCaptor<ChannelMessage> msgCaptor = ArgumentCaptor.forClass(ChannelMessage.class);
        verify(adapter, times(1)).injectSyntheticMessage(msgCaptor.capture());
        assertEquals("/deny pid_d", msgCaptor.getValue().getContent());
    }

    @Test
    @DisplayName("system-owned pending allows ANY clicker (no original requester)")
    void systemPendingAcceptsAnyClicker() {
        PendingApproval pending = pendingFor("pid_sys", "system", "shell_exec");
        when(approvalService.getPending("pid_sys")).thenReturn(Optional.of(pending));

        Map<String, Object> frame = inboundFrame("evt_req_5", buttonKey.encode(
                ToolGuardButtonKey.Action.APPROVE, "pid_sys", "shell_exec", "MEDIUM"));
        handler.handle(adapter, frame, tce(frame), fromBlock("anyone"));

        verify(adapter).injectSyntheticMessage(any(ChannelMessage.class));
    }

    @Test
    @DisplayName("malformed event_key drops the event silently — no card update, no command")
    void malformedEventKeyIgnored() {
        Map<String, Object> frame = inboundFrame("evt_req_6", "{not json");
        handler.handle(adapter, frame, tce(frame), fromBlock("alice"));

        verify(adapter, never()).updateTemplateCard(anyString(), any());
        verify(adapter, never()).injectSyntheticMessage(any(ChannelMessage.class));
    }

    // ---- helpers ----

    private static PendingApproval pendingFor(String pendingId, String requester, String tool) {
        PendingApproval p = new PendingApproval(
                pendingId, "wecom:alice", requester, tool, "{}", "test approval");
        // Status defaults to "pending" via the constructor
        return p;
    }

    private static Map<String, Object> inboundFrame(String reqId, String eventKey) {
        return Map.of(
                "cmd", "aibot_event_callback",
                "headers", Map.of("req_id", reqId),
                "body", Map.of(
                        "chattype", "single",
                        "chatid", "alice",
                        "from", Map.of("userid", "alice"),
                        "event", Map.of(
                                "eventtype", "template_card_event",
                                "template_card_event", Map.of(
                                        "task_id", "tg_approval_pid_xyz",
                                        "event_key", eventKey
                                )
                        )
                )
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tce(Map<String, Object> frame) {
        Map<String, Object> body = (Map<String, Object>) frame.get("body");
        Map<String, Object> event = (Map<String, Object>) body.get("event");
        return (Map<String, Object>) event.get("template_card_event");
    }

    private static Map<String, Object> fromBlock(String userid) {
        return Map.of("userid", userid);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> cardArgCaptor() {
        return (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
    }
}
