package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.conversation.model.MessageEntity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closure regression test for RFC-067 PR 7.
 * <p>
 * PR 1 §4.1.5 flips {@code MessageEntity.status} from {@code awaiting_approval}
 * to {@code completed} (approve) or {@code stopped} (deny) inside
 * {@link vip.mate.workspace.conversation.ConversationService#markPendingApprovalsResolved}.
 * That status flip travels into history sanitization on subsequent LLM turns;
 * if any sanitizer stage accidentally treated the post-flip status as a stub
 * marker, the original assistant content would be dropped from history and the
 * user-visible conversation would lose context after every approval.
 * <p>
 * These tests pin the boundary: only the explicit {@code [等待审批]} content
 * placeholder is dropped by stage 1; a message that carries real streamed text
 * + {@code status=awaiting_approval | completed | stopped} is preserved exactly
 * regardless of where in the approval lifecycle it sits.
 */
class BaseAgentApprovalSanitizationTest {

    @Test
    @DisplayName("Real assistant content with status=awaiting_approval is NOT a Stage 1 placeholder")
    void realContentDuringAwaiting() {
        // Common shape: streamed partial answer + tool_approval_requested mid-flight,
        // doOnComplete persists with status=awaiting_approval (PR 5).
        MessageEntity msg = entity("我准备读取你的简历文件。", "awaiting_approval");
        assertFalse(BaseAgent.isApprovalPlaceholder(msg.getContent()),
                "real text must not match the placeholder regex — sanitizer would drop it otherwise");
    }

    @Test
    @DisplayName("Post-approve message (status=completed, real content) is NOT a placeholder")
    void postApproveMessageSurvives() {
        // After PR 1 §4.1.5 reconciles approval: status flips awaiting_approval → completed,
        // metadata.pendingApproval.status flips pending_approval → approved, content is unchanged.
        MessageEntity msg = entity("已读取简历，关键信息: ...", "completed");
        assertFalse(BaseAgent.isApprovalPlaceholder(msg.getContent()),
                "approved-and-completed history entry must survive sanitization for the next LLM turn");
    }

    @Test
    @DisplayName("Post-deny message (status=stopped, real content) is NOT a placeholder")
    void postDenyMessageSurvives() {
        // Deny path: status flips awaiting_approval → stopped, content stays as the
        // partial assistant text. The LLM should still see this on the next turn so
        // it understands "I started reading then was denied" rather than amnesia.
        MessageEntity msg = entity("用户拒绝执行工具 write_file", "stopped");
        assertFalse(BaseAgent.isApprovalPlaceholder(msg.getContent()),
                "denied turn's assistant text must survive history sanitization");
    }

    @Test
    @DisplayName("Pure placeholder content IS dropped (Stage 1's actual job)")
    void placeholderStubIsFiltered() {
        // The "[等待审批]" stub is the historical placeholder format that Stage 1 catches —
        // those rows have no streamed content and add no value to the LLM context.
        MessageEntity msg = entity("[等待审批]", "awaiting_approval");
        assertTrue(BaseAgent.isApprovalPlaceholder(msg.getContent()),
                "stub-only placeholder content must still match so Stage 1 keeps filtering it");
    }

    private static MessageEntity entity(String content, String status) {
        MessageEntity m = new MessageEntity();
        m.setRole("assistant");
        m.setContent(content);
        m.setStatus(status);
        return m;
    }
}
