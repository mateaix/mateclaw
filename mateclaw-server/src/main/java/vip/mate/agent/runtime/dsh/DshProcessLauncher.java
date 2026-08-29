package vip.mate.agent.runtime.dsh;

import vip.mate.agent.runtime.contract.RuntimeSession;

import java.nio.file.Path;

@FunctionalInterface
public interface DshProcessLauncher {
    DshProcessHandle launch(Path binary, RuntimeSession session, Path sessionHome, String bridgeToken);
}
