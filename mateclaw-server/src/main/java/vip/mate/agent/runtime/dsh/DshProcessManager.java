package vip.mate.agent.runtime.dsh;

import vip.mate.agent.runtime.contract.RuntimeSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DshProcessManager {
    private final DshBinaryResolver binaryResolver;
    private final DshProcessLauncher launcher;
    private final Map<String, DshManagedProcess> active = new ConcurrentHashMap<>();

    public DshProcessManager(DshBinaryResolver binaryResolver, DshProcessLauncher launcher) {
        this.binaryResolver = binaryResolver;
        this.launcher = launcher;
    }

    public DshManagedProcess start(RuntimeSession session) {
        stop(session.sessionId());
        Path binary = binaryResolver.resolve()
                .filter(Files::isExecutable)
                .orElseThrow(() -> new IllegalStateException("DSH binary is unavailable"));
        Path sessionHome;
        try {
            sessionHome = Files.createTempDirectory("mateclaw-dsh-" + safeSessionId(session.sessionId()) + "-");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create DSH session home", e);
        }
        String bridgeToken = UUID.randomUUID().toString();
        try {
            DshProcessHandle process = launcher.launch(binary, session, sessionHome, bridgeToken);
            if (process == null) throw new IllegalStateException("DSH launcher returned no process");
            DshManagedProcess managed = new DshManagedProcess(process, session.sessionId(), binary,
                    sessionHome, bridgeToken, () -> active.remove(session.sessionId()));
            active.put(session.sessionId(), managed);
            return managed;
        } catch (RuntimeException e) {
            deleteSessionHome(sessionHome);
            throw e;
        }
    }

    public boolean stop(String sessionId) {
        DshManagedProcess process = active.remove(sessionId);
        if (process == null) return false;
        process.close();
        return true;
    }

    public Set<String> activeSessionIds() {
        return Set.copyOf(active.keySet());
    }

    private static String safeSessionId(String sessionId) {
        return sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteSessionHome(Path path) {
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(candidate -> {
                try { Files.deleteIfExists(candidate); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
