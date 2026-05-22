package vip.mate.agent.graph.observation;

import lombok.extern.slf4j.Slf4j;
import vip.mate.agent.context.StructuredTruncator;
import vip.mate.config.GraphObservationProperties;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 观察结果处理器
 * <p>
 * 负责工具调用结果的标准化、截断、压缩，以及 shouldSummarize 判断。
 * 防止 observation 无限膨胀，保证传给 LLM 的上下文可控。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ObservationProcessor {

    private final GraphObservationProperties properties;

    public ObservationProcessor(GraphObservationProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取最大总观察字符数（供外部节点读取阈值）
     */
    public int getMaxTotalObservationChars() {
        return properties.getMaxTotalObservationChars();
    }

    /**
     * 标准化工具结果
     * <p>
     * 格式化为统一的 "[工具名] 结果" 结构，方便 LLM 和 summarizing 处理。
     *
     * @param toolName  工具名称
     * @param rawResult 原始工具返回
     * @return 标准化后的观察文本
     */
    public String normalize(String toolName, String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return String.format("[%s] 工具返回空结果", toolName);
        }
        String trimmed = rawResult.strip();
        return String.format("[%s] %s", toolName, trimmed);
    }

    /** 用于检测尾部是否包含错误信息 */
    private static final Pattern ERROR_TAIL_PATTERN = Pattern.compile(
            "(?i)\\b(error|exception|traceback|failed|fatal|panic|stack.?trace|errno)\\b");

    /**
     * 截断大文本，保留首尾关键片段。
     * <p>
     * 如果尾部 2000 字符内检测到错误模式（error, exception, traceback 等），
     * 自动提升 tail 保留比例（默认从 0.6 → 0.8），确保错误信息不被截掉。
     * 同时保证截断后至少保留 minKeepChars 字符。
     *
     * @param text   原始文本
     * @param maxLen 最大允许长度
     * @return 截断后的文本
     */
    public String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }

        // 最少保留保证
        if (maxLen < properties.getMinKeepChars()) {
            maxLen = properties.getMinKeepChars();
            if (text.length() <= maxLen) {
                return text;
            }
        }

        int originalLen = text.length();
        String marker = String.format(properties.getTruncationMarker(), originalLen);
        int available = maxLen - marker.length();
        if (available <= 0) {
            return text.substring(0, maxLen);
        }

        // 检测尾部是否含错误信息 → 动态调整 head/tail 比例
        double effectiveHeadRatio = properties.getHeadRatio();
        String tailRegion = text.substring(Math.max(0, originalLen - 2000));
        if (ERROR_TAIL_PATTERN.matcher(tailRegion).find()) {
            effectiveHeadRatio = 1.0 - properties.getErrorTailRatio(); // 0.2（保留 80% 给 tail）
            log.info("[Observation] Error pattern detected in tail, preserving tail (ratio={})",
                    properties.getErrorTailRatio());
        }

        int headLen = (int) (available * effectiveHeadRatio);
        int tailLen = available - headLen;

        String result = StructuredTruncator.truncate(text, headLen, tailLen, marker);

        log.info("[Observation] Truncated from {} to {} chars (limit={}, headRatio={})",
                originalLen, result.length(), maxLen, effectiveHeadRatio);
        return result;
    }

    /**
     * 处理单次工具结果：标准化 + 截断
     *
     * @param toolName  工具名
     * @param rawResult 原始结果
     * @return 处理后的观察文本
     */
    public String process(String toolName, String rawResult) {
        String normalized = normalize(toolName, rawResult);
        return truncate(normalized, properties.getMaxSingleObservationChars());
    }

    /**
     * 判断是否需要进入 summarizing 阶段
     * <p>
     * 触发条件（任一满足即返回 true）：
     * 1. 单次工具结果超过大结果阈值
     * 2. 历史观察总字符数超过总量上限
     * 3. 观察轮次 >= 最小轮次阈值
     *
     * @param observationHistory 已有的观察历史
     * @param lastResult         最新一次工具结果（处理后）
     * @return 是否需要 summarize
     */
    public boolean needsSummarizing(List<String> observationHistory, String lastResult) {
        // 条件 1：单次结果过大
        if (lastResult != null && lastResult.length() > properties.getLargeResultThreshold()) {
            log.debug("[ObservationProcessor] Summarize triggered: large result ({} chars)", lastResult.length());
            return true;
        }

        // 条件 2：总量超限
        int totalChars = observationHistory.stream().mapToInt(String::length).sum();
        if (lastResult != null) {
            totalChars += lastResult.length();
        }
        if (totalChars > properties.getMaxTotalObservationChars()) {
            log.debug("[ObservationProcessor] Summarize triggered: total observations {} chars", totalChars);
            return true;
        }

        // 条件 3：轮次足够多
        int rounds = observationHistory.size() + (lastResult != null ? 1 : 0);
        if (rounds >= properties.getMinRoundsForSummarize()) {
            log.debug("[ObservationProcessor] Summarize triggered: {} observation rounds", rounds);
            return true;
        }

        return false;
    }
}
