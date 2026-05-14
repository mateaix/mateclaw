package vip.mate.agent.graph.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * Configuration for the tool-result three-layer budget (RFC-008 Phase 3).
 *
 * <p>Layer 1 — per-tool cap — is implemented inside each tool itself.
 * Layer 2 — per-result spill — when a single tool result exceeds {@link #perResultThresholdChars}
 * the full output is written to disk and only a {@link #previewHeadChars} preview
 * (plus a pointer line) is sent back to the LLM.
 * Layer 3 — per-turn aggregate budget — after all tools in a turn complete, if
 * the cumulative response size exceeds {@link #perTurnBudgetChars}, the largest
 * non-spilled responses are spilled in turn until the aggregate fits.</p>
 *
 * <p>Spill files live under {@link #storageBaseDir} when set, otherwise under
 * {@code <workspaceBasePath>/.mateclaw/tool-results/<conversationId>/} when a
 * workspace is bound to the agent, otherwise under
 * {@code ${java.io.tmpdir}/mateclaw/tool-results/<conversationId>/}.</p>
 *
 * <pre>
 * mate:
 *   agent:
 *     tool-result:
 *       enabled: true
 *       per-result-threshold-chars: 16000
 *       per-turn-budget-chars: 32000
 *       preview-head-chars: 800
 *       excluded-tool-inline-chars: 4000
 *       storage-base-dir:
 * </pre>
 */
@ConfigurationProperties(prefix = "mate.agent.tool-result")
public class ToolResultProperties {

    /** Master switch. When false, the executor falls back to plain truncation. */
    private boolean enabled = true;

    /**
     * Per-result spill threshold. A single tool result larger than this is
     * spilled to disk and the in-context view is replaced with a short
     * preview + path so the model can call {@code read_file} on demand.
     *
     * <p>Aligned with {@code ToolExecutionExecutor.MAX_TOOL_RESULT_CHARS}
     * (8000): the executor now tries to spill the RAW result first; only
     * when spilling is disabled, the tool is on {@link #excludedTools}, the
     * body is under this threshold, or the disk write fails, does it fall
     * back to truncating inline to 8000 chars. Keeping the threshold equal
     * to the truncate cap yields a single semantic ladder — above the
     * threshold means "preserved on disk", at-or-below means "stays inline
     * verbatim".
     *
     * <p>If you want to keep more text inline before spilling, raise this
     * value AND raise the executor's hard cap together; otherwise the
     * 8000-char fallback truncate would silently shorten anything between
     * this threshold and 8000 even when spill is disabled, defeating the
     * intent.
     */
    private int perResultThresholdChars = 8000;

    /**
     * Layer 3 — aggregate cap on combined response size in one tool turn.
     * After all tools complete, the largest non-spilled responses are spilled
     * in turn until the cumulative size fits this budget.
     */
    private int perTurnBudgetChars = 32000;  // was 16000 — headroom for multi-tool turns

    /** Number of leading characters kept inline as a preview after spilling. */
    private int previewHeadChars = 800;

    /**
     * Retrieval-style tools are not spilled, but their inline content still must
     * fit the model context. When aggregate turn budget is exceeded and only
     * excluded tools remain, their results are compacted to this size.
     */
    private int excludedToolInlineChars = 2500;

    /**
     * Optional absolute path to override the default spill location.
     * When blank, falls back to {@code <workspace>/.mateclaw/tool-results/} or
     * {@code ${java.io.tmpdir}/mateclaw/tool-results/}.
     */
    private String storageBaseDir = "";

    /**
     * Tools whose results must NEVER be spilled. These are the tools the agent
     * uses to <i>retrieve</i> spilled content — spilling their output would
     * cause infinite recursion (read spill path → produces another spill →
     * agent reads new spill → …) and starve {@code MAX_TOOL_CALLS_PER_STEP}.
     *
     * <p>Defaults to file-read tools that already cap their own output internally.
     * Configurable so deployments can add more retrieval-style tools (e.g.,
     * MCP-provided readers) without code changes.</p>
     */
    private List<String> excludedTools = List.of("read_file", "read_workspace_memory_file");

    /**
     * Days to retain spill files before the scheduled cleanup deletes them.
     * <p><b>Default 0 means time-based cleanup is disabled</b> — spill files
     * stay on disk until the owning conversation is explicitly deleted (which
     * fires {@code purgeConversation} via {@code ConversationService}).
     * This preserves the "recoverable" invariant: a summary or preview that
     * cites a spill path will keep working for the whole life of the
     * conversation, no matter how long it sits dormant.
     * <p>Set to a positive value if disk pressure outweighs recoverability
     * for your deployment. The scheduled sweep will then delete files whose
     * mtime falls outside the retention horizon.
     */
    private int retentionDays = 0;

    /**
     * Cron expression for the spill-cleanup task. Defaults to once a day at
     * 03:00 server-local time so cleanup runs during quiet hours. Set this
     * to a Spring-recognised value (six-field cron) or change the bean
     * wiring to disable it entirely.
     */
    private String cleanupCron = "0 0 3 * * ?";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPerResultThresholdChars() { return perResultThresholdChars; }
    public void setPerResultThresholdChars(int perResultThresholdChars) {
        this.perResultThresholdChars = perResultThresholdChars;
    }

    public int getPerTurnBudgetChars() { return perTurnBudgetChars; }
    public void setPerTurnBudgetChars(int perTurnBudgetChars) {
        this.perTurnBudgetChars = perTurnBudgetChars;
    }

    public int getPreviewHeadChars() { return previewHeadChars; }
    public void setPreviewHeadChars(int previewHeadChars) {
        this.previewHeadChars = previewHeadChars;
    }

    public int getExcludedToolInlineChars() { return excludedToolInlineChars; }
    public void setExcludedToolInlineChars(int excludedToolInlineChars) {
        this.excludedToolInlineChars = excludedToolInlineChars;
    }

    public String getStorageBaseDir() { return storageBaseDir; }
    public void setStorageBaseDir(String storageBaseDir) {
        this.storageBaseDir = storageBaseDir == null ? "" : storageBaseDir;
    }

    public List<String> getExcludedTools() { return excludedTools; }
    public void setExcludedTools(List<String> excludedTools) {
        this.excludedTools = excludedTools == null ? List.of() : excludedTools;
    }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron == null ? "" : cleanupCron;
    }

    /** O(1) membership test for the exclusion list, used on every tool result. */
    public Set<String> excludedToolsSet() {
        return Set.copyOf(excludedTools);
    }
}
