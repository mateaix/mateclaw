package vip.mate.agent.runtime.dsh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DshManagedProcess implements AutoCloseable {
    private final DshProcessHandle process;
    private final String sessionId;
    private final Path binary;
    private final Path sessionHome;
    private final String bridgeToken;
    private final Runnable onClosed;
    private final AtomicBoolean closed = new AtomicBoolean();

    DshManagedProcess(DshProcessHandle process, String sessionId, Path binary,
                      Path sessionHome, String bridgeToken) {
        this(process, sessionId, binary, sessionHome, bridgeToken, () -> { });
    }

    DshManagedProcess(DshProcessHandle process, String sessionId, Path binary,
                      Path sessionHome, String bridgeToken, Runnable onClosed) {
        this.process = process;
        this.sessionId = sessionId;
        this.binary = binary;
        this.sessionHome = sessionHome;
        this.bridgeToken = bridgeToken;
        this.onClosed = onClosed == null ? () -> { } : onClosed;
    }

    public DshProcessDiagnostics diagnostics() {
        return new DshProcessDiagnostics(sessionId, binary, sessionHome,
                process.isAlive(), bridgeToken != null && !bridgeToken.isBlank());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (process.isAlive()) {
            process.destroy();
            if (!process.awaitExit(1_000L) && process.isAlive()) {
                process.destroyForcibly();
                process.awaitExit(1_000L);
            }
        }
        deleteRecursively(sessionHome);
        onClosed.run();
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Cleanup is best effort; the process is already stopped.
                }
            });
        } catch (IOException ignored) {
            // Cleanup is best effort; diagnostics retain the path for operators.
        }
    }
}
