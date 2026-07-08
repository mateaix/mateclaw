package vip.mate.agent.graph.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tool-call loop guard — pure, side-effect-free decision logic that detects
 * a ReAct loop stuck on repetitive tool calls.
 * <p>
 * Three independent detectors, each keyed on a tool-call signature
 * ({@code toolName + ":" + sha256(canonicalized args JSON)}):
 * <ol>
 *   <li><b>Identical-argument repeated failure</b> — the model retries the
 *       exact same failing call without reading the error. Warn early, halt
 *       when clearly stuck.</li>
 *   <li><b>Per-tool repeated failure</b> (arguments ignored) — the model
 *       keeps guessing slightly different arguments against the same broken
 *       tool ("path-guessing" loops).</li>
 *   <li><b>Idempotent no-progress</b> — a read-only tool keeps returning the
 *       byte-identical result; the model should use what it already has.
 *       Restricted to a known read-only tool set so legitimate repeated
 *       writes are never flagged.</li>
 * </ol>
 * Warnings are meant to be appended to the observation text so the model can
 * self-correct on the next reasoning turn; a halt is meant to be routed to
 * the graceful wrap-up node. Executing those side effects is the caller's
 * (ObservationNode's) job — this class only counts and decides, which keeps
 * it trivially unit-testable.
 * <p>
 * Counters live in graph state under
 * {@link vip.mate.agent.graph.state.MateClawStateKeys#TOOL_LOOP_STATS} and are
 * scoped to a single graph run.
 *
 * @author MateClaw Team
 */
public final class ToolLoopGuard {

    /** Identical tool + identical args failing: warn from the 2nd failure, halt at the 5th. */
    static final int EXACT_FAILURE_WARN_AFTER = 2;
    static final int EXACT_FAILURE_HALT_AFTER = 5;

    /** Same tool failing regardless of args: warn from the 3rd failure, halt at the 8th. */
    static final int SAME_TOOL_FAILURE_WARN_AFTER = 3;
    static final int SAME_TOOL_FAILURE_HALT_AFTER = 8;

    /** Idempotent tool returning the identical result: warn from the 2nd repeat, halt at the 5th. */
    static final int NO_PROGRESS_WARN_AFTER = 2;
    static final int NO_PROGRESS_HALT_AFTER = 5;

    /**
     * Read-only tools eligible for no-progress detection. Mutating tools are
     * deliberately excluded — calling {@code write_file} twice with the same
     * content is legitimate (e.g. after an external revert).
     */
    static final Set<String> IDEMPOTENT_TOOLS = Set.of(
            "read_file", "web_search", "extract_document_text", "extract_pdf_text");

    /** Counter-key prefixes inside the stats map. */
    private static final String KEY_EXACT_FAILURE = "ef:";
    private static final String KEY_TOOL_FAILURE = "tf:";
    private static final String KEY_NO_PROGRESS_HASH = "nph:";
    private static final String KEY_NO_PROGRESS_COUNT = "npc:";

    /**
     * Failure heuristic, aligned with how tool errors actually surface:
     * the executor's exception path ({@code "Tool execution failed: …"}), the
     * guard-block path ({@code "[安全拦截] …"}), tools' own error prefixes, and
     * structured JSON errors ({@code "error": <non-empty>} / {@code "success": false}).
     */
    // The possessive \s*+ prevents backtracking from letting the lookahead
    // land on a whitespace char and misclassify {"error": null} as a failure.
    private static final Pattern JSON_ERROR_PATTERN = Pattern.compile(
            "\"error\"\\s*:\\s*+(?!null|\"\")|\"success\"\\s*:\\s*false");

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ToolLoopGuard() {
    }

    /**
     * Outcome of one observation round.
     *
     * @param stats      updated counter map to write back to graph state
     * @param warnings   guidance lines to append to the observation text (may be empty)
     * @param haltReason non-null when a detector crossed its halt threshold;
     *                   the text is suitable for the graph ERROR slot
     */
    public record Evaluation(Map<String, Object> stats, List<String> warnings, String haltReason) {
        public boolean shouldHalt() {
            return haltReason != null;
        }
    }

    /**
     * Evaluate one tool batch. Pairs calls with results by tool-call id
     * (falling back to list order), updates the counters, and returns the
     * warnings / halt decision for this round.
     *
     * @param previousStats counter map from the previous round (never mutated)
     * @param toolCalls     the batch the model requested this round
     * @param toolResults   the corresponding execution results
     */
    public static Evaluation evaluate(Map<String, Object> previousStats,
                                      List<AssistantMessage.ToolCall> toolCalls,
                                      List<ToolResponseMessage.ToolResponse> toolResults) {
        Map<String, Object> stats = new HashMap<>(previousStats == null ? Map.of() : previousStats);
        List<String> warnings = new ArrayList<>();
        String haltReason = null;

        if (toolResults == null || toolResults.isEmpty()) {
            return new Evaluation(stats, warnings, null);
        }

        for (ToolResponseMessage.ToolResponse result : toolResults) {
            String toolName = result.name();
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            String arguments = findArguments(toolCalls, result);
            String signature = toolName + ":" + hash(canonicalizeArguments(arguments));
            boolean failed = isFailure(result.responseData());

            if (failed) {
                int exactCount = increment(stats, KEY_EXACT_FAILURE + signature);
                int toolCount = increment(stats, KEY_TOOL_FAILURE + toolName);

                if (exactCount >= EXACT_FAILURE_HALT_AFTER) {
                    haltReason = "工具调用陷入循环：" + toolName + " 已连续 " + exactCount
                            + " 次以相同参数失败，已强制收尾";
                } else if (toolCount >= SAME_TOOL_FAILURE_HALT_AFTER) {
                    haltReason = "工具调用陷入循环：" + toolName + " 本次运行累计失败 " + toolCount
                            + " 次，已强制收尾";
                } else if (exactCount >= EXACT_FAILURE_WARN_AFTER) {
                    warnings.add("[🔁 循环警告] 工具 " + toolName + " 已连续 " + exactCount
                            + " 次以相同参数失败。请勿原样重试：分析上面的错误信息并改变策略"
                            + "（调整参数或改用其他工具），或向用户说明具体阻塞点。");
                } else if (toolCount >= SAME_TOOL_FAILURE_WARN_AFTER) {
                    warnings.add("[🔁 循环警告] 工具 " + toolName + " 本次运行已失败 " + toolCount
                            + " 次。请先诊断根因（检查路径、参数、前置条件）再继续，不要盲目换参数重试。");
                }
            } else {
                // A success clears the failure streaks for this call shape / tool.
                stats.remove(KEY_EXACT_FAILURE + signature);
                stats.remove(KEY_TOOL_FAILURE + toolName);

                if (IDEMPOTENT_TOOLS.contains(toolName)) {
                    String resultHash = hash(result.responseData());
                    String lastHash = (String) stats.get(KEY_NO_PROGRESS_HASH + signature);
                    int repeatCount = resultHash.equals(lastHash)
                            ? increment(stats, KEY_NO_PROGRESS_COUNT + signature)
                            : resetNoProgress(stats, signature);
                    stats.put(KEY_NO_PROGRESS_HASH + signature, resultHash);

                    if (repeatCount >= NO_PROGRESS_HALT_AFTER) {
                        haltReason = "工具调用陷入循环：" + toolName + " 已连续 " + repeatCount
                                + " 次返回完全相同的结果，已强制收尾";
                    } else if (repeatCount >= NO_PROGRESS_WARN_AFTER) {
                        warnings.add("[🔁 循环提示] 工具 " + toolName + " 已连续 " + repeatCount
                                + " 次返回完全相同的结果。请直接使用已获得的结果继续任务，不要重复调用。");
                    }
                }
            }
        }
        return new Evaluation(Map.copyOf(stats), List.copyOf(warnings), haltReason);
    }

    /** Heuristic: does this tool response text represent a failure? */
    public static boolean isFailure(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return false;
        }
        String head = responseData.substring(0, Math.min(responseData.length(), 300)).strip();
        String lower = head.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tool execution failed")
                || head.startsWith("[安全拦截]")
                || lower.startsWith("error:")
                || head.startsWith("错误：")
                || head.startsWith("错误:")) {
            return true;
        }
        return head.startsWith("{") && JSON_ERROR_PATTERN.matcher(head).find();
    }

    /**
     * Canonicalize an arguments JSON string so key order and whitespace do not
     * change the signature. Falls back to the trimmed raw string when the
     * arguments are not parseable JSON.
     */
    static String canonicalizeArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            Object parsed = CANONICAL_MAPPER.readValue(argumentsJson, Object.class);
            return CANONICAL_MAPPER.writeValueAsString(parsed);
        } catch (Exception e) {
            return argumentsJson.trim();
        }
    }

    private static String findArguments(List<AssistantMessage.ToolCall> toolCalls,
                                        ToolResponseMessage.ToolResponse result) {
        if (toolCalls == null) {
            return "";
        }
        for (AssistantMessage.ToolCall call : toolCalls) {
            if (call != null && call.id() != null && call.id().equals(result.id())) {
                return call.arguments();
            }
        }
        return "";
    }

    private static int increment(Map<String, Object> stats, String key) {
        int next = ((Number) stats.getOrDefault(key, 0)).intValue() + 1;
        stats.put(key, next);
        return next;
    }

    private static int resetNoProgress(Map<String, Object> stats, String signature) {
        stats.put(KEY_NO_PROGRESS_COUNT + signature, 1);
        return 1;
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is mandatory on every JVM; fall back to hashCode just in case.
            return Integer.toHexString(text == null ? 0 : text.hashCode());
        }
    }
}
