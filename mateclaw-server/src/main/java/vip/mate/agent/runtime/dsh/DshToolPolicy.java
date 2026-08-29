package vip.mate.agent.runtime.dsh;

import java.nio.file.Path;
import java.util.Set;

public record DshToolPolicy(
        Path workspaceRoot,
        String permissionMode,
        Set<String> disabledTools,
        Set<String> readTools,
        Set<String> editTools,
        Set<String> autoApprovedTools
) {
    public DshToolPolicy {
        permissionMode = permissionMode == null ? "read-only" : permissionMode;
        disabledTools = disabledTools == null ? Set.of() : Set.copyOf(disabledTools);
        readTools = readTools == null ? Set.of() : Set.copyOf(readTools);
        editTools = editTools == null ? Set.of() : Set.copyOf(editTools);
        autoApprovedTools = autoApprovedTools == null ? Set.of() : Set.copyOf(autoApprovedTools);
    }
}
