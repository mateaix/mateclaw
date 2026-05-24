package vip.mate.tool.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.progress.ProgressEntry;
import vip.mate.agent.progress.ProgressLedger;
import vip.mate.agent.progress.ProgressLedgerService;
import vip.mate.agent.progress.ProgressStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ProgressLedgerTool#progress_update} — the only mutation entry
 * the LLM has into the conversation-scoped progress ledger. Bad inputs must
 * surface as structured "Error:" strings rather than throwing, so the model
 * can recover by correcting the call instead of breaking the agent run.
 */
class ProgressLedgerToolTest {

    @AfterEach
    void clearContext() {
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("Happy path: tool result includes the full rendered snapshot for positive feedback.")
    void happyPath() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        Map<String, ProgressEntry> after = new LinkedHashMap<>();
        after.put("step_a", new ProgressEntry("step_a", "Step A",
                ProgressStatus.IN_PROGRESS, "note", Instant.now()));
        when(service.upsert(eq("conv-1"), eq("step_a"), eq("Step A"),
                eq(ProgressStatus.IN_PROGRESS), eq("starting now")))
                .thenReturn(new ProgressLedger(after));

        ProgressLedgerTool tool = new ProgressLedgerTool(service);
        String out = tool.progress_update("step_a", "Step A", "in_progress", "starting now", null);

        // Header line confirms the write so the model has a clear ack.
        assertTrue(out.startsWith("✓ Recorded step_a → in_progress (1 entries total)"), out);
        // The rendered snapshot must be appended so the model sees its own
        // update reflected in the same view the runtime injects each turn.
        assertTrue(out.contains("当前任务进度"), "expected snapshot in tool result: " + out);
        assertTrue(out.contains("Step A"), "expected entry label in snapshot: " + out);
        assertTrue(out.contains("`step_a`"), "expected bracketed key in snapshot: " + out);
        verify(service, times(1)).upsert(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("No conversation context → structured error, no DB call.")
    void missingContext() {
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        String out = tool.progress_update("step_a", "Step A", "done", null, null);
        assertTrue(out.startsWith("Error: no conversation context"), out);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Blank stepKey rejected without touching the service.")
    void blankKey() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        String out = tool.progress_update("  ", "L", "done", null, null);
        assertTrue(out.startsWith("Error: stepKey is required"), out);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Unknown status string → structured error listing valid values.")
    void unknownStatus() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        String out = tool.progress_update("step_a", "Step A", "finished", null, null);
        assertTrue(out.contains("pending"), out);
        assertTrue(out.contains("in_progress"), out);
        assertTrue(out.contains("done"), out);
        assertTrue(out.contains("blocked"), out);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Service failure surfaces as a structured error instead of throwing.")
    void serviceFailureNotPropagated() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        when(service.upsert(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("disk full"));
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        String out = tool.progress_update("step_a", "Step A", "done", null, null);
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("disk full"), out);
    }

    @Test
    @DisplayName("Empty 'note' is forwarded verbatim — service decides how to store null vs blank.")
    void emptyNoteForwarded() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        when(service.upsert(any(), any(), any(), any(), any()))
                .thenReturn(ProgressLedger.empty());
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        tool.progress_update("step_a", "Step A", "done", null, null);
        verify(service, times(1)).upsert(eq("conv-1"), eq("step_a"), eq("Step A"),
                eq(ProgressStatus.DONE), isNull());
    }
}
