package vip.mate.llm.chatmodel;

/**
 * Request-scoped {@link ThreadLocal} holder for the thinking depth.
 *
 * <p>Carries the front-end-selected thinking level from the agent service down
 * to the reasoning nodes and chat model builders, without mutating the cached
 * agent instance or the streaming interfaces.
 *
 * <p>Supported values: off / low / medium / high / max; {@code null} means
 * "follow the model default".
 *
 * @author MateClaw Team
 */
public final class ThinkingLevelHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ThinkingLevelHolder() {}

    public static void set(String level) {
        HOLDER.set(level);
    }

    /**
     * Get the current request's thinking level; {@code null} means unset
     * (follow the model default).
     */
    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
