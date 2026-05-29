package vip.mate.agent;

import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.approval.grant.WorkspaceLookupCache;
import vip.mate.approval.grant.service.ApprovalGrantResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static-shape wiring check for the PR-1 integration points.
 * <p>
 * Earlier drafts used {@code @SpringBootTest} here, but that boots the full
 * application context (failing on the missing WebSocket {@code ServerContainer}
 * in the test classpath) and runs Flyway against the local dev file H2 — a
 * destructive side effect for a test whose only job is to verify that two fields
 * exist and one constructor signature is present.
 * <p>
 * Reflection covers exactly that: if anyone deletes a field on
 * {@code AgentGraphBuilder}, changes its type, or removes the new 9-arg
 * {@code ToolExecutionExecutor} constructor, this test fails immediately. No
 * Spring, no database, no provider keys required.
 * <p>
 * The "does Spring actually inject these beans at runtime" check moves to
 * PR-2's integration test, which already brings up the full context for the
 * conversation-lifecycle event listener.
 */
class AgentGraphBuilderIT {

    @Test
    void agent_graph_builder_declares_auto_grant_fields() throws NoSuchFieldException {
        Field resolverField = AgentGraphBuilder.class.getDeclaredField("approvalGrantResolver");
        Field cacheField = AgentGraphBuilder.class.getDeclaredField("workspaceLookupCache");

        assertThat(resolverField.getType()).isEqualTo(ApprovalGrantResolver.class);
        assertThat(cacheField.getType()).isEqualTo(WorkspaceLookupCache.class);
    }

    @Test
    void tool_execution_executor_has_constructor_that_accepts_auto_grant_deps() {
        boolean found = false;
        for (Constructor<?> c : ToolExecutionExecutor.class.getConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            if (types.length >= 2
                    && types[types.length - 2] == WorkspaceLookupCache.class
                    && types[types.length - 1] == ApprovalGrantResolver.class) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("ToolExecutionExecutor must expose a public constructor whose last two "
                        + "parameters are WorkspaceLookupCache + ApprovalGrantResolver; otherwise "
                        + "AgentGraphBuilder's `new ToolExecutionExecutor(...)` call sites won't compile.")
                .isTrue();
    }

    @Test
    void tool_execution_executor_keeps_legacy_constructors() {
        // Five legacy public constructors stay so that legacy callers and tests
        // that don't know about auto-grant continue to compile and run.
        long legacyCount = 0;
        for (Constructor<?> c : ToolExecutionExecutor.class.getConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            boolean isAutoGrantCtor = types.length >= 2
                    && types[types.length - 2] == WorkspaceLookupCache.class
                    && types[types.length - 1] == ApprovalGrantResolver.class;
            if (!isAutoGrantCtor) {
                legacyCount++;
            }
        }
        assertThat(legacyCount)
                .as("Removing legacy ToolExecutionExecutor constructors would break "
                        + "existing call sites and tests; keep all 5 in place.")
                .isGreaterThanOrEqualTo(5);
    }
}
