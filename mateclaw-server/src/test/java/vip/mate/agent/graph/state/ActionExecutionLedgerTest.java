package vip.mate.agent.graph.state;

import org.junit.jupiter.api.Test;
import vip.mate.agent.GraphEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionExecutionLedgerTest {

    @Test
    void successfulBusinessToolSatisfiesActionCompletion() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call-1", "schedule_meeting", "created", true)));

        assertTrue(ledger.hasSubstantiveAttempt());
        assertTrue(ledger.hasSuccessfulSubstantiveCall());
        assertEquals(ActionExecutionLedger.Status.SUCCEEDED,
                ledger.receipts().get("call-1").status());
    }

    @Test
    void failedBusinessToolIsAnAttemptButNotSuccessfulEvidence() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call-2", "schedule_meeting", "HTTP 500", false)));

        assertTrue(ledger.hasSubstantiveAttempt());
        assertFalse(ledger.hasSuccessfulSubstantiveCall());
        assertEquals(ActionExecutionLedger.Status.FAILED,
                ledger.receipts().get("call-2").status());
    }

    @Test
    void disclosureToolsNeverSatisfyActionCompletion() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("load-1", "load_skill", "skill body", true),
                GraphEventPublisher.toolComplete("enable-1", "enable_tool", "enabled", true)));

        assertFalse(ledger.hasSubstantiveAttempt());
        assertFalse(ledger.hasSuccessfulSubstantiveCall());
        assertEquals(2, ledger.receipts().size());
    }

    @Test
    void mergePreservesReceiptsAcrossIterations() {
        ActionExecutionLedger first = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("load-1", "load_skill", "skill body", true)));
        ActionExecutionLedger second = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call-3", "schedule_meeting", "created", true)));

        ActionExecutionLedger merged = first.merge(second);

        assertEquals(2, merged.receipts().size());
        assertTrue(merged.hasSuccessfulSubstantiveCall());
    }
}
