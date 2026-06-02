package vip.mate.memory.event;

/**
 * 对话完成事件
 * <p>
 * 在 assistant 消息持久化之后发布，用于触发异步记忆提取。
 *
 * @param agentId          Agent ID
 * @param conversationId   会话 ID
 * @param userMessage      最后一条用户消息
 * @param assistantReply   Agent 最终回答
 * @param messageCount     当前会话消息总数
 * @param triggerSource    触发来源："web" / "channel" / "cron"
 * @param ownerKey         memory owner this turn is attributed to (e.g.
 *                         "user:42"); null / "system" means not owner-scoped,
 *                         in which case extracted memory is written as shared.
 * @author MateClaw Team
 */
public record ConversationCompletedEvent(
        Long agentId,
        String conversationId,
        String userMessage,
        String assistantReply,
        int messageCount,
        String triggerSource,
        String ownerKey
) {
    /** Backwards-compatible constructor without an owner key (resolves to null). */
    public ConversationCompletedEvent(Long agentId, String conversationId, String userMessage,
                                      String assistantReply, int messageCount, String triggerSource) {
        this(agentId, conversationId, userMessage, assistantReply, messageCount, triggerSource, null);
    }
}
