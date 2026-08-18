package vip.mate.agent.graph.guard;

import org.junit.jupiter.api.Test;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.ActionExecutionLedger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionCompletionPolicyTest {

    @Test
    void ordinaryQuestionAllowsTextAnswer() {
        assertEquals(ActionCompletionPolicy.Decision.ALLOW,
                ActionCompletionPolicy.evaluate(false, 0, ActionExecutionLedger.empty()));
    }

    @Test
    void successfulActionAllowsTextAnswer() {
        ActionExecutionLedger ledger = ledger("schedule_meeting", true);
        assertEquals(ActionCompletionPolicy.Decision.ALLOW,
                ActionCompletionPolicy.evaluate(true, 0, ledger));
    }

    @Test
    void missingAttemptGetsOneContinuation() {
        assertEquals(ActionCompletionPolicy.Decision.RETRY,
                ActionCompletionPolicy.evaluate(true, 0, ActionExecutionLedger.empty()));
    }

    @Test
    void missingAttemptAfterContinuationIsUnverified() {
        assertEquals(ActionCompletionPolicy.Decision.UNVERIFIED,
                ActionCompletionPolicy.evaluate(true, 1, ActionExecutionLedger.empty()));
    }

    @Test
    void failedActionTerminatesAsFailure() {
        ActionExecutionLedger ledger = ledger("schedule_meeting", false);
        assertEquals(ActionCompletionPolicy.Decision.FAILED,
                ActionCompletionPolicy.evaluate(true, 0, ledger));
    }

    @Test
    void successfulDisclosureCallStillRequiresAction() {
        ActionExecutionLedger ledger = ledger("load_skill", true);
        assertEquals(ActionCompletionPolicy.Decision.RETRY,
                ActionCompletionPolicy.evaluate(true, 0, ledger));
    }

    private static ActionExecutionLedger ledger(String toolName, boolean success) {
        return ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("id-1", toolName, "result", success)));
    }
}
