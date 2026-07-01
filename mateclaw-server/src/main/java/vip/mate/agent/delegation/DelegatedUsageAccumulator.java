package vip.mate.agent.delegation;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-conversation accumulator for delegated sub-agent token usage.
 *
 * <p>When a parent turn delegates work to sub-agents, each child runs as its own
 * agent invocation in a separate conversation, so its token usage never lands in
 * the parent graph's own usage counters. This accumulator lets the delegation
 * layer record each completed child's usage keyed by the <em>root</em>
 * (user-facing) conversation, so the parent turn's {@code _usage_final} emission
 * can roll the whole sub-tree up into the turn total — surfaced live on the SSE
 * stream and persisted on the assistant message.
 *
 * <p><b>No double counting across nesting:</b> every descendant (child,
 * grandchild, …) records against the same root conversation, because the
 * delegation context carries the original root forward. The root agent drains
 * the full tree exactly once at its {@code _usage_final}; intermediate agents
 * drain their own conversation key, which holds nothing. A child agent's own
 * usage (returned to its parent and recorded once by the parent's delegation
 * call) is therefore counted a single time.
 *
 * <p>Exposed via a static accessor because the StateGraph agents that emit
 * {@code _usage_final} are built per-config and are not Spring-managed beans, so
 * they cannot receive this singleton by constructor injection.
 */
@Component
public class DelegatedUsageAccumulator {

    private static volatile DelegatedUsageAccumulator instance;

    @PostConstruct
    void register() {
        instance = this;
    }

    /** Returns the singleton, or {@code null} before the context is ready. */
    public static DelegatedUsageAccumulator getInstance() {
        return instance;
    }

    private record Usage(AtomicLong prompt, AtomicLong completion) {
        Usage() {
            this(new AtomicLong(), new AtomicLong());
        }
    }

    private final Map<String, Usage> byConversation = new ConcurrentHashMap<>();

    /** Record one completed child's usage against its root conversation. */
    public void add(String rootConversationId, int promptTokens, int completionTokens) {
        if (rootConversationId == null || rootConversationId.isBlank()) {
            return;
        }
        if (promptTokens <= 0 && completionTokens <= 0) {
            return;
        }
        Usage u = byConversation.computeIfAbsent(rootConversationId, k -> new Usage());
        if (promptTokens > 0) {
            u.prompt().addAndGet(promptTokens);
        }
        if (completionTokens > 0) {
            u.completion().addAndGet(completionTokens);
        }
    }

    /** Token pair carrier for a drained accumulation. */
    public record Drained(long promptTokens, long completionTokens) {
        public boolean isEmpty() {
            return promptTokens <= 0 && completionTokens <= 0;
        }
    }

    /** Atomically read and remove the accumulated delegated usage for a conversation. */
    public Drained drain(String conversationId) {
        if (conversationId == null) {
            return new Drained(0, 0);
        }
        Usage u = byConversation.remove(conversationId);
        return u == null ? new Drained(0, 0) : new Drained(u.prompt().get(), u.completion().get());
    }

    /** Discard any accumulation for a conversation — leak guard on error/cancel. */
    public void clear(String conversationId) {
        if (conversationId != null) {
            byConversation.remove(conversationId);
        }
    }
}
