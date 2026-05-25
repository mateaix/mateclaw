package vip.mate.workspace.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.approval.MetadataDecision;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * markPendingApprovalsResolved must keep three persisted fields aligned (RFC-067 §4.1.5):
 *   1. metadata.pendingApproval.status
 *   2. metadata.currentPhase (when it was awaiting_approval)
 *   3. MessageEntity.status (when it was awaiting_approval) — using the existing
 *      Message.status union the frontend recognizes (completed/stopped), NOT
 *      approved/denied.
 * <p>
 * The pre-RFC implementation only flipped pendingApproval.status, leaving the
 * other two stale and producing ghost approval banners on refresh.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceMarkPendingApprovalsResolvedTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private AgentMapper agentMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private ConversationService service;

    @Test
    @DisplayName("APPROVED reconciles all three fields when message was awaiting_approval")
    void approvedRewritesAllThreeFields() {
        MessageEntity msg = assistantWithPending("pid-1", "pending_approval",
                "awaiting_approval", "awaiting_approval");
        whenListReturns(List.of(msg));

        int rewritten = service.markPendingApprovalsResolved("conv-1",
                Set.of("pid-1"), MetadataDecision.APPROVED);

        assertThat(rewritten).isEqualTo(1);
        ArgumentCaptor<MessageEntity> updated = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).updateById(updated.capture());
        MessageEntity persisted = updated.getValue();

        assertThat(persisted.getStatus()).isEqualTo("completed");
        Map<String, Object> meta = readMeta(persisted);
        assertThat(meta).containsEntry("currentPhase", "resolved");
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) meta.get("pendingApproval");
        assertThat(pending).containsEntry("status", "approved");
    }

    @Test
    @DisplayName("DENIED maps to message.status=stopped + pendingApproval.status=denied")
    void deniedMapsToStoppedAndDenied() {
        MessageEntity msg = assistantWithPending("pid-2", "pending_approval",
                "awaiting_approval", "awaiting_approval");
        whenListReturns(List.of(msg));

        int rewritten = service.markPendingApprovalsResolved("conv-1",
                Set.of("pid-2"), MetadataDecision.DENIED);

        assertThat(rewritten).isEqualTo(1);
        ArgumentCaptor<MessageEntity> updated = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).updateById(updated.capture());
        MessageEntity persisted = updated.getValue();

        assertThat(persisted.getStatus()).isEqualTo("stopped");
        Map<String, Object> meta = readMeta(persisted);
        assertThat(meta).containsEntry("currentPhase", "resolved");
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) meta.get("pendingApproval");
        assertThat(pending).containsEntry("status", "denied");
    }

    @Test
    @DisplayName("Idempotent: already-resolved pendingApproval.status is left alone")
    void alreadyResolvedIsSkipped() {
        MessageEntity msg = assistantWithPending("pid-3", "approved",
                "drafting_answer", "completed");
        whenListReturns(List.of(msg));

        int rewritten = service.markPendingApprovalsResolved("conv-1",
                Set.of("pid-3"), MetadataDecision.DENIED);

        assertThat(rewritten).isZero();
        verify(messageMapper, never()).updateById(any(MessageEntity.class));
    }

    @Test
    @DisplayName("Message.status is preserved when source was already a non-approval terminal state")
    void preservesNonApprovalMessageStatus() {
        // Edge case: pendingApproval.status==pending_approval but the message itself
        // is already 'completed' (e.g. text portion finished, approval still in flight).
        // We must NOT overwrite a downstream-set message.status with stopped/completed
        // just because the metadata flipped — only convert the awaiting_approval gate.
        MessageEntity msg = assistantWithPending("pid-4", "pending_approval",
                "drafting_answer", "completed");
        whenListReturns(List.of(msg));

        int rewritten = service.markPendingApprovalsResolved("conv-1",
                Set.of("pid-4"), MetadataDecision.DENIED);

        assertThat(rewritten).isEqualTo(1);
        ArgumentCaptor<MessageEntity> updated = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).updateById(updated.capture());
        // status stays 'completed' — only awaiting_approval is rewritten.
        assertThat(updated.getValue().getStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("Non-matching pendingId in the same conversation is ignored")
    void unrelatedPendingIdNotTouched() {
        MessageEntity matching = assistantWithPending("pid-keep-A", "pending_approval",
                "awaiting_approval", "awaiting_approval");
        MessageEntity unrelated = assistantWithPending("pid-other-B", "pending_approval",
                "awaiting_approval", "awaiting_approval");
        whenListReturns(List.of(matching, unrelated));

        int rewritten = service.markPendingApprovalsResolved("conv-1",
                Set.of("pid-keep-A"), MetadataDecision.APPROVED);

        assertThat(rewritten).isEqualTo(1);
        // Exactly one updateById fired — the unrelated message is untouched.
        verify(messageMapper).updateById(any(MessageEntity.class));
    }

    @Test
    @DisplayName("currentPhase != awaiting_approval is preserved (only the awaiting gate is cleared)")
    void preservesUnrelatedCurrentPhase() {
        MessageEntity msg = assistantWithPending("pid-5", "pending_approval",
                "drafting_answer", "awaiting_approval");
        whenListReturns(List.of(msg));

        service.markPendingApprovalsResolved("conv-1",
                Set.of("pid-5"), MetadataDecision.APPROVED);

        ArgumentCaptor<MessageEntity> updated = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).updateById(updated.capture());
        Map<String, Object> meta = readMeta(updated.getValue());
        assertThat(meta).containsEntry("currentPhase", "drafting_answer");
    }

    @Test
    @DisplayName("Null decision rejects loudly — callers must declare intent")
    void nullDecisionRejected() {
        // No need to mock listMessages: validation happens before the DB read.
        assertThatThrownBy(() -> service.markPendingApprovalsResolved(
                "conv-1", Set.of("pid-x"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- RFC-067 §4.10 PR 9: toolCall / segment sync on deny ----------

    @Test
    @DisplayName("DENIED flips matching toolCall.success=false + result so card renders ✗ instead of ✓")
    void deniedFlipsMatchingToolCall() {
        // Mirrors the production failure: LLM streamed tool_call_complete with
        // status=completed before approval was decided; user clicks deny;
        // without this fix the metadata still says success=true so the frontend
        // renders a green checkmark on the rejected rm command.
        MessageEntity msg = assistantWithToolCallsAndSegments(
                "pid-deny-A",
                "rm -rf ~/Downloads/历史",
                /* succeededBefore */ true);
        whenListReturns(List.of(msg));

        service.markPendingApprovalsResolved("conv-1", Set.of("pid-deny-A"), MetadataDecision.DENIED);

        ArgumentCaptor<MessageEntity> updated = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).updateById(updated.capture());
        Map<String, Object> meta = readMeta(updated.getValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) meta.get("toolCalls");
        assertThat(toolCalls).hasSize(2);

        Map<String, Object> matched = toolCalls.stream()
                .filter(c -> "{\"command\":\"rm -rf ~/Downloads/历史\"}".equals(c.get("arguments")))
                .findFirst().orElseThrow();
        // Status MUST flip off awaiting_approval so MessageBubble's success=false
        // branch can render red ✗ instead of the orange ⚠️ that wins the
        // precedence when status==='awaiting_approval'.
        assertThat(matched).containsEntry("status", "completed");
        assertThat(matched).containsEntry("success", Boolean.FALSE);
        assertThat(matched).containsEntry("result", "[已拒绝]");

        // Unrelated toolCall (different arguments) is untouched — the earlier
        // ls command still shows ✓ because it really did execute.
        Map<String, Object> unrelated = toolCalls.stream()
                .filter(c -> "{\"command\":\"ls -la /tmp\"}".equals(c.get("arguments")))
                .findFirst().orElseThrow();
        assertThat(unrelated).containsEntry("status", "completed");
        assertThat(unrelated).containsEntry("success", Boolean.TRUE);
        assertThat(unrelated).doesNotContainEntry("result", "[已拒绝]");
    }

    @Test
    @DisplayName("DENIED flips matching segment toolSuccess=false + toolResult")
    void deniedFlipsMatchingSegment() {
        MessageEntity msg = assistantWithToolCallsAndSegments(
                "pid-deny-B",
                "rm -rf ~/Downloads/历史",
                /* succeededBefore */ true);
        whenListReturns(List.of(msg));

        service.markPendingApprovalsResolved("conv-1", Set.of("pid-deny-B"), MetadataDecision.DENIED);

        ArgumentCaptor<MessageEntity> updated = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).updateById(updated.capture());
        Map<String, Object> meta = readMeta(updated.getValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments = (List<Map<String, Object>>) meta.get("segments");
        Map<String, Object> matched = segments.stream()
                .filter(s -> "tool_call".equals(s.get("type")))
                .filter(s -> "{\"command\":\"rm -rf ~/Downloads/历史\"}".equals(s.get("toolArgs")))
                .findFirst().orElseThrow();
        // Same status flip as toolCalls — ToolCallSegment.vue computes isError as
        // {status==='error' || toolSuccess===false}, so toolSuccess=false alone
        // is enough only if status is no longer awaiting_approval.
        assertThat(matched).containsEntry("status", "completed");
        assertThat(matched).containsEntry("toolSuccess", Boolean.FALSE);
        assertThat(matched).containsEntry("toolResult", "[已拒绝]");
    }

    @Test
    @DisplayName("H2-style double-encoded metadata: unwrap before parse so reconciliation succeeds")
    void doubleEncodedMetadataIsUnwrapped() {
        // H2's JSON column returns metadata as a JSON-encoded string when read via
        // MyBatis (leading + trailing `"`, inner `"` escaped to `\"`). Production
        // failure mode prior to PR 9: this form failed `objectMapper.readValue` with
        // "Cannot construct LinkedHashMap from String value", turning every deny
        // into a silent no-op (messagesRewritten=0) and leaving toolCall.success=true.
        // The read-side unwrap (mirroring MessageVO.parseMetadataToObject) is the fix.
        MessageEntity msg = assistantWithPending("pid-h2-encoded", "pending_approval",
                "awaiting_approval", "awaiting_approval");
        // Wrap the clean JSON like H2 would: re-serialize the existing metadata
        // string AS A STRING — this is exactly the shape MyBatis hands us when the
        // column type is JSON.
        try {
            String wrapped = new ObjectMapper().writeValueAsString(msg.getMetadata());
            msg.setMetadata(wrapped);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        // Confirm fixture really is double-encoded (starts with `"`).
        assertThat(msg.getMetadata()).startsWith("\"");
        whenListReturns(List.of(msg));

        int rewritten = service.markPendingApprovalsResolved("conv-1",
                Set.of("pid-h2-encoded"), MetadataDecision.DENIED);

        assertThat(rewritten).isEqualTo(1);
        ArgumentCaptor<MessageEntity> updated = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).updateById(updated.capture());
        // Verify the rewritten message is stored as clean JSON (not re-wrapped).
        assertThat(updated.getValue().getMetadata()).startsWith("{");
        Map<String, Object> meta = readMeta(updated.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> pa = (Map<String, Object>) meta.get("pendingApproval");
        assertThat(pa).containsEntry("status", "denied");
    }

    @Test
    @DisplayName("APPROVED also flips matching toolCall + segment to terminal '[已批准]' on the gate row")
    void approvedFlipsGateRow() {
        // Replay runs the actual tool in a NEW assistant message — it does NOT
        // overwrite this gate message's toolCall/segment. So if we don't flip
        // the gate row here it stays as an orange spinner forever (RFC-067 §4.10
        // post-PR-9 follow-up: the original "approve leaves toolCalls alone"
        // assumption was wrong for this architecture).
        MessageEntity msg = assistantWithToolCallsAndSegments(
                "pid-approve-A",
                "rm -rf ~/Downloads/历史",
                /* awaitingApproval */ true);
        whenListReturns(List.of(msg));

        service.markPendingApprovalsResolved("conv-1", Set.of("pid-approve-A"), MetadataDecision.APPROVED);

        ArgumentCaptor<MessageEntity> updated = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).updateById(updated.capture());
        Map<String, Object> meta = readMeta(updated.getValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) meta.get("toolCalls");
        Map<String, Object> rmCall = toolCalls.stream()
                .filter(c -> "{\"command\":\"rm -rf ~/Downloads/历史\"}".equals(c.get("arguments")))
                .findFirst().orElseThrow();
        assertThat(rmCall).containsEntry("status", "completed");
        assertThat(rmCall).containsEntry("success", Boolean.TRUE);
        assertThat(rmCall).containsEntry("result", "[已批准]");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments = (List<Map<String, Object>>) meta.get("segments");
        Map<String, Object> rmSegment = segments.stream()
                .filter(s -> "tool_call".equals(s.get("type")))
                .filter(s -> "{\"command\":\"rm -rf ~/Downloads/历史\"}".equals(s.get("toolArgs")))
                .findFirst().orElseThrow();
        assertThat(rmSegment).containsEntry("status", "completed");
        assertThat(rmSegment).containsEntry("toolSuccess", Boolean.TRUE);
        assertThat(rmSegment).containsEntry("toolResult", "[已批准]");

        // Unrelated ls toolCall is untouched (it really did execute).
        Map<String, Object> lsCall = toolCalls.stream()
                .filter(c -> "{\"command\":\"ls -la /tmp\"}".equals(c.get("arguments")))
                .findFirst().orElseThrow();
        assertThat(lsCall).containsEntry("success", Boolean.TRUE);
        assertThat(lsCall).doesNotContainEntry("result", "[已批准]");
    }

    // ---------- helpers ----------

    private void whenListReturns(List<MessageEntity> messages) {
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(messages);
    }

    private MessageEntity assistantWithPending(String pendingId, String approvalStatus,
                                               String currentPhase, String messageStatus) {
        MessageEntity m = new MessageEntity();
        m.setId(System.nanoTime());
        m.setConversationId("conv-1");
        m.setRole("assistant");
        m.setStatus(messageStatus);

        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("pendingId", pendingId);
        pending.put("toolName", "write_file");
        pending.put("status", approvalStatus);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("currentPhase", currentPhase);
        meta.put("pendingApproval", pending);
        try {
            m.setMetadata(new ObjectMapper().writeValueAsString(meta));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return m;
    }

    /**
     * Build a message that mirrors the production shape in image 3 of RFC-067 PR 9:
     * one already-completed ls command + one rm command awaiting approval. Both
     * carry status=completed because tool_call_complete events streamed before
     * the user's approval decision arrived. {@code succeededBefore=true} makes
     * the rm entry's success=true initially, which is the bug RFC-067 PR 9 fixes
     * (frontend renders ✓ on a denied tool).
     */
    private MessageEntity assistantWithToolCallsAndSegments(String pendingId,
                                                            String denyCommand,
                                                            boolean succeededBefore) {
        MessageEntity m = new MessageEntity();
        m.setId(System.nanoTime());
        m.setConversationId("conv-1");
        m.setRole("assistant");
        m.setStatus("awaiting_approval");

        String denyArgs = "{\"command\":\"" + denyCommand + "\"}";
        String lsArgs = "{\"command\":\"ls -la /tmp\"}";

        Map<String, Object> lsCall = new LinkedHashMap<>();
        lsCall.put("name", "execute_shell_command");
        lsCall.put("arguments", lsArgs);
        lsCall.put("status", "completed");
        lsCall.put("success", Boolean.TRUE);
        lsCall.put("result", "lrwxr-xr-x ...");

        Map<String, Object> rmCall = new LinkedHashMap<>();
        rmCall.put("name", "execute_shell_command");
        rmCall.put("arguments", denyArgs);
        // Production-faithful: rm entry is paused on the guard so its status is
        // `awaiting_approval` (not `completed`). MessageBubble's icon precedence
        // is running > awaiting_approval > success!=false > success===false, so
        // the deny path MUST flip status off awaiting_approval for the success
        // check to win — otherwise the card stays orange ⚠️.
        rmCall.put("status", "awaiting_approval");
        rmCall.put("success", succeededBefore);
        rmCall.put("result", "stale-output");

        Map<String, Object> lsSegment = new LinkedHashMap<>();
        lsSegment.put("id", "to-0");
        lsSegment.put("type", "tool_call");
        lsSegment.put("status", "completed");
        lsSegment.put("toolName", "execute_shell_command");
        lsSegment.put("toolArgs", lsArgs);
        lsSegment.put("toolSuccess", Boolean.TRUE);

        Map<String, Object> rmSegment = new LinkedHashMap<>();
        rmSegment.put("id", "to-1");
        rmSegment.put("type", "tool_call");
        rmSegment.put("status", "awaiting_approval");
        rmSegment.put("toolName", "execute_shell_command");
        rmSegment.put("toolArgs", denyArgs);
        rmSegment.put("toolSuccess", succeededBefore);

        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("pendingId", pendingId);
        pending.put("toolName", "execute_shell_command");
        pending.put("arguments", denyArgs);
        pending.put("status", "pending_approval");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("toolCalls", List.of(lsCall, rmCall));
        meta.put("segments", List.of(lsSegment, rmSegment));
        meta.put("currentPhase", "awaiting_approval");
        meta.put("pendingApproval", pending);
        try {
            m.setMetadata(new ObjectMapper().writeValueAsString(meta));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return m;
    }

    private Map<String, Object> readMeta(MessageEntity msg) {
        try {
            return new ObjectMapper().readValue(msg.getMetadata(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
