package vip.mate.agent.runtime.dsh;

import java.nio.file.Path;

public final class DshToolPolicyEvaluator {
    public DshToolDecision decide(DshToolPolicy policy, String toolName, Path targetPath) {
        if (policy == null || toolName == null || toolName.isBlank()) return DshToolDecision.DENY;
        if (policy.disabledTools().contains(toolName)) return DshToolDecision.DENY;
        if (targetPath != null && !withinWorkspace(policy.workspaceRoot(), targetPath)) {
            return DshToolDecision.DENY;
        }
        boolean edit = policy.editTools().contains(toolName);
        if (edit && "read-only".equalsIgnoreCase(policy.permissionMode())) {
            return DshToolDecision.DENY;
        }
        if (policy.autoApprovedTools().contains(toolName)) return DshToolDecision.ALLOW;
        if (policy.readTools().contains(toolName) && !edit) return DshToolDecision.ALLOW;
        return DshToolDecision.APPROVAL;
    }

    private boolean withinWorkspace(Path root, Path target) {
        if (root == null) return false;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        return normalizedTarget.startsWith(normalizedRoot);
    }
}
