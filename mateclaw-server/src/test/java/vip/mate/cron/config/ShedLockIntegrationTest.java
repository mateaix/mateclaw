package vip.mate.cron.config;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC-03 Lane G2 integration test — exercises the full path:
 *
 * <ol>
 *   <li>Flyway migration {@code V74__shedlock_table.sql} ran successfully
 *       against the in-memory H2 (otherwise context startup would fail).</li>
 *   <li>{@link ShedLockConfig} wired a {@link LockProvider} bean.</li>
 *   <li>The provider's lock/unlock semantics actually exclude concurrent
 *       holders — i.e. node-A → node-B contention works as expected.</li>
 * </ol>
 *
 * <p>Single-node deployments hit only the trivial path (acquire from this
 * JVM always succeeds), so a CI test that only exercises one acquirer
 * would miss the multi-node behavior we actually shipped this for.
 * Simulating two nodes against the same H2 database catches the
 * contention path.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shedlock_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class ShedLockIntegrationTest {

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("V74 created the shedlock table with the expected columns")
    void shedlockTableExists() {
        // information_schema lookup works on H2 MySQL-mode and on MySQL itself.
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'shedlock'",
                Long.class);
        assertNotNull(count);
        assertEquals(1L, count, "shedlock table should be created by V74");
    }

    @Test
    @DisplayName("acquire then release lets a sibling acquire immediately")
    void acquireAndRelease() {
        String name = "test-lock-acquire-release";
        // First node — acquires.
        Optional<SimpleLock> a = lockProvider.lock(new LockConfiguration(
                Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO));
        assertTrue(a.isPresent(), "first acquirer should succeed");

        // Sibling tries while A holds it — must be excluded.
        Optional<SimpleLock> b = lockProvider.lock(new LockConfiguration(
                Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO));
        assertFalse(b.isPresent(), "second acquirer should be blocked while first holds the lock");

        // A releases.
        a.get().unlock();

        // Sibling tries again — should now succeed.
        Optional<SimpleLock> c = lockProvider.lock(new LockConfiguration(
                Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO));
        assertTrue(c.isPresent(), "third acquirer should succeed after release");
        c.get().unlock();
    }

    @Test
    @DisplayName("different lock names are independent — two jobs both proceed")
    void independentLocks() {
        Optional<SimpleLock> jobA = lockProvider.lock(new LockConfiguration(
                Instant.now(), "cron-job-A", Duration.ofMinutes(5), Duration.ZERO));
        Optional<SimpleLock> jobB = lockProvider.lock(new LockConfiguration(
                Instant.now(), "cron-job-B", Duration.ofMinutes(5), Duration.ZERO));

        assertTrue(jobA.isPresent());
        assertTrue(jobB.isPresent(),
                "different lock names must not block each other — multi-job parallelism is the whole point");

        jobA.get().unlock();
        jobB.get().unlock();
    }

    @Test
    @DisplayName("lockAtLeastFor prevents instant re-acquire by the same caller")
    void lockAtLeastForHonored() {
        String name = "test-lock-at-least";
        // Hold the lock for at least 2 seconds even if we release immediately.
        Optional<SimpleLock> first = lockProvider.lock(new LockConfiguration(
                Instant.now(), name, Duration.ofMinutes(5), Duration.ofSeconds(2)));
        assertTrue(first.isPresent());
        first.get().unlock(); // unlock returns, but lockAtLeastFor still applies

        // Immediate re-acquire should fail because lockAtLeastFor=2s hasn't elapsed.
        Optional<SimpleLock> second = lockProvider.lock(new LockConfiguration(
                Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO));
        assertFalse(second.isPresent(),
                "lockAtLeastFor must keep the entry inaccessible for its duration even after unlock");
    }
}
