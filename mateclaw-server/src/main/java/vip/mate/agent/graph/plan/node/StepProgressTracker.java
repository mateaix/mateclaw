package vip.mate.agent.graph.plan.node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-step progress detector for the Plan-Execute executor.
 *
 * <p>A plan step runs its own inner tool-calling loop. Without progress
 * tracking, a step can spin — repeatedly calling the same tool, or hammering
 * different variants that all fail / return nothing — until it hits the
 * tool-call ceiling, then "complete" with an empty result and let the plan
 * plow into dependent steps that have no real input.
 *
 * <p>This tracker watches the tool results of each round and recognises two
 * signature-based stall patterns:
 * <ul>
 *   <li><b>repeated failure</b> — the same call (tool name + canonical args)
 *       keeps failing, or the same tool keeps failing with different args;</li>
 *   <li><b>no progress</b> — a call keeps returning the <em>same</em> result,
 *       so re-issuing it yields nothing new.</li>
 * </ul>
 *
 * <p>Detection is graduated: at the WARN threshold it emits a one-shot nudge
 * (injected back into the step's messages so the model changes strategy);
 * past the HALT threshold it flags the step as stuck so the executor can stop
 * the inner loop and re-plan instead of advancing with junk. Thresholds are
 * deliberately low — the goal is to break a stall early, before the whole
 * tool-call budget is burned.
 *
 * <p>Not thread-safe; create one per step.
 */
public final class StepProgressTracker {

    /** Same exact call (tool + args) failing: nudge / halt thresholds. */
    static final int SAME_CALL_FAIL_WARN = 2;
    static final int SAME_CALL_FAIL_HALT = 4;
    /** Same tool failing across different args: nudge / halt thresholds. */
    static final int SAME_TOOL_FAIL_WARN = 3;
    static final int SAME_TOOL_FAIL_HALT = 6;
    /** Same call returning identical output (no new information): nudge / halt. */
    static final int NO_PROGRESS_WARN = 2;
    static final int NO_PROGRESS_HALT = 4;

    /**
     * Lower-cased markers that identify a tool result as a failure / empty
     * outcome. Kept intentionally small and language-mixed: tool errors in this
     * codebase surface as English exception text, while a few common "not
     * found" phrasings also appear in Chinese tool output.
     */
    private static final String[] FAILURE_MARKERS = {
            "execution failed", "error:", "exception", "timeout", "timed out",
            "enoent", "no such file", "not found", "authentication failed",
            "permission denied", "failed to", "未找到", "不存在", "没有找到", "执行失败", "无法"
    };

    private final Map<String, Integer> sameCallFail = new HashMap<>();
    private final Map<String, Integer> sameToolFail = new HashMap<>();
    private final Map<String, Integer> resultRepeat = new HashMap<>();
    private final Set<String> warnedKeys = new HashSet<>();

    private boolean stuck = false;
    private String haltReason = null;

    /**
     * Record one tool result from the current round.
     *
     * @param toolName the invoked tool's name (never null)
     * @param argsJson the raw arguments JSON (may be empty when unresolved)
     * @param resultText the tool's result text (may be null/empty)
     * @return a nudge to inject into the step's messages when a WARN threshold
     *         was freshly crossed, otherwise empty. Each distinct warning fires
     *         at most once.
     */
    public Optional<String> record(String toolName, String argsJson, String resultText) {
        String name = toolName == null ? "tool" : toolName;
        String args = argsJson == null ? "" : argsJson;
        String result = resultText == null ? "" : resultText;
        boolean failure = looksLikeFailure(result);

        String callSig = name + "::" + args.hashCode();
        String resultKey = callSig + "##" + result.trim().hashCode();

        // No-progress: identical result for the same call, regardless of success.
        int repeats = resultRepeat.merge(resultKey, 1, Integer::sum);
        if (repeats >= NO_PROGRESS_HALT) {
            markStuck("no_progress:" + name);
        }
        Optional<String> nudge = maybeWarn(repeats >= NO_PROGRESS_WARN, "np:" + resultKey,
                "工具 " + name + " 已连续 " + repeats + " 次返回相同结果。不要重复同样的调用——"
                        + "改用已有结果、换查询/换工具,或直接基于现有信息给出本步骤结论。");

        if (failure) {
            int callFails = sameCallFail.merge(callSig, 1, Integer::sum);
            int toolFails = sameToolFail.merge(name, 1, Integer::sum);
            if (callFails >= SAME_CALL_FAIL_HALT || toolFails >= SAME_TOOL_FAIL_HALT) {
                markStuck("repeated_failure:" + name);
            }
            if (nudge.isEmpty()) {
                nudge = maybeWarn(callFails >= SAME_CALL_FAIL_WARN, "cf:" + callSig,
                        "工具 " + name + " 用相同参数已失败 " + callFails + " 次,像是死循环。"
                                + "先看错误原因再换一种方式,不要原样重试。");
            }
            if (nudge.isEmpty()) {
                nudge = maybeWarn(toolFails >= SAME_TOOL_FAIL_WARN, "tf:" + name,
                        "工具 " + name + " 本步骤已失败 " + toolFails + " 次。停止在同一条失败路径上重试,"
                                + "换工具或换思路完成本步骤。");
            }
        }
        return nudge;
    }

    /** True once a HALT threshold was crossed — the step should stop and re-plan. */
    public boolean isStuck() {
        return stuck;
    }

    /** Machine-readable reason for the halt, or null when not stuck. */
    public String haltReason() {
        return haltReason;
    }

    private Optional<String> maybeWarn(boolean crossed, String key, String message) {
        if (crossed && warnedKeys.add(key)) {
            return Optional.of(message);
        }
        return Optional.empty();
    }

    private void markStuck(String reason) {
        if (!stuck) {
            stuck = true;
            haltReason = reason;
        }
    }

    static boolean looksLikeFailure(String result) {
        if (result == null || result.isBlank()) {
            return true; // an empty result is no progress either
        }
        String lower = result.toLowerCase();
        for (String marker : FAILURE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
