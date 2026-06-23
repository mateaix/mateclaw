package vip.mate.tool.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.delegation.SubagentRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SessionListTool} — the read-only "list" leg of the
 * spawn / send / list triad.
 *
 * @author MateClaw Team
 */
class SessionListToolTest {

    private SubagentRegistry registry;
    private SessionListTool tool;

    @BeforeEach
    void setUp() {
        registry = new SubagentRegistry();
        tool = new SessionListTool(registry);
        // Drain any delegation frames a prior test may have leaked on this thread.
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
        ToolExecutionContext.clear();
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
    }

    @Test
    void reportsNoContextWhenConversationUnknown() {
        // No ToolExecutionContext conversation and no delegation frame.
        String out = tool.listSubagents(null);
        assertTrue(out.contains("no conversation context"),
                "expected a no-context message, got: " + out);
    }

    @Test
    void reportsEmptyTreeWhenNoSubagents() {
        ToolExecutionContext.set("conv-root", "tester");
        String out = tool.listSubagents(null);
        assertTrue(out.contains("No active sub-agents for this conversation"),
                "expected an empty-tree message, got: " + out);
    }

    @Test
    void listsActiveSubagentsForCurrentConversation() {
        ToolExecutionContext.set("conv-root", "tester");
        registry.register("conv-root", "child-1", 11L, "research the topic", null);
        registry.register("conv-root", "child-2", 22L, "draft the summary", null);
        // A subagent under a different root must not leak into this listing.
        registry.register("other-conv", "child-x", 99L, "unrelated work", null);

        String out = tool.listSubagents(null);

        assertTrue(out.contains("Active sub-agents (2)"), "expected exactly two children, got: " + out);
        assertTrue(out.contains("agent=11"), out);
        assertTrue(out.contains("agent=22"), out);
        assertTrue(out.contains("research the topic"), out);
        assertTrue(out.contains("status=running"), out);
        assertFalse(out.contains("agent=99"), "other conversation's subagent leaked: " + out);
    }

    @Test
    void prefersDelegationRootOverCurrentConversation() {
        // Inside a delegated layer, the tree root is the human-facing conversation
        // carried by the delegation frame, not the child's own conversation.
        ToolExecutionContext.set("child-conv", "tester");
        DelegationContext.enter("child-conv", java.util.Set.of(), "conv-root", "sa-1", 1);
        try {
            registry.register("conv-root", "child-1", 11L, "research the topic", null);
            String out = tool.listSubagents(null);
            assertTrue(out.contains("Active sub-agents (1)"), out);
            assertTrue(out.contains("agent=11"), out);
        } finally {
            DelegationContext.exit();
        }
    }
}
