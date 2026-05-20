package vip.mate.llm.cache;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认策略：在 system / tools / messages 尾部最多打 4 个缓存断点。
 *
 * <p>断点选择规则（按优先级递减）：
 * <ol>
 *   <li>{@link BreakpointKind#SYSTEM_TAIL} —— system 段长度足够时挂一个</li>
 *   <li>{@link BreakpointKind#TOOLS_TAIL} —— {@code includeToolsBlock=true} 且至少有 1 个工具</li>
 *   <li>{@link BreakpointKind#MESSAGES_TAIL} —— 倒数第 3 条 user/assistant 消息</li>
 *   <li>{@link BreakpointKind#MESSAGES_TAIL} —— 最后一条 user 消息（若与 #3 不同索引）</li>
 * </ol></p>
 *
 * <p>这是无状态的纯函数实现，单例线程安全；任何调度方都可直接复用。</p>
 */
public final class SystemAndTailCacheStrategy implements PromptCacheStrategy {

    /** system 段至少 256 字符才值得打断点（cache write 摊销下限）。 */
    private static final int MIN_SYSTEM_CHARS = 256;

    @Override
    public CachedPlan plan(CachePlanContext ctx) {
        if (!shouldCache(ctx)) {
            return CachedPlan.none();
        }

        List<Breakpoint> bps = new ArrayList<>(ctx.maxBreakpoints());

        if (ctx.hasSystemMessage() && ctx.systemCharLen() >= MIN_SYSTEM_CHARS) {
            bps.add(Breakpoint.systemTail());
        }
        if (ctx.includeToolsBlock() && bps.size() < ctx.maxBreakpoints()) {
            bps.add(Breakpoint.toolsTail());
        }

        // messages 尾部断点：倒数第 3 与最后 1（去重）
        List<Message> msgs = ctx.messages();
        int lastUserIdx = lastIndexOfType(msgs, MessageType.USER);
        int thirdLastTurnIdx = nthLastTurnIndex(msgs, 3);

        if (thirdLastTurnIdx >= 0 && bps.size() < ctx.maxBreakpoints()) {
            bps.add(Breakpoint.messagesTail(thirdLastTurnIdx));
        }
        if (lastUserIdx >= 0 && lastUserIdx != thirdLastTurnIdx
                && bps.size() < ctx.maxBreakpoints()) {
            bps.add(Breakpoint.messagesTail(lastUserIdx));
        }

        return bps.isEmpty() ? CachedPlan.none() : new CachedPlan(bps, ctx.ttl());
    }

    private static int lastIndexOfType(List<Message> msgs, MessageType type) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getMessageType() == type) return i;
        }
        return -1;
    }

    /**
     * 找倒数第 n 个 user/assistant 消息的索引（system 与 tool 不计）。
     * n=1 → 最后一条；n=3 → 倒数第三条。
     */
    private static int nthLastTurnIndex(List<Message> msgs, int n) {
        int seen = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            MessageType t = msgs.get(i).getMessageType();
            if (t == MessageType.USER || t == MessageType.ASSISTANT) {
                if (++seen == n) return i;
            }
        }
        return -1;
    }
}
