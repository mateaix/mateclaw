package vip.mate.execution.evidence;

import org.junit.jupiter.api.BeforeEach;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import vip.mate.execution.evidence.service.ExecutionEvidenceQueryService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.team.service.TeamWorkerConversationGovernanceService;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.service.WorkspaceService;
import static org.mockito.Mockito.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vip.mate.execution.evidence.model.AttemptState;
import vip.mate.execution.evidence.model.BeginResult;
import java.util.concurrent.CountDownLatch;
import vip.mate.execution.evidence.model.EffectOutcome;
import vip.mate.execution.evidence.model.EvidenceKind;
import vip.mate.execution.evidence.model.EvidenceObservation;
import vip.mate.execution.evidence.model.EvidenceResult;
import vip.mate.execution.evidence.model.ExecutionEvidence;
import vip.mate.execution.evidence.model.ExecutionIdentity;
import vip.mate.execution.evidence.model.SourceLevel;
import vip.mate.execution.evidence.service.ExecutionEvidenceStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Callable;
import org.springframework.dao.DuplicateKeyException;
import java.util.List;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class ExecutionEvidenceStoreTest {
    private JdbcTemplate jdbc;
    private ExecutionEvidenceStore store;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V191__execution_evidence_ledger.sql")).execute(source);
        jdbc = spy(new JdbcTemplate(source));
        store = new ExecutionEvidenceStore(jdbc, new DataSourceTransactionManager(source), new ExecutionEvidenceProperties());
    }
    @Test void fullPageUsesTwoLedgerQueriesAndPreservesOrder() {
        for (int i = 0; i < 100; i++) {
            var attempt = store.begin(identity("page-" + i));
            store.finish(attempt.id(), "owner", AttemptState.SUCCEEDED, EffectOutcome.UNCERTAIN,
                    List.of(observation("result-" + i)));
        }
        var conversations = mock(ConversationService.class);
        var conversation = new ConversationEntity();
        conversation.setWorkspaceId(1L);
        conversation.setConversationId("conversation");
        when(conversations.findByConversationId("conversation")).thenReturn(conversation);
        when(conversations.isConversationOwner("conversation", "owner")).thenReturn(true);
        var queries = new ExecutionEvidenceQueryService(store, conversations,
                mock(TeamWorkerConversationGovernanceService.class), mock(GeneratedFileCache.class),
                mock(AuthService.class), mock(WorkspaceService.class), new ExecutionEvidenceProperties(),
                new SimpleMeterRegistry());
        clearInvocations(jdbc);

        var page = queries.list("owner", 1L, "conversation", null, 100, null, null);

        assertThat(page.items()).hasSize(100);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).allSatisfy(row -> {
            assertThat(row.state()).isEqualTo(AttemptState.SUCCEEDED);
            assertThat(row.toolName()).isEqualTo("shell");
        });
        assertThat(page.items().getFirst().summary()).isEqualTo("result-99");
        assertThat(page.items().getLast().summary()).isEqualTo("result-0");
        long selects = mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("query") && call.getMethod().isVarArgs())
                .filter(call -> call.getArgument(0) instanceof String sql && sql.startsWith("SELECT"))
                .count();
        assertThat(selects).isEqualTo(2);
    }

    @Test void batchAttemptsAreScopedBoundedAndExcludeDeletedRows() {
        var first = store.begin(identity("first"));
        var second = store.begin(identity("second"));
        var foreign = store.begin(new ExecutionIdentity(2L, "other", "native", null, "foreign", "foreign",
                1, null, "shell", null, null, null, null, null, null, "owner"));
        jdbc.update("UPDATE mate_execution_attempt SET deleted=1 WHERE id=?", second.id());
        assertThat(store.findAttempts(1L, "conversation", List.of(first.id(), first.id(), second.id(), foreign.id())))
                .containsOnlyKeys(first.id());
        assertThat(store.findAttempts(1L, "other", List.of(first.id()))).isEmpty();
        assertThat(store.findAttempts(2L, "conversation", List.of(first.id()))).isEmpty();
        clearInvocations(jdbc);
        assertThat(store.findAttempts(1L, "conversation", List.of())).isEmpty();
        assertThatThrownBy(() -> store.findAttempts(1L, "conversation", Collections.nCopies(101, first.id())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    private ExecutionIdentity identity(String invocation) {
        return new ExecutionIdentity(1L,"conversation","native","session",invocation,invocation,1,"provider-id","shell",null,null,null,null,null,null,"owner");
    }
    private EvidenceObservation observation(String summary) {
        return new EvidenceObservation("return",EvidenceKind.TOOL_RETURNED,EvidenceResult.OBSERVED,SourceLevel.PLATFORM_OBSERVED,summary);
    }
    @ParameterizedTest
    @ValueSource(strings = {"h2", "mysql", "kingbase"})
    void migrationCreatesAllTablesInCompatibleDialectMode(String dialect) {
        var dataSource = new JdbcDataSource();
        String mode = "kingbase".equals(dialect) ? "PostgreSQL" : "MySQL";
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + dialect + "/V191__execution_evidence_ledger.sql"))
                .execute(dataSource);
        var probe = new JdbcTemplate(dataSource);
        for (String table : List.of("mate_execution_attempt", "mate_execution_evidence", "mate_evidence_scope", "mate_goal_criterion_evidence")) {
            assertThat(probe.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
    }

    @Test void startsDurablyAndDoesNotConfuseProviderIdsAcrossInvocations() {
        var first = store.begin(identity("one"));
        assertThat(first.state()).isEqualTo(AttemptState.STARTED);
        assertThat(store.begin(identity("one")).id()).isEqualTo(first.id());
        assertThat(store.begin(identity("two")).id()).isNotEqualTo(first.id());
        assertThat(jdbc.queryForObject("select count(*) from mate_execution_attempt",Integer.class)).isEqualTo(2);
    }
    @Test void terminalEvidenceIsImmutableAndIdempotent() {
        var attempt = store.begin(identity("one"));
        var evidence = store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.UNCERTAIN,List.of(observation("returned")));
        assertThat(store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.UNCERTAIN,List.of(observation("returned")))).isEqualTo(evidence);
        assertThatThrownBy(() -> store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.UNCERTAIN,List.of(observation("changed")))).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(1L,"conversation",evidence.getFirst().id())).contains(evidence.getFirst());
        assertThat(store.find(2L,"conversation",evidence.getFirst().id())).isEmpty();
        assertThat(store.find(1L,"other",evidence.getFirst().id())).isEmpty();
    }
    @Test void rejectsOldOwnerAndRollsBackConflictingObservations() {
        var attempt = store.begin(identity("one"));
        assertThatThrownBy(() -> store.finish(attempt.id(),"old",AttemptState.SUCCEEDED,EffectOutcome.NONE,List.of(observation("ok")))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,List.of(observation("ok"),observation("different")))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select state from mate_execution_attempt",String.class)).isEqualTo("STARTED");
        assertThat(jdbc.queryForObject("select count(*) from mate_execution_evidence",Integer.class)).isZero();
    }
    @Test void sanitizesAndBoundsSummariesInUtf8Bytes() {
        var attempt = store.begin(identity("one"));
        var evidence = store.finish(attempt.id(),"owner",AttemptState.FAILED,EffectOutcome.UNCERTAIN,List.of(observation("token=secretvalue " + "界".repeat(3000)))).getFirst();
        assertThat(evidence.observation().summary()).contains("[redacted]").doesNotContain("secretvalue");
        assertThat(evidence.observation().summary().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
    }
    @Test void boundsListsAndRejectsMissingScope() {
        var attempt = store.begin(identity("one"));
        store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,List.of(observation("ok")));
        assertThat(store.list(1L,"conversation",null,null,10000)).hasSize(1);
        assertThatThrownBy(() -> store.list(1L,null,null,null,20)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.list(1L,"conversation",Instant.EPOCH,1L,20)).isEmpty();
    }
    @Test void duplicateSameSourceWithinBatchRemainsIdempotent() {
        var attempt = store.begin(identity("one"));
        var result = store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,
                List.of(observation("same"),observation("same")));
        assertThat(result).hasSize(1);
    }
    @Test void migrationEnforcesLogicalAttemptAndSourceUniqueness() {
        var attempt = store.begin(identity("one"));
        store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,List.of(observation("same")));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO mate_execution_evidence (id,workspace_id,attempt_id,source_key,kind,result,source_level,observed_at)
                VALUES (42,1,?,'return','TOOL_RETURNED','OBSERVED','PLATFORM_OBSERVED',CURRENT_TIMESTAMP)
                """, attempt.id())).isInstanceOf(DuplicateKeyException.class);
        var duplicateLogical = new ExecutionIdentity(1L,"conversation","native","session","different","one",1,
                "provider-id","shell",null,null,null,null,null,null,"owner");
        assertThatThrownBy(() -> store.begin(duplicateLogical)).isInstanceOf(IllegalStateException.class);
    }
    @Test void cursorUsesIdForEqualObservationTimes() {
        var when = Instant.parse("2026-09-07T12:00:00Z");
        for (int i=0; i<3; i++) {
            var attempt = store.begin(identity("invocation-" + i));
            var observation = new EvidenceObservation("return",EvidenceKind.TOOL_RETURNED,EvidenceResult.OBSERVED,
                    SourceLevel.PLATFORM_OBSERVED,null,null,null,null,null,null,null,null,"ok",null,when,null);
            store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,List.of(observation));
        }
        var firstPage = store.list(1L,"conversation",null,null,2);
        var last = firstPage.getLast();
        assertThat(store.list(1L,"conversation",last.observation().observedAt(),last.id(),2)).hasSize(1)
                .doesNotContainAnyElementsOf(firstPage);
    }
    @Test void retentionPurgesOnlyBoundedOldUnreferencedFinishedAttempts() {
        var pinned = store.begin(identity("pinned"));
        var pinnedEvidence = store.finish(pinned.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,
                List.of(observation("pinned"))).getFirst();
        jdbc.update("""
                INSERT INTO mate_goal_criterion_evidence
                (id,workspace_id,goal_id,criterion_id,criterion_revision,evidence_id)
                VALUES (1,1,10,'criterion',1,?)
                """, pinnedEvidence.id());
        var active = store.begin(identity("active"));
        for (int i=0;i<2;i++) {
            var attempt = store.begin(identity("expired-" + i));
            store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,List.of(observation("old")));
        }
        jdbc.update("UPDATE mate_execution_attempt SET update_time=TIMESTAMP '2020-01-01 00:00:00'");
        jdbc.update("UPDATE mate_execution_evidence SET observed_at=TIMESTAMP '2020-01-01 00:00:00'");
        var recent = store.begin(identity("recent"));
        assertThat(store.purgeExpiredMetadata(Instant.now(),1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mate_execution_attempt",Integer.class)).isEqualTo(4);
        assertThat(store.findById(pinnedEvidence.id())).isPresent();
        assertThat(store.findAttempt(active.id())).isPresent();
        assertThat(store.findAttempt(recent.id())).isPresent();
        assertThat(store.purgeExpiredMetadata(Instant.now(),10000)).isEqualTo(1);
        assertThat(store.purgeExpiredMetadata(Instant.now(),100)).isZero();
    }
    @Test void privacyDeletionClearsContentAndBlocksLateReceiptsWhileKeepingBindings() {
        var attempt = store.begin(identity("finished"));
        var observation = new EvidenceObservation("return",EvidenceKind.ARTIFACT_SNAPSHOT,EvidenceResult.OBSERVED,
                SourceLevel.PLATFORM_OBSERVED,1L,1L,"input-digest","recipe",1L,"path", "file-id","digest",
                "private summary","payload",Instant.now(),null);
        var evidence = store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,List.of(observation)).getFirst();
        jdbc.update("""
                INSERT INTO mate_goal_criterion_evidence
                (id,workspace_id,goal_id,criterion_id,criterion_revision,evidence_id)
                VALUES (1,1,10,'criterion',1,?)
                """, evidence.id());
        var active = store.begin(identity("active"));
        assertThat(store.purgeConversation("conversation")).isEqualTo(1);
        assertThat(store.findById(evidence.id())).isEmpty();
        assertThat(store.list(1L,"conversation",null,null,20)).isEmpty();
        assertThat(jdbc.queryForMap("""
                SELECT summary,artifact_ref,artifact_digest,payload_ref,input_fingerprint,check_scope
                FROM mate_execution_evidence WHERE id=?
                """,evidence.id()).values()).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT state FROM mate_execution_attempt WHERE id=?",String.class,active.id())).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> store.finish(active.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,
                List.of(observation("late")))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.begin(identity("active"))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_criterion_evidence",Integer.class)).isEqualTo(1);
        assertThat(store.purgeConversation("conversation")).isZero();
    }
    @Test void mysqlMigrationUsesSupportedTimestampDefaults() throws Exception {
        String migration = new ClassPathResource("db/migration/mysql/V191__execution_evidence_ledger.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(migration).doesNotContain("CURRENT_DATETIME").contains("DEFAULT CURRENT_TIMESTAMP(6)");
    }
    @Test void reservationAuthorizesOnlyOneInserterAcrossConcurrentConnections() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<BeginResult> reserve = () -> {
                ready.countDown();
                start.await();
                return store.reserve(identity("shared"));
            };
            var first = executor.submit(reserve);
            var second = executor.submit(reserve);
            ready.await();
            start.countDown();
            var results = List.of(first.get(),second.get());
            assertThat(results).filteredOn(BeginResult::created).hasSize(1);
            assertThat(results.getFirst().attempt().id()).isEqualTo(results.getLast().attempt().id());
            assertThat(store.reserve(identity("shared")).created()).isFalse();
        }
    }
    @Test void concurrentWritersReturnOneAuthoritativeReceipt() throws Exception {
        var attempt = store.begin(identity("one"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.<Callable<List<ExecutionEvidence>>>of(
                () -> store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,List.of(observation("ok"))),
                () -> store.finish(attempt.id(),"owner",AttemptState.SUCCEEDED,EffectOutcome.NONE,List.of(observation("ok"))));
            var results = executor.invokeAll(tasks);
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        }
        assertThat(jdbc.queryForObject("select count(*) from mate_execution_evidence",Integer.class)).isEqualTo(1);
    }
}
