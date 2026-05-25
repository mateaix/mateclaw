package vip.mate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话历史上下文窗口管理配置
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.agent.conversation.window")
public class ConversationWindowProperties {

    /** 全局默认最大输入 token（上下文窗口） */
    private int defaultMaxInputTokens = 128000;

    /** 历史 token 占比达此阈值触发压缩（0-1） */
    private double compactTriggerRatio = 0.75;

    /** 压缩后保留最近 N 轮对话（user+assistant 算一轮） */
    private int preserveRecentPairs = 5;

    /** 摘要自身最大 token 数（仅作 LLM maxToken 参数上限，实际预算由动态计算） */
    private int summaryMaxTokens = 800;

    // ==================== 动态压缩配置 ====================

    /** 尾部保护的最小消息数（即使 token 预算用完也至少保留这么多） */
    private int protectLastMinMessages = 10;

    /** 摘要 token 预算占被压缩内容的比例 (0-1)。被压缩内容越多，摘要越长。 */
    private double summaryBudgetRatio = 0.20;

    /** 摘要 token 预算上限（字数，非 token） */
    private int summaryBudgetCeiling = 3000;

    /** 摘要 token 预算下限（字数） */
    private int summaryBudgetFloor = 500;

    /**
     * Minimum prefix size (in messages) the pair-safe boundary must leave
     * before compaction is allowed to run. After enforcing tool-call/response
     * pair integrity the boundary may collapse so far forward that only a
     * handful of messages remain in the prefix — at that point the
     * compaction cost (a structured-summary LLM call) outweighs any token
     * savings, and we may as well skip this turn.
     *
     * <p>Default 2 means "at least two old messages worth condensing".
     * Set to 0 to always attempt compaction whenever a pair-safe cut exists.
     */
    private int pairSafeMinPrefixToCompact = 2;

    /**
     * After compaction, re-inject the first user message of the compressed
     * prefix so the original goal stays anchored in the prompt even when a
     * long task has paged through dozens of turns. Without an anchor the
     * structured summary alone can drift, and the model may forget what was
     * being asked. Injected as a {@link org.springframework.ai.chat.messages.UserMessage}
     * (never SystemMessage) so historical user input cannot be promoted to a
     * system-level instruction.
     */
    private boolean firstUserAnchorEnabled = true;

    /**
     * Maximum tokens the anchor body is allowed to consume in the prompt.
     * The first user message is often short ("write me a CLI tool that…"),
     * but power users sometimes paste multi-KB specs. When the body fits
     * the budget it stays verbatim; when it is up to 3× over, it is
     * head+tail truncated to this budget; when it is more than 3× over,
     * it degrades to a 200-char pointer line so the model still knows the
     * original goal existed without blowing prompt-cache or summary budget.
     */
    private int firstUserAnchorMaxTokens = 400;
}
