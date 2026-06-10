package vip.mate.workspace.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Registers the global fallback sandbox root with {@link WorkspacePathGuard}.
 * <p>
 * Without this, a workspace whose {@code base_path} is unset (the default state)
 * leaves the path guard a no-op, so the agent's file and shell tools can reach
 * anywhere the server process can. Pinning a fallback root makes the sandbox
 * fail closed: unconfigured conversations are confined to a single directory
 * instead of the whole filesystem.
 *
 * @author MateClaw Team
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WorkspaceSandboxProperties.class)
public class WorkspaceSandboxAutoConfiguration {

    public WorkspaceSandboxAutoConfiguration(WorkspaceSandboxProperties properties) {
        if (!properties.isEnabled()) {
            WorkspacePathGuard.setDefaultRoot(null);
            log.warn("[WorkspaceSandbox] Fallback sandbox root disabled — conversations "
                    + "without a configured workspace base path run unconstrained");
            return;
        }
        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            // Registering the root still tightens the boundary even if the
            // directory can't be pre-created; the shell cwd just won't be pinned
            // to it until it exists. Log and continue rather than fail startup.
            log.warn("[WorkspaceSandbox] Failed to create fallback sandbox root {}: {}",
                    root, e.getMessage());
        }
        WorkspacePathGuard.setDefaultRoot(root.toString());
    }
}
