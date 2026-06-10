package vip.mate.workspace.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workspace filesystem sandbox configuration.
 * <p>
 * Backs the global fallback boundary enforced by
 * {@link vip.mate.tool.guard.WorkspacePathGuard}. When a conversation has no
 * per-workspace base path configured, file and shell tools are confined to
 * {@link #root} instead of running unconstrained against the whole filesystem.
 * This is the fail-closed default for the common out-of-the-box state where a
 * workspace's {@code base_path} column is unset.
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.workspace.sandbox")
public class WorkspaceSandboxProperties {

    /**
     * Whether the global fallback sandbox root is enforced. When {@code false},
     * conversations without a configured workspace base path run unconstrained
     * (the legacy behaviour) — an escape hatch for operators who deliberately
     * want agents to reach outside any single directory.
     */
    private boolean enabled = true;

    /**
     * Global fallback sandbox root, used when no per-workspace base path is set.
     * Defaults to {@code <working dir>/data/workspace}, alongside the H2 data
     * directory. The directory is created at startup if missing.
     */
    private String root = System.getProperty("user.dir") + "/data/workspace";
}
