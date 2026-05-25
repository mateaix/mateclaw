package vip.mate.channel.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.conversation.model.MessageEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truth-table for {@link ChatController#derivePersistStatus} (RFC-067 §4.6).
 * <p>
 * The pre-RFC controller had three different inline derivations across normal /
 * queued / replay {@code doOnComplete}; queued and replay hardcoded
 * {@code completed} which caused {@code awaiting_approval} turns to be silently
 * downgraded — the frontend would then call {@code expirePendingApprovals} and
 * ghost-clear the banner. These tests pin the unified five-way truth table so a
 * future refactor can't reintroduce the divergence.
 */
class ChatControllerPersistStatusTest {

    @Test
    @DisplayName("awaiting_approval wins over every other condition (top of priority)")
    void awaitingApprovalTakesPrecedence() {
        // Even when stop+error fired AFTER the approval gate, persistence must
        // still surface awaiting_approval — the turn isn't truly finished and
        // the frontend's done handler skips expire on this status.
        assertThat(ChatController.derivePersistStatus(true, true, true,
                ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP))
                .isEqualTo("awaiting_approval");
        assertThat(ChatController.derivePersistStatus(true, false, false, null))
                .isEqualTo("awaiting_approval");
    }

    @Test
    @DisplayName("error beats every non-approval state")
    void errorOnTypedErrorPrefix() {
        assertThat(ChatController.derivePersistStatus(false, true, false, null))
                .isEqualTo("error");
        // Stop coexists with error → still error (BaseAgent sanitization needs to
        // skip these regardless of whether the user pressed Stop afterward).
        assertThat(ChatController.derivePersistStatus(false, true, true,
                ChatStreamTracker.InterruptType.USER_STOP))
                .isEqualTo("error");
    }

    @Test
    @DisplayName("clean finish → completed")
    void completedOnCleanFinish() {
        assertThat(ChatController.derivePersistStatus(false, false, false, null))
                .isEqualTo("completed");
    }

    @Test
    @DisplayName("user-stop without follow-up → stopped")
    void stoppedOnPlainStop() {
        assertThat(ChatController.derivePersistStatus(false, false, true,
                ChatStreamTracker.InterruptType.USER_STOP))
                .isEqualTo("stopped");
        // Null InterruptType (defensive) also collapses to stopped — mirrors
        // historical behavior where wasStopped+null was the common shape on
        // doOnCancel paths before the typed enum landed.
        assertThat(ChatController.derivePersistStatus(false, false, true, null))
                .isEqualTo("stopped");
    }

    @Test
    @DisplayName("user interrupt-with-followup → interrupted (queued message takes over)")
    void interruptedOnFollowupQueue() {
        assertThat(ChatController.derivePersistStatus(false, false, true,
                ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP))
                .isEqualTo("interrupted");
    }

    @Test
    @DisplayName("empty completed turns persist an explicit placeholder")
    void emptyCompletedTurnUsesPlaceholder() {
        assertThat(ChatController.emptyAssistantPlaceholder("completed"))
                .isEqualTo("[本次没有输出]");
        assertThat(ChatController.emptyAssistantPlaceholder("awaiting_approval"))
                .isEqualTo("[等待审批]");
    }

    @Test
    @DisplayName("done.persisted reflects whether an assistant row was actually saved")
    void donePersistedFollowsSavedAssistant() {
        MessageEntity saved = new MessageEntity();

        assertThat(ChatController.isAssistantPersisted(saved)).isTrue();
        assertThat(ChatController.isAssistantPersisted(null)).isFalse();
    }
}
