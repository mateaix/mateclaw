package vip.mate.agent.context;

import java.util.function.Supplier;

/** Subscription-time marker; callers capture it before asynchronous lifecycle callbacks. */
public final class GoalContinuationContext {
    private static final ThreadLocal<Boolean> EXPLICIT_PROMPT = new ThreadLocal<>();
    private GoalContinuationContext() {}
    public static boolean active() { return EXPLICIT_PROMPT.get() != null; }
    public static boolean explicitPrompt() { return Boolean.TRUE.equals(EXPLICIT_PROMPT.get()); }
    public static <T> T call(Supplier<T> action) { return call(true, action); }

    /** Queued user input keeps normal attachment reconstruction within the same worker. */
    public static <T> T call(boolean explicitPrompt, Supplier<T> action) {
        Boolean previous=EXPLICIT_PROMPT.get();
        EXPLICIT_PROMPT.set(explicitPrompt);
        try { return action.get(); }
        finally { if(previous==null) EXPLICIT_PROMPT.remove(); else EXPLICIT_PROMPT.set(previous); }
    }
}
