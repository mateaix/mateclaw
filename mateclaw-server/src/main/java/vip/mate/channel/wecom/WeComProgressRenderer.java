package vip.mate.channel.wecom;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Builds the live progress text shown in the WeCom stream bubble while an
 * agent turn is running: a status header (thinking / tool call / replying,
 * with elapsed time), the most recent tool-call lines, an optional rolling
 * window of reasoning text, and the tail of the answer produced so far.
 * <p>
 * Mutations arrive from the stream-consuming thread; {@link #snapshot()} may
 * also be called from the keepalive scheduler thread, so all state access is
 * synchronized on this instance.
 */
final class WeComProgressRenderer {

    /** Max completed/running tool lines rendered before collapsing to a counter. */
    private static final int MAX_TOOL_LINES = 3;

    /** Rolling window (chars) of reasoning text when thinking display is on. */
    private static final int THINKING_WINDOW = 500;

    /** Tail window (chars) of the streamed answer kept inside the bubble.
     *  The full answer is delivered by the final render pass; the bubble only
     *  needs enough to show live progress while staying under the 2048 limit. */
    private static final int ANSWER_WINDOW = 1200;

    private record ToolLine(String callId, String name, long startedAt,
                            Long finishedAt, boolean success) {
    }

    private final long startedAtMillis;
    private final boolean showThinking;

    private final Deque<ToolLine> toolLines = new ArrayDeque<>();
    private int collapsedToolCount;
    private final StringBuilder thinkingTail = new StringBuilder();
    private final StringBuilder answerTail = new StringBuilder();
    private boolean thinkingSeen;
    private boolean contentSeen;
    private boolean approvalPending;
    private String planStepLine;

    WeComProgressRenderer(long startedAtMillis, boolean showThinking) {
        this.startedAtMillis = startedAtMillis;
        this.showThinking = showThinking;
    }

    synchronized void onThinkingDelta(String delta) {
        thinkingSeen = true;
        if (showThinking && delta != null && !delta.isEmpty()) {
            thinkingTail.append(delta);
            trimLeading(thinkingTail, THINKING_WINDOW);
        }
    }

    synchronized void onContentDelta(String delta) {
        contentSeen = true;
        if (delta != null && !delta.isEmpty()) {
            answerTail.append(delta);
            trimLeading(answerTail, ANSWER_WINDOW);
        }
    }

    /**
     * Consume a graph event. Returns true when the event changes what the
     * bubble shows in a way worth flushing immediately (tool transitions,
     * plan steps, approval waits) rather than waiting for the throttle tick.
     */
    synchronized boolean onEvent(String eventType, Map<String, Object> data) {
        if (eventType == null) {
            return false;
        }
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
                        + (title != null && !title.isBlank() ? ": " + title : "");
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

    /** Render the current progress text for the stream bubble. */
    synchronized String snapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append(statusLine());
        if (planStepLine != null) {
            sb.append('\n').append(planStepLine);
        }
        appendToolLines(sb);
        if (showThinking && !contentSeen && thinkingTail.length() > 0) {
            sb.append("\n\n> 💭 ").append(thinkingTail.toString().replace("\n", "\n> "));
        }
        if (answerTail.length() > 0) {
            sb.append("\n\n").append(answerTail);
        }
        return sb.toString();
    }

    private String statusLine() {
        if (approvalPending) {
            return "⏸️ 等待工具审批…（" + elapsed() + "）";
        }
        if (contentSeen) {
            return "✍️ 正在回复…（" + elapsed() + "）";
        }
        ToolLine running = lastRunningTool();
        if (running != null) {
            return "🔧 正在调用 " + displayName(running) + "…（" + elapsed() + "）";
        }
        if (thinkingSeen) {
            return "💭 思考中…（" + elapsed() + "）";
        }
        return "🤔 思考中…（" + elapsed() + "）";
    }

    private void appendToolLines(StringBuilder sb) {
        if (collapsedToolCount > 0) {
            sb.append("\n…等 ").append(collapsedToolCount).append(" 项已完成");
        }
        for (ToolLine line : toolLines) {
            if (line.finishedAt() == null) {
                // The running tool is already the status header — skip here
                // unless content started (header shows "正在回复" instead).
                if (contentSeen || approvalPending) {
                    sb.append("\n🔧 ").append(displayName(line)).append(" 运行中…");
                }
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
        for (ToolLine line : toolLines) {
            if (line.finishedAt() == null) {
                running = line;
            }
        }
        return running;
    }

    private void markToolCompleted(String callId, String toolName, boolean success) {
        ToolLine match = null;
        for (ToolLine line : toolLines) {
            if (line.finishedAt() != null) {
                continue;
            }
            boolean idMatch = callId != null && callId.equals(line.callId());
            boolean nameMatch = callId == null && toolName != null && toolName.equals(line.name());
            if (idMatch || nameMatch) {
                match = line;
            }
        }
        if (match == null) {
            toolLines.addLast(new ToolLine(callId, toolName,
                    System.currentTimeMillis(), System.currentTimeMillis(), success));
        } else {
            ToolLine done = new ToolLine(match.callId(), match.name(),
                    match.startedAt(), System.currentTimeMillis(), success);
            replaceLine(match, done);
        }
        compactToolLines();
    }

    private void replaceLine(ToolLine oldLine, ToolLine newLine) {
        Deque<ToolLine> rebuilt = new ArrayDeque<>(toolLines.size());
        for (ToolLine line : toolLines) {
            rebuilt.addLast(line == oldLine ? newLine : line);
        }
        toolLines.clear();
        toolLines.addAll(rebuilt);
    }

    /** Keep at most {@link #MAX_TOOL_LINES}; older completed lines collapse into a counter. */
    private void compactToolLines() {
        while (toolLines.size() > MAX_TOOL_LINES) {
            ToolLine oldest = toolLines.peekFirst();
            if (oldest != null && oldest.finishedAt() == null) {
                // Never collapse a still-running tool line.
                break;
            }
            toolLines.pollFirst();
            collapsedToolCount++;
        }
    }

    private String elapsed() {
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);
        if (seconds < 60) {
            return "已 " + seconds + " 秒";
        }
        return "已 " + (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    private static String displayName(ToolLine line) {
        return line.name() != null && !line.name().isBlank() ? line.name() : "工具";
    }

    private static String stringField(Map<String, Object> data, String key) {
        Object value = data != null ? data.get(key) : null;
        return value != null ? value.toString() : null;
    }

    private static void trimLeading(StringBuilder sb, int maxLen) {
        int excess = sb.length() - maxLen;
        if (excess > 0) {
            sb.delete(0, excess);
        }
    }
}
