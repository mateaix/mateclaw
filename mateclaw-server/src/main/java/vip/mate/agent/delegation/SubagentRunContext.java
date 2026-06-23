package vip.mate.agent.delegation;

import java.util.Set;

/**
 * Immutable snapshot of one delegation layer's runtime identity.
 *
 * <p>This is the canonical value object that carries "who am I in the delegation
 * tree" down a single child agent run: tree depth, the immediate parent
 * conversation, the human-facing root conversation, the subagent id of the layer
 * currently executing, and the tool deny set in force for this layer.
 *
 * <p>It exists as a first-class, named record (rather than an anonymous frame
 * buried in a ThreadLocal stack) so the same identity can later be passed
 * explicitly through the call chain instead of being reconstructed from
 * thread-local state — explicit passing survives virtual-thread and reactive
 * hops, where a thread-confined stack does not. {@link vip.mate.tool.builtin.DelegationContext}
 * currently holds a stack of these per thread; callers that already have a
 * context in hand should prefer threading it explicitly.
 *
 * @param depth                 1-based tree depth; {@code 0} means the top-level
 *                              (non-delegated) call.
 * @param parentConversationId  the immediate parent conversation that spawned
 *                              this layer, or {@code null} at the top level.
 * @param rootConversationId    the human-facing conversation at the top of the
 *                              whole delegation tree; every layer carries it
 *                              unchanged so a deep child's progress events can
 *                              broadcast to the stream the user is watching.
 * @param currentSubagentId     the subagent id of the layer executing now; a
 *                              deeper child reads it as its own parent id to
 *                              reconstruct the spawn tree.
 * @param deniedTools           tool names this layer's agent may not call;
 *                              normalised to a non-null immutable set.
 *
 * @author MateClaw Team
 */
public record SubagentRunContext(
        int depth,
        String parentConversationId,
        String rootConversationId,
        String currentSubagentId,
        Set<String> deniedTools
) {

    /** The top-level context: not inside any delegation. */
    public static final SubagentRunContext ROOT = new SubagentRunContext(0, null, null, null, Set.of());

    public SubagentRunContext {
        // Normalise the deny set so every read site gets a non-null immutable
        // view without re-checking — mirrors the old accessor's null guard.
        deniedTools = (deniedTools == null) ? Set.of() : Set.copyOf(deniedTools);
    }

    /** True when this context represents a delegated (sub-agent) layer. */
    public boolean isDelegated() {
        return depth > 0;
    }

    /**
     * Build the context for the next layer spawned beneath this one. The root
     * conversation is inherited unchanged (falling back to the child's parent
     * conversation when this is the first delegation), and depth advances by one.
     *
     * @param childParentConversationId the spawning conversation for the child
     * @param childSubagentId           the subagent id assigned to the child
     * @param childDeniedTools          tool deny set for the child
     */
    public SubagentRunContext childFrame(String childParentConversationId,
                                         String childSubagentId,
                                         Set<String> childDeniedTools) {
        String inheritedRoot = (rootConversationId != null) ? rootConversationId : childParentConversationId;
        return new SubagentRunContext(depth + 1, childParentConversationId,
                inheritedRoot, childSubagentId, childDeniedTools);
    }
}
