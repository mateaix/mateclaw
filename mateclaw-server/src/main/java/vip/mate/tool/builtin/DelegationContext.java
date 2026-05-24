package vip.mate.tool.builtin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Tracks Agent delegation call context to prevent infinite recursion and carry parent session info.
 * <p>
 * Uses a ThreadLocal stack so that nested delegations correctly restore the previous layer's
 * parentConversationId and childDeniedTools on exit.
 * Each {@link DelegateAgentTool} delegation calls enter() before and exit() after execution.
 *
 * @author MateClaw Team
 */
public final class DelegationContext {

    /**
     * Snapshot of one delegation layer's state.
     *
     * <p>{@code rootConversationId} is the human-facing conversation at the top
     * of the delegation tree — every layer carries it unchanged so that a
     * grandchild's progress events can be broadcast to the same stream the user
     * is watching, rather than to its immediate (machine-only) parent.
     * {@code currentSubagentId} is the id of the subagent running THIS layer; a
     * deeper child reads it as its own {@code parentSubagentId} to reconstruct
     * the spawn tree.
     */
    private record Frame(String parentConversationId, Set<String> childDeniedTools,
                         String rootConversationId, String currentSubagentId, int depth) {}

    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private DelegationContext() {}

    /**
     * Current delegation depth (0 = top-level call, not inside any delegation).
     * <p>Read from the TOP frame's recorded depth, NOT the thread-local stack
     * size: async / parallel children run on fresh executor threads where the
     * stack starts empty, so a size-based depth would reset to 1 at every async
     * hop and let a child bypass {@code MAX_DELEGATION_DEPTH}. The real tree
     * depth is carried in via {@link #enter(String, Set, String, String, int)}.
     */
    public static int currentDepth() {
        Frame top = STACK.get().peek();
        return top != null ? top.depth : 0;
    }

    /** Depth for the next layer when the caller doesn't pass one explicitly. */
    private static int nextDepth() {
        Frame top = STACK.get().peek();
        return (top != null ? top.depth : 0) + 1;
    }

    /** Parent conversation ID for event relay (from the current frame) */
    public static String parentConversationId() {
        Frame top = STACK.get().peek();
        return top != null ? top.parentConversationId : null;
    }

    /** Denied tools set for the child Agent (from the current frame) */
    public static Set<String> childDeniedTools() {
        Frame top = STACK.get().peek();
        return top != null && top.childDeniedTools != null ? top.childDeniedTools : Set.of();
    }

    /** Root (human-facing) conversation ID for the whole tree, or null at top level. */
    public static String rootConversationId() {
        Frame top = STACK.get().peek();
        return top != null ? top.rootConversationId : null;
    }

    /** Subagent id of the layer currently executing, or null at top level. */
    public static String currentSubagentId() {
        Frame top = STACK.get().peek();
        return top != null ? top.currentSubagentId : null;
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
        STACK.get().push(new Frame(parentConversationId, deniedTools,
                rootConversationId, currentSubagentId, depth));
    }

    /** Enter the next delegation layer (backward-compatible overload) */
    public static void enter() {
        enter(null, null, null, null, nextDepth());
    }

    /** Exit the current delegation layer, restoring the previous layer's context */
    public static void exit() {
        Deque<Frame> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        // Clean up ThreadLocal entirely when the stack is empty to prevent memory leaks
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }
}
