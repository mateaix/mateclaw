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
 *
 * <p>Three entry classes coexist:
 * <ul>
 *   <li><b>Regular entries</b> — LLM-controlled via the {@code progress_update}
 *       tool. The model registers steps, advances their status, and adds
 *       notes. These are what {@link #mostRecentUpdate()} and the stale
 *       reminder consider when judging whether the ledger is maintained.</li>
 *   <li><b>Pinned entries</b> — Java-controlled, written by ActionNode when
 *       {@code load_skill} is called (B2). They carry the skill's structured
 *       constraints (from {@code SkillManifest.constraints}) and survive
 *       context compression because they live in {@code nonHistoryPrefix}.
 *       The LLM's {@code progress_update} tool never touches them.</li>
 *   <li><b>Auto-recorded entries</b> — Java-controlled, written by ActionNode
 *       after a successful tool call (B5). They use the
 *       {@link #AUTO_RECORDED_PREFIX} on their key so the renderer can group
 *       them separately. They don't affect staleness calculation.</li>
 * </ul>
 */
public final class ProgressLedger {

    /** Hard cap on the snapshot's note suffix so a rambling note can't bloat every turn. */
    private static final int NOTE_PREVIEW_CHARS = 120;

    /**
     * Key prefix for entries auto-recorded by ActionNode after successful
     * tool calls (B5). Lets the renderer group them into a separate
     * "auto-recorded" section and exclude them from staleness calculation.
     */
    public static final String AUTO_RECORDED_PREFIX = "auto_";

    private final Map<String, ProgressEntry> entries;
    private final Map<String, ProgressEntry> pinned;

    public ProgressLedger(Map<String, ProgressEntry> entries) {
        this(entries, null);
    }

    public ProgressLedger(Map<String, ProgressEntry> entries, Map<String, ProgressEntry> pinned) {
        this.entries = entries != null ? entries : new LinkedHashMap<>();
        this.pinned = pinned != null ? pinned : new LinkedHashMap<>();
    }

    public static ProgressLedger empty() {
        return new ProgressLedger(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    public boolean isEmpty() {
        return entries.isEmpty() && pinned.isEmpty();
    }

    public int size() {
        return entries.size() + pinned.size();
    }

    public Map<String, ProgressEntry> asMap() {
        return entries;
    }

    /** @return the pinned (Java-controlled) entries, never null. */
    public Map<String, ProgressEntry> pinnedEntries() {
        return pinned;
    }

    /**
     * @return the most recent {@code updatedAt} across regular (non-auto,
     *         non-pinned) entries, or empty when none have a timestamp.
     *         Only regular entries count because pinned entries are static
     *         constraints and auto-recorded entries are Java-side — neither
     *         indicates the LLM is maintaining the ledger.
     */
    public Optional<Instant> mostRecentUpdate() {
        Instant max = null;
        for (ProgressEntry e : entries.values()) {
            if (e.getKey() != null && e.getKey().startsWith(AUTO_RECORDED_PREFIX)) {
                continue;
            }
            Instant t = e.getUpdatedAt();
            if (t != null && (max == null || t.isAfter(max))) {
                max = t;
            }
        }
        return Optional.ofNullable(max);
    }

    /**
     * Iteration before which no stale reminder is ever issued — too early to judge.
     */
    private static final int STALE_WARMUP_ITERATIONS = 3;
    private static final int EMPTY_LEDGER_NUDGE_ITERATIONS = 5;
    private static final long STALE_GAP_SECONDS = 45;

    public String renderStaleReminder(int currentIteration, Instant now) {
        if (currentIteration < STALE_WARMUP_ITERATIONS) {
            return null;
        }
        // Only regular (non-auto) entries indicate LLM engagement with the ledger.
        boolean hasRegularEntries = entries.values().stream()
                .anyMatch(e -> e.getKey() == null || !e.getKey().startsWith(AUTO_RECORDED_PREFIX));
        if (!hasRegularEntries) {
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
        long done = entries.values().stream()
                .filter(e -> isRegular(e) && e.getStatus() == ProgressStatus.DONE).count();
        long inProgress = entries.values().stream()
                .filter(e -> isRegular(e) && e.getStatus() == ProgressStatus.IN_PROGRESS).count();
        long pending = entries.values().stream()
                .filter(e -> isRegular(e) && e.getStatus() == ProgressStatus.PENDING).count();
        return "## ⚠️ 进度账本已 " + gap + " 秒未更新\n\n"
                + "你已运行 " + currentIteration + " 轮，但 progress_update 已经 "
                + gap + " 秒（约 " + (gap / 60) + " 分钟）没被调用过。\n"
                + "当前账本：" + done + " done / " + inProgress + " in_progress / "
                + pending + " pending。\n\n"
                + "**立即做以下一件事**（不要再 read_file 或 browser_use，先更新账本）：\n"
                + "- 把已经完成的子步骤切到 `done`（如果你能看到工作区文件已生成）\n"
                + "- 把正在做的步骤切到 `in_progress`\n"
                + "- 有阻塞切到 `blocked` + 写明原因\n"
                + "不维护账本会导致重复工作 / 漏做项目 / 撞迭代上限。";
    }

    /** True when the entry is regular (not auto-recorded, not pinned). */
    private boolean isRegular(ProgressEntry e) {
        return e.getKey() == null || !e.getKey().startsWith(AUTO_RECORDED_PREFIX);
    }

    /**
     * @return a compact, model-readable progress snapshot, or {@code null}
     *         when the ledger is empty so the caller can skip injection
     *         entirely (no "(empty)" placeholder noise).
     */
    public String renderSnapshot() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("## 当前任务进度（执行参考，权威记录）\n\n");

        // Pinned constraints (highest priority — always visible)
        if (!pinned.isEmpty()) {
            sb.append("🔒 固定约束（来自 skill，全程不可忽略）:\n");
            for (ProgressEntry e : pinned.values()) {
                String label = e.getLabel() != null && !e.getLabel().isBlank()
                        ? e.getLabel() : e.getKey();
                sb.append("- ").append(label);
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

        // Auto-recorded tool completions (B5)
        List<ProgressEntry> autoRecorded = new ArrayList<>();
        for (ProgressEntry e : entries.values()) {
            if (e.getKey() != null && e.getKey().startsWith(AUTO_RECORDED_PREFIX)) {
                autoRecorded.add(e);
            }
        }
        if (!autoRecorded.isEmpty()) {
            sb.append("🔧 自动记录（工具调用完成）:\n");
            for (ProgressEntry e : autoRecorded) {
                String label = e.getLabel() != null && !e.getLabel().isBlank()
                        ? e.getLabel() : e.getKey();
                sb.append("- ").append(label).append(" → ").append(e.getStatus());
                sb.append('\n');
            }
            sb.append('\n');
        }

        // Regular entries grouped by status
        List<ProgressEntry> done = bucketRegular(ProgressStatus.DONE);
        List<ProgressEntry> inProgress = bucketRegular(ProgressStatus.IN_PROGRESS);
        List<ProgressEntry> pending = bucketRegular(ProgressStatus.PENDING);
        List<ProgressEntry> blocked = bucketRegular(ProgressStatus.BLOCKED);
        appendBucket(sb, "✅ 已完成", done);
        appendBucket(sb, "🔄 进行中", inProgress);
        appendBucket(sb, "⏳ 待办", pending);
        appendBucket(sb, "⛔ 受阻", blocked);
        sb.append("\n请基于此进度继续推进；已完成的步骤不要重复执行。")
                .append("完成新步骤后调用 `progress_update` 工具更新本账本。");
        return sb.toString();
    }

    private List<ProgressEntry> bucketRegular(ProgressStatus status) {
        List<ProgressEntry> out = new ArrayList<>();
        for (ProgressEntry e : entries.values()) {
            if (isRegular(e) && e.getStatus() == status) {
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
