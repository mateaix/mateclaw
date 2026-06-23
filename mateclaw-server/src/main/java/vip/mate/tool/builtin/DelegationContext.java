package vip.mate.tool.builtin;

import vip.mate.agent.delegation.SubagentRunContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Tracks Agent delegation call context to prevent infinite recursion and carry parent session info.
 *
 * <p>Thin thread-local adapter over {@link SubagentRunContext}: each layer's
 * identity is an immutable {@link SubagentRunContext}, and this class keeps a
 * per-thread stack of them so nested delegations correctly restore the previous
 * layer's context on {@link #exit()}. Each {@link DelegateAgentTool} delegation
 * calls {@link #enter} before and {@link #exit} after execution.
 *
 * <p>The value object is the canonical carrier; this adapter only manages its
 * thread-confined lifecycle. Call sites that already hold a
 * {@link SubagentRunContext} should prefer passing it explicitly — a thread-local
 * stack does not survive virtual-thread / reactive hops, which is why the
 * explicit-depth {@link #enter(String, Set, String, String, int)} overload
 * exists for async / parallel children that start on a fresh executor thread.
 *
 * @author MateClaw Team
 */
public final class DelegationContext {

    private static final ThreadLocal<Deque<SubagentRunContext>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private DelegationContext() {}

    /**
     * The context of the layer currently executing on this thread, or
     * {@link SubagentRunContext#ROOT} when not inside any delegation.
     */
    public static SubagentRunContext current() {
        SubagentRunContext top = STACK.get().peek();
        return top != null ? top : SubagentRunContext.ROOT;
    }

    /**
     * Current delegation depth (0 = top-level call, not inside any delegation).
     * <p>Read from the TOP frame's recorded depth, NOT the thread-local stack
     * size: async / parallel children run on fresh executor threads where the
     * stack starts empty, so a size-based depth would reset to 1 at every async
     * hop and let a child bypass {@code MAX_DELEGATION_DEPTH}. The real tree
     * depth is carried in via {@link #enter(String, Set, String, String, int)}.
     */
    public static int currentDepth() {
        return current().depth();
    }

    /** Depth for the next layer when the caller doesn't pass one explicitly. */
    private static int nextDepth() {
        return current().depth() + 1;
    }

    /** Parent conversation ID for event relay (from the current frame) */
    public static String parentConversationId() {
        return current().parentConversationId();
    }

    /** Denied tools set for the child Agent (from the current frame) */
    public static Set<String> childDeniedTools() {
        return current().deniedTools();
    }

    /** Root (human-facing) conversation ID for the whole tree, or null at top level. */
    public static String rootConversationId() {
        return current().rootConversationId();
    }

    /** Subagent id of the layer currently executing, or null at top level. */
    public static String currentSubagentId() {
        return current().currentSubagentId();
    }

    /** Enter the next delegation layer (with parent conversation ID and child tool restrictions) */
    public static void enter(String parentConversationId, Set<String> deniedTools) {
        enter(parentConversationId, deniedTools, null, null, nextDepth());
    }

    /**
     * Enter the next delegation layer carrying the full tree identity so deeper
     * children can broadcast to the root conversation and tag their parent.
     * Depth is inferred from the current frame; use the explicit-depth overload
     * from executor threads where the stack starts empty.
     */
    public static void enter(String parentConversationId, Set<String> deniedTools,
                             String rootConversationId, String currentSubagentId) {
        enter(parentConversationId, deniedTools, rootConversationId, currentSubagentId, nextDepth());
    }

    /**
     * Enter the next delegation layer with an EXPLICIT tree depth. Async /
     * parallel children run on fresh executor threads with an empty stack, so
     * they must pass the real {@code childDepth} computed on the dispatching
     * thread — otherwise depth-based recursion limits reset at every hop.
     */
    public static void enter(String parentConversationId, Set<String> deniedTools,
                             String rootConversationId, String currentSubagentId, int depth) {
        push(new SubagentRunContext(depth, parentConversationId, rootConversationId,
                currentSubagentId, deniedTools));
    }

    /** Enter the next delegation layer (backward-compatible overload) */
    public static void enter() {
        enter(null, null, null, null, nextDepth());
    }

    /**
     * Push a pre-built context onto this thread's stack. Preferred when the
     * caller already holds an explicit {@link SubagentRunContext} (e.g. one
     * reconstructed on a fresh executor thread), so the identity is threaded
     * as a value rather than reassembled from positional arguments.
     */
    public static void push(SubagentRunContext context) {
        STACK.get().push(context);
    }

    /** Exit the current delegation layer, restoring the previous layer's context */
    public static void exit() {
        Deque<SubagentRunContext> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        // Clean up ThreadLocal entirely when the stack is empty to prevent memory leaks
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }
}
