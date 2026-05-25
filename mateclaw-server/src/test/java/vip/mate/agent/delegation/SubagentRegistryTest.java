package vip.mate.agent.delegation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubagentRegistryTest {

    /** ID format: sa-<epoch_ms>-<8 lowercase hex> */
    private static final Pattern ID_PATTERN = Pattern.compile("^sa-\\d+-[0-9a-f]{8}$");

    private SubagentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SubagentRegistry();
    }

    @Test
    @DisplayName("register assigns matching ID, snapshot finds it, unregister drops it")
    void registerSnapshotUnregister() {
        Disposable d = mock(Disposable.class);
        String id = registry.register("parent-1", "child-1", 7L, "do thing", d);

        assertThat(id).matches(ID_PATTERN);
        assertThat(registry.get(id)).isPresent();
        assertThat(registry.snapshot("parent-1")).hasSize(1);
        assertThat(registry.snapshot("parent-1").get(0).childConversationId()).isEqualTo("child-1");
        assertThat(registry.allActive()).hasSize(1);

        registry.unregister(id);

        assertThat(registry.get(id)).isEmpty();
        assertThat(registry.snapshot("parent-1")).isEmpty();
    }

    @Test
    @DisplayName("snapshot filters by parent — siblings under other parents are not visible")
    void snapshotFiltersByParent() {
        registry.register("parent-A", "ca-1", 1L, "task", null);
        registry.register("parent-A", "ca-2", 1L, "task", null);
        registry.register("parent-B", "cb-1", 1L, "task", null);

        assertThat(registry.snapshot("parent-A")).hasSize(2);
        assertThat(registry.snapshot("parent-B")).hasSize(1);
        assertThat(registry.snapshot("parent-C")).isEmpty();
        assertThat(registry.snapshot(null)).isEmpty();
    }

    @Test
    @DisplayName("interrupt flips status, disposes subscription, returns false for missing/null")
    void interruptBehaviour() {
        Disposable disposable = mock(Disposable.class);
        when(disposable.isDisposed()).thenReturn(false);
        String id = registry.register("p", "c", 1L, "g", disposable);

        assertThat(registry.interrupt(id)).isTrue();
        assertThat(registry.get(id)).isPresent();
        assertThat(registry.get(id).get().status().get()).isEqualTo("interrupted");
        verify(disposable).dispose();

        // Already-disposed subscription is not disposed again.
        when(disposable.isDisposed()).thenReturn(true);
        registry.interrupt(id);
        verify(disposable).dispose(); // still only the first call

        assertThat(registry.interrupt("does-not-exist")).isFalse();
        assertThat(registry.interrupt(null)).isFalse();
    }

    @Test
    @DisplayName("interrupt with null disposable does not throw")
    void interruptNullDisposable() {
        String id = registry.register("p", "c", 1L, "g", null);
        assertThat(registry.interrupt(id)).isTrue();
        assertThat(registry.get(id).get().status().get()).isEqualTo("interrupted");
    }

    @Test
    @DisplayName("setSpawnPaused is scoped per parent — pausing A does not pause B")
    void spawnPauseIsParentScoped() {
        registry.setSpawnPaused("parent-A", true);
        assertThat(registry.isSpawnPaused("parent-A")).isTrue();
        assertThat(registry.isSpawnPaused("parent-B")).isFalse();

        registry.setSpawnPaused("parent-A", false);
        assertThat(registry.isSpawnPaused("parent-A")).isFalse();

        // Null inputs are tolerated and never report paused.
        assertThat(registry.isSpawnPaused(null)).isFalse();
        assertThat(registry.setSpawnPaused(null, true)).isFalse();
    }

    @Test
    @DisplayName("concurrent register from many threads produces unique IDs and no record loss")
    void concurrentRegister() throws Exception {
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = java.util.Collections.synchronizedSet(new HashSet<>());

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < perThread; i++) {
                    String id = registry.register("parent-" + tid, "child-" + tid + "-" + i,
                            (long) i, "g", null);
                    ids.add(id);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(ids).hasSize(threads * perThread);
        assertThat(registry.allActive()).hasSize(threads * perThread);

        // Each parent owns exactly perThread children.
        for (int t = 0; t < threads; t++) {
            assertThat(registry.snapshot("parent-" + t)).hasSize(perThread);
        }
    }

    @Test
    @DisplayName("get on null / missing returns empty Optional")
    void getNullSafe() {
        assertThat(registry.get(null)).isEmpty();
        assertThat(registry.get("nope")).isEmpty();
    }

    @Test
    @DisplayName("unregister on null / missing is a no-op")
    void unregisterNullSafe() {
        registry.register("p", "c", 1L, "g", null);
        registry.unregister(null);
        registry.unregister("does-not-exist");
        assertThat(registry.allActive()).hasSize(1);
    }
}
