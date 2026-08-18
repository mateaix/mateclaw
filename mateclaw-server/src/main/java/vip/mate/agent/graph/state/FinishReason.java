package vip.mate.agent.graph.state;

/**
 * ReAct 状态图终止原因枚举
 *
 * @author MateClaw Team
 */
public enum FinishReason {

    /** 正常完成：LLM 直接给出最终回答 */
    NORMAL("normal"),

    /** 经过 summarizing 后完成 */
    SUMMARIZED("summarized"),

    /** 达到最大迭代次数后强制收束 */
    MAX_ITERATIONS_REACHED("max_iterations_reached"),

    /** 发生错误后降级回答 */
    ERROR_FALLBACK("error_fallback"),

    /** 响应未完整完成，需要继续生成或重试 */
    INCOMPLETE("incomplete"),

    /** 最终回答引用了未被工具结果验证的源码事实 */
    EVIDENCE_INSUFFICIENT("evidence_insufficient"),

    /** An executable action was required but no substantive tool call was observed. */
    ACTION_UNVERIFIED("action_unverified"),

    /** Substantive action tools ran, but none completed successfully. */
    ACTION_FAILED("action_failed"),

    /** 用户主动停止 */
    STOPPED("stopped"),

    /** RFC-052: a tool with returnDirect=true short-circuited the loop;
     *  result was delivered to the user without re-entering the LLM. */
    RETURN_DIRECT("return_direct");

    private final String value;

    FinishReason(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
