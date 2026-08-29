package vip.mate.agent.runtime.dsh;

import org.junit.jupiter.api.Test;
import vip.mate.agent.runtime.contract.RuntimeSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DshProcessManagerTest {

    @Test
    void missingBinaryPreventsProcessLaunch() {
        AtomicBoolean launched = new AtomicBoolean();
        DshProcessManager manager = new DshProcessManager(
                () -> Optional.empty(),
                (binary, session, home, token) -> {
                    launched.set(true);
                    return new FakeProcess();
                });

        assertThrows(IllegalStateException.class, () -> manager.start(session()));
        assertFalse(launched.get());
    }

    @Test
    void closeStopsProcessAndRemovesSessionHome() throws Exception {
        Path binary = Files.createTempFile("dsh", "bin");
        assertTrue(binary.toFile().setExecutable(true));
        FakeProcess process = new FakeProcess();
        DshProcessManager manager = new DshProcessManager(
                () -> Optional.of(binary),
                (ignored, ignoredSession, home, ignoredToken) -> {
                    process.home = home;
                    return process;
                });

        DshManagedProcess managed = manager.start(session());
        Path home = managed.diagnostics().sessionHome();
        assertTrue(Files.exists(home));
        assertTrue(managed.diagnostics().bridgeTokenRedacted());
        assertTrue(manager.activeSessionIds().contains("session-1"));
        assertTrue(manager.stop("session-1"));
        assertFalse(manager.stop("session-1"));

        assertTrue(process.destroyed.get());
        assertFalse(Files.exists(home));
        assertFalse(manager.activeSessionIds().contains("session-1"));
    }

    private static RuntimeSession session() {
        return new RuntimeSession("session-1", "conversation-1", 1L, 2L,
                "model", Path.of("/workspace"), Map.of());
    }

    private static final class FakeProcess implements DshProcessHandle {
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private Path home;

        @Override
        public boolean isAlive() {
            return !destroyed.get();
        }

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public void destroyForcibly() {
            destroyed.set(true);
        }

        @Override
        public boolean awaitExit(long millis) {
            return destroyed.get();
        }
    }
}
