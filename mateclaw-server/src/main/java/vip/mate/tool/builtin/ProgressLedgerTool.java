package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.progress.ProgressLedger;
import vip.mate.agent.progress.ProgressLedgerService;
import vip.mate.agent.progress.ProgressStatus;

/**
 * Tool exposed to the LLM for maintaining the conversation-scoped progress
 * ledger. The runtime renders the ledger into the system prompt before every
 * reasoning step, so the model can rely on this tool as the durable record
 * of "what I have done and what remains" across context-window trims.
 *
 * <p>Why a single mutating tool rather than separate
 * {@code progress_mark_done} / {@code progress_block} / etc. methods: the
 * model already volunteers the desired status as a string. Splitting into
 * per-status methods would multiply the tool schema for no gain and forces
 * a re-classification when statuses evolve.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgressLedgerTool {

    private final ProgressLedgerService service;

    @Tool(description = "Record or update a single step in the current conversation's progress "
            + "ledger. Use this to track multi-step tasks (research workflows, document drafting "
            + "split by section, etc.) — the runtime injects a rendered snapshot of the ledger "
            + "into your context before every reasoning step so you never lose track of what is "
            + "already done after a context trim. Call once per step transition: "
            + "register pending entries up front when you decompose a task, mark in_progress "
            + "before starting each one, then done as soon as it lands. Re-using the same stepKey "
            + "overwrites the entry in place (no duplicates).")
    public String progress_update(
            @ToolParam(description = "Stable identifier for this step (e.g. 'model_gpt55', "
                    + "'section_intro', 'step_pptx'). Reuse exactly to update an existing entry.")
            String stepKey,
            @ToolParam(description = "Human-readable label shown in the snapshot (e.g. "
                    + "'GPT-5.5 调研'). Pass empty to keep the existing label when updating.",
                    required = false)
            String label,
            @ToolParam(description = "One of: pending, in_progress, done, blocked.")
            String status,
            @ToolParam(description = "Optional 1-line note (why it's blocked, what was produced, "
                    + "next sub-step). Capped at ~120 chars when rendered into the snapshot.",
                    required = false)
            String note,
            @Nullable ToolContext ctx) {

        String conversationId = ToolExecutionContext.conversationId(ctx);
        if (conversationId == null || conversationId.isBlank()) {
            // Happens only on test paths that bypass the executor wiring;
            // give a structured error so the LLM doesn't loop on it.
            return "Error: no conversation context bound to this call. progress_update is only "
                    + "usable from inside an active agent run.";
        }
        if (stepKey == null || stepKey.isBlank()) {
            return "Error: stepKey is required.";
        }
        ProgressStatus parsed = ProgressStatus.parse(status);
        if (parsed == null) {
            return "Error: status must be one of pending, in_progress, done, blocked. Got: " + status;
        }
        try {
            ProgressLedger updated = service.upsert(conversationId, stepKey, label, parsed, note);
            // Return the freshly-rendered snapshot in the tool result so the
            // model immediately sees its own update reflected in the
            // canonical view it will be reading next iteration. Without this
            // positive-feedback loop the model treats progress_update as a
            // fire-and-forget side effect and stops calling it after the
            // first few transitions (observed: round-4 dropped to 3 calls in
            // 27 minutes of work). The snapshot is also what the runtime
            // injects pre-LLM-call, so echoing it here keeps the two views
            // identical.
            StringBuilder out = new StringBuilder(256);
            out.append("✓ Recorded ").append(stepKey).append(" → ").append(parsed.wireValue())
                    .append(" (").append(updated.size()).append(" entries total).\n\n");
            String snapshot = updated.renderSnapshot();
            if (snapshot != null) {
                out.append(snapshot);
            }
            return out.toString();
        } catch (Exception e) {
            log.warn("progress_update failed for conv={} key={}: {}", conversationId, stepKey, e.getMessage());
            return "Error: failed to persist progress entry — " + e.getMessage();
        }
    }
}
