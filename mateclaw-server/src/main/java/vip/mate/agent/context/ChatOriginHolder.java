package vip.mate.agent.context;

/**
 * Request-scoped {@link ChatOrigin} bridge between {@code AgentService}'s
 * public entry points and the StateGraph's {@code buildInitialState}.
 *
 * <p>RFC-063r §2.5 carries the origin end-to-end via Spring AI {@code ToolContext}
 * once it lands in graph state. This holder is the small bridge that gets the
 * origin from the AgentService method invocation into the graph's initial
 * state map — the holder lifecycle is bounded by the AgentService method
 * call (set on entry, cleared in {@code finally}). Once written into the
 * graph state under {@link vip.mate.agent.graph.state.MateClawStateKeys#CHAT_ORIGIN},
 * the rest of the runtime reads via the typed accessor — no further ThreadLocal
 * access. Mirrors {@link vip.mate.llm.chatmodel.ThinkingLevelHolder}.
 */
public final class ChatOriginHolder {

    private static final ThreadLocal<ChatOrigin> HOLDER = new ThreadLocal<>();

    private ChatOriginHolder() {
    }

    /** Set the origin for the current AgentService invocation. */
    public static void set(ChatOrigin origin) {
        HOLDER.set(origin);
    }

    /**
     * @return the origin set for the current invocation, or {@link ChatOrigin#EMPTY}
     *         when no entry path has supplied one (legacy callers).
     */
    public static ChatOrigin get() {
        ChatOrigin v = HOLDER.get();
        return v != null ? v : ChatOrigin.EMPTY;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
