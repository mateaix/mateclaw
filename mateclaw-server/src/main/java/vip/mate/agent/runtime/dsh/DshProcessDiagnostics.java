package vip.mate.agent.runtime.dsh;

import java.nio.file.Path;

public record DshProcessDiagnostics(
        String sessionId,
        Path binary,
        Path sessionHome,
        boolean alive,
        boolean bridgeTokenRedacted
) {}
