package vip.mate.channel.feishu;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Builds the execution trace rendered inside a Feishu CardKit streaming card.
 *
 * <p>The renderer deliberately separates user-visible progress from persisted
 * assistant content. The adapter returns only the final answer to the router,
 * while this class keeps a bounded live trace in the card: phase, plan step,
 * tool transitions, optional model thinking, and grounded stage narration.
 */
final class FeishuProgressRenderer {

    private static final int MAX_TOOL_LINES = 3;
    private static final int MAX_NARRATION_LINES = 3;
    private static final int THINKING_WINDOW = 500;
    private static final int ANSWER_WINDOW = 1200;

    private record ToolLine(String callId, String name, long startedAt,
                            Long finishedAt, boolean success) {}

    private final long startedAtMillis;
    private final boolean showThinking;
    private final boolean showToolTrace;
    private final Deque<ToolLine> toolLines = new ArrayDeque<>();
    private final Deque<String> committedNarrations = new ArrayDeque<>();
    private final StringBuilder thinkingTail = new StringBuilder();
    private final StringBuilder answerTail = new StringBuilder();

    private int collapsedToolCount;
    private boolean thinkingSeen;
    private boolean contentSeen;
    private boolean approvalPending;
    private String planStepLine;
    private String pendingNarration;

    FeishuProgressRenderer(long startedAtMillis, boolean showThinking, boolean showToolTrace) {
        this.startedAtMillis = startedAtMillis;
        this.showThinking = showThinking;
        this.showToolTrace = showToolTrace;
    }

    void onThinkingDelta(String delta) {
        thinkingSeen = true;
        if (showThinking && delta != null && !delta.isEmpty()) {
            thinkingTail.append(delta);
            trimLeading(thinkingTail, THINKING_WINDOW);
        }
    }

    void onContentDelta(String delta) {
        contentSeen = true;
        if (delta != null && !delta.isEmpty()) {
            answerTail.append(delta);
            trimLeading(answerTail, ANSWER_WINDOW);
        }
    }

    /** Returns true for transitions that should bypass the normal update throttle. */
    boolean onEvent(String eventType, Map<String, Object> data) {
        if (eventType == null) return false;
        switch (eventType) {
            case "tool_call_started" -> {
                toolLines.addLast(new ToolLine(
                        stringField(data, "toolCallId"),
                        stringField(data, "toolName"),
                        System.currentTimeMillis(), null, false));
                compactToolLines();
                return true;
            }
            case "tool_call_completed" -> {
                String callId = stringField(data, "toolCallId");
                boolean success = data == null || !Boolean.FALSE.equals(data.get("success"));
                markToolCompleted(callId, stringField(data, "toolName"), success);
                return true;
            }
            case "plan_step_started" -> {
                Object index = data != null ? data.get("index") : null;
                String title = stringField(data, "title");
                planStepLine = "📋 步骤" + (index != null ? " " + index : "")
                        + (title != null && !title.isBlank() ? "：" + title : "");
                return true;
            }
            case "tool_approval_requested" -> {
                approvalPending = true;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    void onPendingNarration(String text) {
        pendingNarration = normalize(text);
    }

    void commitNarration(String text) {
        String normalized = normalize(text);
        if (normalized == null) return;
        committedNarrations.addLast(normalized);
        while (committedNarrations.size() > MAX_NARRATION_LINES) {
            committedNarrations.removeFirst();
        }
    }

    void clearPendingNarration() {
        pendingNarration = null;
    }

    boolean isApprovalPending() {
        return approvalPending;
    }

    String snapshot() {
        StringBuilder sb = new StringBuilder();
        appendTrace(sb, statusLine(false), true);
        if (answerTail.length() > 0) {
            sb.append("\n\n---\n\n").append(answerTail);
        }
        return sb.toString();
    }

    String completedSnapshot(String finalAnswer) {
        String answer = finalAnswer == null ? "" : finalAnswer.trim();
        StringBuilder sb = new StringBuilder();
        appendTrace(sb, statusLine(true), false);
        if (!answer.isEmpty()) {
            sb.append("\n\n---\n\n").append(answer);
        } else if (approvalPending) {
            sb.append("\n\n⏸️ 已暂停，等待工具审批。");
        } else {
            sb.append("\n\n（本轮没有产生回复内容）");
        }
        return sb.toString();
    }

    private void appendTrace(StringBuilder sb, String status, boolean includePending) {
        sb.append("**执行轨迹**\n").append(status);
        if (planStepLine != null) sb.append('\n').append(planStepLine);
        appendToolLines(sb);
        for (String narration : committedNarrations) {
            sb.append("\n• ").append(narration);
        }
        if (includePending && pendingNarration != null) {
            sb.append("\n• ").append(pendingNarration);
        }
        if (showThinking && thinkingTail.length() > 0) {
            sb.append("\n\n> 💭 ")
                    .append(thinkingTail.toString().replace("\n", "\n> "));
        }
    }

    private String statusLine(boolean completed) {
        if (completed) return approvalPending ? "⏸️ 等待工具审批（" + elapsed() + "）"
                : "✅ 已完成（" + elapsed() + "）";
        if (approvalPending) return "⏸️ 等待工具审批…（" + elapsed() + "）";
        if (contentSeen) return "✍️ 正在回复…（" + elapsed() + "）";
        ToolLine running = lastRunningTool();
        if (running != null) {
            return showToolTrace
                    ? "🔧 正在调用 " + displayName(running) + "…（" + elapsed() + "）"
                    : "🔧 正在执行工具…（" + elapsed() + "）";
        }
        return (thinkingSeen ? "💭" : "🤔") + " 思考中…（" + elapsed() + "）";
    }

    private void appendToolLines(StringBuilder sb) {
        if (!showToolTrace) {
            int completed = collapsedToolCount;
            boolean running = false;
            for (ToolLine line : toolLines) {
                if (line.finishedAt() == null) running = true;
                else completed++;
            }
            if (completed > 0) sb.append("\n✅ 已执行 ").append(completed).append(" 项工具");
            if (running && contentSeen) sb.append("\n🔧 工具运行中…");
            return;
        }
        if (collapsedToolCount > 0) sb.append("\n…等 ").append(collapsedToolCount).append(" 项已完成");
        for (ToolLine line : toolLines) {
            if (line.finishedAt() == null) {
                if (contentSeen || approvalPending) sb.append("\n🔧 ").append(displayName(line)).append(" 运行中…");
            } else {
                long seconds = Math.max(0, (line.finishedAt() - line.startedAt()) / 1000);
                sb.append('\n').append(line.success() ? "✅ " : "❌ ")
                        .append(displayName(line))
                        .append(line.success() ? " 完成" : " 失败")
                        .append("（").append(seconds).append(" 秒）");
            }
        }
    }

    private ToolLine lastRunningTool() {
        ToolLine running = null;
        for (ToolLine line : toolLines) if (line.finishedAt() == null) running = line;
        return running;
    }

    private void markToolCompleted(String callId, String toolName, boolean success) {
        ToolLine match = null;
        for (ToolLine line : toolLines) {
            if (line.finishedAt() != null) continue;
            if ((callId != null && callId.equals(line.callId()))
                    || (callId == null && toolName != null && toolName.equals(line.name()))) {
                match = line;
            }
        }
        long now = System.currentTimeMillis();
        if (match == null) {
            toolLines.addLast(new ToolLine(callId, toolName, now, now, success));
        } else {
            Deque<ToolLine> rebuilt = new ArrayDeque<>(toolLines.size());
            for (ToolLine line : toolLines) {
                rebuilt.addLast(line == match
                        ? new ToolLine(match.callId(), match.name(), match.startedAt(), now, success)
                        : line);
            }
            toolLines.clear();
            toolLines.addAll(rebuilt);
        }
        compactToolLines();
    }

    private void compactToolLines() {
        while (toolLines.size() > MAX_TOOL_LINES) {
            ToolLine oldest = toolLines.peekFirst();
            if (oldest != null && oldest.finishedAt() == null) break;
            toolLines.pollFirst();
            collapsedToolCount++;
        }
    }

    private String elapsed() {
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);
        return seconds < 60 ? "已 " + seconds + " 秒"
                : "已 " + (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    private static String displayName(ToolLine line) {
        return line.name() != null && !line.name().isBlank() ? line.name() : "工具";
    }

    private static String stringField(Map<String, Object> data, String key) {
        Object value = data != null ? data.get(key) : null;
        return value != null ? value.toString() : null;
    }

    private static String normalize(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static void trimLeading(StringBuilder sb, int maxLen) {
        int excess = sb.length() - maxLen;
        if (excess > 0) sb.delete(0, excess);
    }
}
