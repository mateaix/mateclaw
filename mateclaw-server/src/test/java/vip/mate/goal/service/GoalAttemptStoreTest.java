package vip.mate.goal.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vip.mate.goal.model.GoalAttempt;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GoalAttemptStoreTest {
    private GoalAttemptStore store;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 27, 9, 0);

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/h2/V120__agent_goal.sql"),
                new ClassPathResource("db/migration/h2/V188__goal_continuation.sql"),
                new ClassPathResource("db/migration/h2/V189__goal_attempt_and_input_queue.sql"))
                .execute(dataSource);
        store = new GoalAttemptStore(new JdbcTemplate(dataSource));
    }

    @Test
    void lifecycleIsLeaseFencedAndTerminalRowsAreImmutable() {
        GoalAttempt attempt = store.create(7L, "conv-7", null, "continuation",
                "lease-a", now.plusMinutes(1), null, now);

        assertThat(store.markRunning(attempt.id(), "wrong", now.plusSeconds(1))).isFalse();
        assertThat(store.markRunning(attempt.id(), "lease-a", now.plusSeconds(1))).isTrue();
        assertThat(store.checkpoint(attempt.id(), "lease-a", "uncertain", "tool_started", null,
                now.plusSeconds(2))).isTrue();
        assertThat(store.finish(attempt.id(), "wrong", "succeeded", "normal", null,
                now.plusSeconds(3))).isFalse();
        assertThat(store.finish(attempt.id(), "lease-a", "succeeded", "normal", null,
                now.plusSeconds(3))).isTrue();
        assertThat(store.markRunning(attempt.id(), "lease-a", now.plusSeconds(4))).isFalse();

        GoalAttempt finished = store.get(attempt.id());
        assertThat(finished.state()).isEqualTo("succeeded");
        assertThat(finished.replaySafety()).isEqualTo("uncertain");
        assertThat(finished.checkpointType()).isEqualTo("tool_started");
        assertThat(finished.finishedAt()).isEqualTo(now.plusSeconds(3));
    }

    @Test
    void historyPreservesRecoveryParentAndCreationOrder() {
        GoalAttempt first = store.create(7L, "conv-7", null, "continuation",
                "lease-a", now.plusMinutes(1), null, now);
        GoalAttempt recovery = store.create(7L, "conv-7", first.id(), "recovery",
                "lease-b", now.plusMinutes(2), 91L, now.plusSeconds(5));

        assertThat(store.listRecent(7L, 10)).extracting(GoalAttempt::id)
                .containsExactly(recovery.id(), first.id());
        assertThat(store.get(recovery.id()).parentAttemptId()).isEqualTo(first.id());
        assertThat(store.get(recovery.id()).inputItemId()).isEqualTo(91L);
    }
}
