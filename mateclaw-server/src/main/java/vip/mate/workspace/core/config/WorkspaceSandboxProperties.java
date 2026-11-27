package vip.mate.workspace.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workspace filesystem sandbox configuration.
 * <p>
 * Backs the global fallback boundary enforced by
 * {@link vip.mate.tool.guard.WorkspacePathGuard}. When a conversation has no
 * per-workspace base path configured, file paths and literal shell paths are
 * validated against {@link #root}. This path validation does not contain
 * arbitrary subprocess file access; strict member isolation additionally
 * requires the container executor.
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

    /** Opt-in strict member storage and container execution; existing deployments retain legacy paths. */
    private boolean memberIsolationEnabled = false;

    /**
     * Global fallback sandbox root, used when no per-workspace base path is set.
     * Defaults to {@code <working dir>/data/workspace}, alongside the H2 data
     * directory. The directory is created at startup if missing.
     */
    private String root = System.getProperty("user.dir") + "/data/workspace";
}
