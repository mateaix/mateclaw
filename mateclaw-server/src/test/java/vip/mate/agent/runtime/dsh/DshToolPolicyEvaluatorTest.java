package vip.mate.agent.runtime.dsh;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DshToolPolicyEvaluatorTest {
    private final DshToolPolicyEvaluator evaluator = new DshToolPolicyEvaluator();
    private final DshToolPolicy policy = new DshToolPolicy(
            Path.of("/workspace"), "read-only", Set.of("disabled"),
            Set.of("read"), Set.of("edit"), Set.of("safe"));

    @Test
    void disabledToolIsDeniedBeforeOtherRules() {
        assertEquals(DshToolDecision.DENY, evaluator.decide(policy, "disabled", null));
    }

    @Test
    void pathOutsideWorkspaceIsDenied() {
        assertEquals(DshToolDecision.DENY,
                evaluator.decide(policy, "read", Path.of("/tmp/outside.txt")));
    }

    @Test
    void readOnlyModeDeniesEditTools() {
        assertEquals(DshToolDecision.DENY,
                evaluator.decide(policy, "edit", Path.of("/workspace/a.txt")));
    }

    @Test
    void explicitApprovalIsReturnedForAllowedEditInWriteMode() {
        DshToolPolicy writePolicy = new DshToolPolicy(
                Path.of("/workspace"), "workspace-write", Set.of(),
                Set.of("read"), Set.of("edit"), Set.of());
        assertEquals(DshToolDecision.APPROVAL,
                evaluator.decide(writePolicy, "edit", Path.of("/workspace/a.txt")));
    }
}
