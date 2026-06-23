package vip.mate.agent.delegation;

import org.junit.jupiter.api.Test;
import vip.mate.tool.builtin.DelegationContext;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link SubagentRunContext} value object and its use as an
 * explicitly-threaded carrier across executor threads.
 *
 * @author MateClaw Team
 */
class SubagentRunContextTest {

    @Test
    void normalisesNullDenySetToEmptyImmutable() {
        SubagentRunContext ctx = new SubagentRunContext(1, "p", "r", "sa", null);
        assertEquals(Set.of(), ctx.deniedTools());
        assertThrows(UnsupportedOperationException.class, () -> ctx.deniedTools().add("x"));
    }

    @Test
    void copiesDenySetSoLaterMutationDoesNotLeak() {
        Set<String> mutable = new HashSet<>(Set.of("toolA"));
        SubagentRunContext ctx = new SubagentRunContext(1, "p", "r", "sa", mutable);
        mutable.add("toolB");
        // The context took an immutable copy at construction; the later add must not leak in.
        assertEquals(Set.of("toolA"), ctx.deniedTools());
    }

    @Test
    void rootContextIsNotDelegated() {
        assertFalse(SubagentRunContext.ROOT.isDelegated());
        assertEquals(0, SubagentRunContext.ROOT.depth());
        assertNull(SubagentRunContext.ROOT.parentConversationId());
        assertTrue(new SubagentRunContext(1, "p", "r", "sa", Set.of()).isDelegated());
    }

    @Test
    void childFrameAdvancesDepthAndInheritsRoot() {
        SubagentRunContext l1 = SubagentRunContext.ROOT.childFrame("conv-root", "sa-1", Set.of("delegateToAgent"));
        assertEquals(1, l1.depth());
        assertEquals("conv-root", l1.parentConversationId());
        // First delegation: root falls back to the spawning conversation.
        assertEquals("conv-root", l1.rootConversationId());

        SubagentRunContext l2 = l1.childFrame("conv-child", "sa-2", Set.of());
        assertEquals(2, l2.depth());
        assertEquals("conv-child", l2.parentConversationId());
        // Deeper layers keep broadcasting to the same human-facing root.
        assertEquals("conv-root", l2.rootConversationId());
    }

    /**
     * Guardrail: a context passed explicitly carries its depth across a fresh
     * executor thread, where a size-based thread-local stack would reset to 1.
     * Reconstructing the layer via {@link DelegationContext#push} on the child
     * thread reproduces the real tree depth.
     */
    @Test
    void explicitContextCarriesDepthAcrossThreadHop() throws Exception {
        // Build a depth-3 context on the dispatching thread without touching the
        // current thread's stack.
        SubagentRunContext dispatched = new SubagentRunContext(3, "conv", "root", "sa", Set.of("execute_code"));

        AtomicInteger observedDepth = new AtomicInteger(-1);
        AtomicReference<String> observedDenied = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            // Fresh thread: stack starts empty.
            assertEquals(0, DelegationContext.currentDepth());
            DelegationContext.push(dispatched);
            try {
                observedDepth.set(DelegationContext.currentDepth());
                observedDenied.set(String.join(",", DelegationContext.childDeniedTools()));
            } finally {
                DelegationContext.exit();
            }
        });
        worker.join();

        assertEquals(3, observedDepth.get());
        assertEquals("execute_code", observedDenied.get());
    }
}
