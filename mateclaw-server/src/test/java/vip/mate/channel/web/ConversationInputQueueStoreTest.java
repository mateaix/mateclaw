package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vip.mate.channel.web.ConversationInputQueueStore.QueuedInput;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationInputQueueStoreTest {
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private ConversationInputQueueStore store;
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
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();
        store = new ConversationInputQueueStore(jdbc, mapper);
    }

    @Test
    void fifoClaimSurvivesStoreReconstructionAndPreservesAttachments() {
        MessageContentPart attachment = MessageContentPart.file("media-1", "report.pdf", "application/pdf");
        QueuedInput first = store.enqueue("conv", 1L, "mate", "one", List.of(attachment), now);
        QueuedInput second = store.enqueue("conv", 1L, "mate", "two", List.of(), now.plusNanos(1));

        QueuedInput claimed = store.claimNext("conv", "attempt-a", now.plusSeconds(1)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(first.id());
        assertThat(claimed.contentParts()).singleElement().extracting(MessageContentPart::getFileName)
                .isEqualTo("report.pdf");
        assertThat(store.bindMessage(first.id(), "attempt-a", 101L, now.plusSeconds(2))).isTrue();
        assertThat(store.consume(first.id(), "attempt-a", now.plusSeconds(3))).isTrue();

        ConversationInputQueueStore restarted = new ConversationInputQueueStore(jdbc, mapper);
        assertThat(restarted.claimNext("conv", "attempt-b", now.plusSeconds(4)))
                .get().extracting(QueuedInput::id).isEqualTo(second.id());
        assertThat(restarted.get(first.id()).persistedMessageId()).isEqualTo(101L);
        assertThat(restarted.get(first.id()).state()).isEqualTo("consumed");
    }

    @Test
    void claimReleaseAndCancellationAreFencedByAttempt() {
        QueuedInput input = store.enqueue("conv", 1L, "mate", "queued", List.of(), now);
        assertThat(store.claimNext("conv", "attempt-a", now.plusSeconds(1))).isPresent();

        assertThat(store.release(input.id(), "attempt-b", now.plusSeconds(2))).isFalse();
        assertThat(store.release(input.id(), "attempt-a", now.plusSeconds(2))).isTrue();
        assertThat(store.countQueued("conv")).isEqualTo(1);
        assertThat(store.cancel(input.id(), "stream_finished", now.plusSeconds(3))).isTrue();
        assertThat(store.claimNext("conv", "attempt-c", now.plusSeconds(4))).isEmpty();
    }
}
