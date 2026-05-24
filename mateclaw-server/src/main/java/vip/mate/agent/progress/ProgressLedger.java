package vip.mate.agent.progress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only view over a conversation's progress entries with a renderer that
 * turns the map into a compact markdown snapshot for system-prompt injection.
 *
 * <p>The snapshot is grouped by status (done → in-progress → pending →
 * blocked) and stays short on purpose: the agent reads it on every turn, so
 * spending more than ~200 tokens on it would defeat the very context
 * pressure this ledger exists to relieve.
 */
public final class ProgressLedger {

    /** Hard cap on the snapshot's note suffix so a rambling note can't bloat every turn. */
    private static final int NOTE_PREVIEW_CHARS = 120;

    private final Map<String, ProgressEntry> entries;

    public ProgressLedger(Map<String, ProgressEntry> entries) {
        this.entries = entries != null ? entries : new LinkedHashMap<>();
    }

    public static ProgressLedger empty() {
        return new ProgressLedger(new LinkedHashMap<>());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public Map<String, ProgressEntry> asMap() {
        return entries;
    }

    /**
     * @return the most recent {@code updatedAt} across all entries, or empty
     *         when the ledger is empty / all entries lack a timestamp.
     */
    public Optional<Instant> mostRecentUpdate() {
        Instant max = null;
        for (ProgressEntry e : entries.values()) {
            Instant t = e.getUpdatedAt();
            if (t != null && (max == null || t.isAfter(max))) {
                max = t;
            }
        }
        return Optional.ofNullable(max);
    }

    /** Iteration before which no stale reminder is ever issued — too early to judge. */
    private static final int STALE_WARMUP_ITERATIONS = 10;

    /** Iteration past which an empty ledger triggers a "you should register steps" reminder. */
    private static final int EMPTY_LEDGER_NUDGE_ITERATIONS = 15;

    /** Wall-clock gap that flips a non-empty ledger from "fresh" to "stale". */
    private static final long STALE_GAP_SECONDS = 90;

    /**
     * Build a stale-reminder string for injection into the model's context
     * when the ledger appears to be falling behind the actual reasoning
     * progress. Returns {@code null} when the ledger is being maintained
     * normally so the caller can skip the injection.
     *
     * <p>Trigger heuristics — derived from round-4 of the LLM-review smoke
     * test, where the model stopped calling {@code progress_update} after
     * the first 30s and silently fell out of the ledger discipline:
     *
     * <ul>
     *   <li><strong>Warm-up</strong>: {@code currentIteration < 10} → never
     *       remind, the model is still setting up the task.</li>
     *   <li><strong>Empty ledger</strong>: {@code currentIteration ≥ 15} and
     *       no entries at all → likely a multi-step task being executed
     *       without any ledger discipline.</li>
     *   <li><strong>Stale updates</strong>: ledger has entries, but the
     *       most recent {@code updatedAt} is &gt; 90 s ago → ledger is no
     *       longer tracking the real work.</li>
     * </ul>
     *
     * @param currentIteration the agent's current ReAct iteration count
     * @param now              the reference instant for staleness ("now");
     *                         injected for testability
     */
    public String renderStaleReminder(int currentIteration, Instant now) {
        if (currentIteration < STALE_WARMUP_ITERATIONS) {
            return null;
        }
        if (entries.isEmpty()) {
            if (currentIteration < EMPTY_LEDGER_NUDGE_ITERATIONS) {
                return null;
            }
            return "## ⚠️ 进度账本是空的（已运行 " + currentIteration + " 轮）\n\n"
                    + "你正在进行一个看起来需要拆解的多步任务，但还没有调用 `progress_update`。\n"
                    + "**立即用一条并行 tool_calls 回复批量注册所有 pending 步骤**，否则上下文\n"
                    + "窗口被裁剪后，你会忘记自己做过的工作并重复执行。";
        }
        Optional<Instant> lastUpdate = mostRecentUpdate();
        if (lastUpdate.isEmpty()) {
            return null;
        }
        long gap = java.time.Duration.between(lastUpdate.get(), now).getSeconds();
        if (gap < STALE_GAP_SECONDS) {
            return null;
        }
        int done = (int) entries.values().stream()
                .filter(e -> e.getStatus() == ProgressStatus.DONE).count();
        int inProgress = (int) entries.values().stream()
                .filter(e -> e.getStatus() == ProgressStatus.IN_PROGRESS).count();
        return "## ⚠️ 进度账本已 " + gap + " 秒未更新\n\n"
                + "你已运行 " + currentIteration + " 轮，但 progress_update 已经 "
                + gap + " 秒（约 " + (gap / 60) + " 分钟）没被调用过。\n"
                + "当前账本：" + done + " done / " + inProgress + " in_progress / "
                + (entries.size() - done - inProgress) + " pending。\n\n"
                + "**立即做以下一件事**（不要再 read_file 或 browser_use，先更新账本）：\n"
                + "- 把已经完成的子步骤切到 `done`（如果你能看到工作区文件已生成）\n"
                + "- 把正在做的步骤切到 `in_progress`\n"
                + "- 有阻塞切到 `blocked` + 写明原因\n"
                + "不维护账本会导致重复工作 / 漏做项目 / 撞迭代上限。";
    }

    /**
     * @return a compact, model-readable progress snapshot, or {@code null}
     *         when the ledger is empty so the caller can skip injection
     *         entirely (no "(empty)" placeholder noise).
     */
    public String renderSnapshot() {
        if (entries.isEmpty()) {
            return null;
        }
        List<ProgressEntry> done = bucket(ProgressStatus.DONE);
        List<ProgressEntry> inProgress = bucket(ProgressStatus.IN_PROGRESS);
        List<ProgressEntry> pending = bucket(ProgressStatus.PENDING);
        List<ProgressEntry> blocked = bucket(ProgressStatus.BLOCKED);

        StringBuilder sb = new StringBuilder(256);
        sb.append("## 当前任务进度（执行参考，权威记录）\n\n");
        appendBucket(sb, "✅ 已完成", done);
        appendBucket(sb, "🔄 进行中", inProgress);
        appendBucket(sb, "⏳ 待办", pending);
        appendBucket(sb, "⛔ 受阻", blocked);
        sb.append("\n请基于此进度继续推进；已完成的步骤不要重复执行。")
                .append("完成新步骤后调用 `progress_update` 工具更新本账本。");
        return sb.toString();
    }

    private List<ProgressEntry> bucket(ProgressStatus status) {
        List<ProgressEntry> out = new ArrayList<>();
        for (ProgressEntry e : entries.values()) {
            if (e.getStatus() == status) {
                out.add(e);
            }
        }
        return out;
    }

    private void appendBucket(StringBuilder sb, String header, Collection<ProgressEntry> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append(header).append(" (").append(items.size()).append("):\n");
        for (ProgressEntry e : items) {
            String label = e.getLabel() != null && !e.getLabel().isBlank() ? e.getLabel() : e.getKey();
            sb.append("- ").append(label).append(" [`").append(e.getKey()).append("`]");
            String note = e.getNote();
            if (note != null && !note.isBlank()) {
                String trimmed = note.length() > NOTE_PREVIEW_CHARS
                        ? note.substring(0, NOTE_PREVIEW_CHARS) + "…"
                        : note;
                sb.append(" — ").append(trimmed);
            }
            sb.append('\n');
        }
        sb.append('\n');
    }
}
