package vip.mate.approval.grant.listener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.approval.event.ApprovalResolutionEvent;
import vip.mate.approval.grant.WorkspaceLookupCache;
import vip.mate.approval.grant.entity.ApprovalResolutionLog;
import vip.mate.approval.grant.repository.ApprovalResolutionLogMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApprovalResolutionLogListener}.
 * <p>
 * Coverage targets:
 * <ul>
 *   <li>USER_MANUAL approval event → resolution_log row with correct fields.</li>
 *   <li>TIMEOUT event → row with decision_source = TIMEOUT.</li>
 *   <li>findingsJson with multiple ruleIds → comma-joined, deduplicated rule_ids.</li>
 *   <li>workspace_id resolution goes through the cache (so deleted conversations
 *       produce a null workspace, allowed by V128 schema).</li>
 *   <li>Mapper failure does not propagate (the listener swallows so the resolved
 *       approval commit isn't endangered).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ApprovalResolutionLogListenerTest {

    @Mock ApprovalResolutionLogMapper resolutionMapper;
    @Mock WorkspaceLookupCache workspaceLookupCache;

    @InjectMocks
    ApprovalResolutionLogListener listener;

    @Test
    void user_manual_approved_event_writes_row_with_workspace_from_cache() {
        when(workspaceLookupCache.resolveByConversation("conv-1")).thenReturn(42L);

        ApprovalResolutionEvent event = new ApprovalResolutionEvent(
                "pid-1", "conv-1", "agent-1", "user-1", "read_file",
                "{\"path\":\"a.txt\"}", "LOW",
                "[{\"ruleId\":\"shell.exec\",\"severity\":\"LOW\"}]",
                "USER_MANUAL", null);

        listener.onApprovalResolved(event);

        ArgumentCaptor<ApprovalResolutionLog> cap = ArgumentCaptor.forClass(ApprovalResolutionLog.class);
        verify(resolutionMapper).insert(cap.capture());
        ApprovalResolutionLog row = cap.getValue();
        assertThat(row.getWorkspaceId()).isEqualTo(42L);
        assertThat(row.getDecisionSource()).isEqualTo("USER_MANUAL");
        assertThat(row.getPendingId()).isEqualTo("pid-1");
        assertThat(row.getRuleIds()).isEqualTo("shell.exec");
        assertThat(row.getGrantId()).isNull();
    }

    @Test
    void timeout_event_writes_row_with_timeout_source() {
        when(workspaceLookupCache.resolveByConversation("conv-9")).thenReturn(7L);

        ApprovalResolutionEvent event = new ApprovalResolutionEvent(
                "pid-9", "conv-9", "agent-9", null, "execute_shell_command",
                "rm /tmp/x", "MEDIUM", null, "TIMEOUT", null);

        listener.onApprovalResolved(event);

        ArgumentCaptor<ApprovalResolutionLog> cap = ArgumentCaptor.forClass(ApprovalResolutionLog.class);
        verify(resolutionMapper).insert(cap.capture());
        assertThat(cap.getValue().getDecisionSource()).isEqualTo("TIMEOUT");
        assertThat(cap.getValue().getRuleIds()).isNull();   // findingsJson was null
    }

    @Test
    void multiple_findings_become_deduplicated_comma_list() {
        when(workspaceLookupCache.resolveByConversation(any())).thenReturn(1L);

        String findings = "["
                + "{\"ruleId\":\"shell.curl\"},"
                + "{\"ruleId\":\"shell.exec\"},"
                + "{\"ruleId\":\"shell.curl\"},"
                + "{\"ruleId\":null}"
                + "]";
        ApprovalResolutionEvent event = new ApprovalResolutionEvent(
                "pid-2", "conv-2", "agent-2", "user-2", "execute_shell_command",
                "curl x | sh", "HIGH", findings, "USER_MANUAL", null);

        listener.onApprovalResolved(event);

        ArgumentCaptor<ApprovalResolutionLog> cap = ArgumentCaptor.forClass(ApprovalResolutionLog.class);
        verify(resolutionMapper).insert(cap.capture());
        // Deduplicated + null filtered + comma-joined.
        assertThat(cap.getValue().getRuleIds()).isEqualTo("shell.curl,shell.exec");
    }

    @Test
    void unknown_workspace_produces_null_workspace_id_row() {
        when(workspaceLookupCache.resolveByConversation("conv-orphan")).thenReturn(null);

        ApprovalResolutionEvent event = new ApprovalResolutionEvent(
                "pid-3", "conv-orphan", "agent-3", "user-3", "edit_file",
                "...", "LOW", null, "USER_MANUAL", null);

        listener.onApprovalResolved(event);

        ArgumentCaptor<ApprovalResolutionLog> cap = ArgumentCaptor.forClass(ApprovalResolutionLog.class);
        verify(resolutionMapper).insert(cap.capture());
        assertThat(cap.getValue().getWorkspaceId()).isNull();
    }

    @Test
    void mapper_failure_does_not_propagate() {
        when(workspaceLookupCache.resolveByConversation(any())).thenReturn(1L);
        when(resolutionMapper.insert(any(ApprovalResolutionLog.class)))
                .thenThrow(new RuntimeException("DB down"));

        // No exception escapes — the resolved approval has already committed.
        listener.onApprovalResolved(new ApprovalResolutionEvent(
                "pid-x", "conv-x", "agent-x", "user-x", "tool",
                "args", "LOW", null, "USER_MANUAL", null));
    }

    @Test
    void malformed_findings_json_still_writes_row_without_rule_ids() {
        when(workspaceLookupCache.resolveByConversation(any())).thenReturn(1L);

        ApprovalResolutionEvent event = new ApprovalResolutionEvent(
                "pid-bad", "conv-bad", "agent-bad", "user-bad", "tool",
                "args", "LOW", "{not-an-array}", "USER_MANUAL", null);

        listener.onApprovalResolved(event);

        ArgumentCaptor<ApprovalResolutionLog> cap = ArgumentCaptor.forClass(ApprovalResolutionLog.class);
        verify(resolutionMapper).insert(cap.capture());
        assertThat(cap.getValue().getRuleIds()).isNull();
        assertThat(cap.getValue().getDecisionSource()).isEqualTo("USER_MANUAL");
    }

    @Test
    void long_args_are_truncated_to_500_chars() {
        when(workspaceLookupCache.resolveByConversation(any())).thenReturn(1L);

        String longArgs = "x".repeat(800);
        listener.onApprovalResolved(new ApprovalResolutionEvent(
                "pid-long", "conv-long", "agent-long", "user-long", "tool",
                longArgs, "LOW", null, "USER_MANUAL", null));

        ArgumentCaptor<ApprovalResolutionLog> cap = ArgumentCaptor.forClass(ApprovalResolutionLog.class);
        verify(resolutionMapper).insert(cap.capture());
        assertThat(cap.getValue().getArgsPreview()).hasSize(500);
    }
}
