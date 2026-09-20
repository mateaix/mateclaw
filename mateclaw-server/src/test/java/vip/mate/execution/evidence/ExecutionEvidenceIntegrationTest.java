package vip.mate.execution.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ExecutionAttribution;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.execution.evidence.model.*;
import vip.mate.execution.evidence.service.*;
import vip.mate.team.service.TeamWorkerConversationGovernanceService;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionEvidenceIntegrationTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private ExecutionEvidenceStore store;
    private ExecutionIdentityResolver identities;
    private ExecutionEvidenceRecorder recorder;
    private final ChatOrigin origin = ChatOrigin.web("conv", "owner", 1L, null);
    private final AtomicInteger executions = new AtomicInteger();

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V191__execution_evidence_ledger.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mate_conversation(conversation_id VARCHAR(128) PRIMARY KEY,workspace_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mate_agent_goal(id BIGINT PRIMARY KEY,conversation_id VARCHAR(128),workspace_id BIGINT,status VARCHAR(20),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mate_goal_attempt(attempt_id VARCHAR(128) PRIMARY KEY,goal_id BIGINT,conversation_id VARCHAR(128),lease_token VARCHAR(128),state VARCHAR(20),lease_until TIMESTAMP)");
        jdbc.execute("CREATE TABLE mate_cron_job_run(id BIGINT PRIMARY KEY,conversation_id VARCHAR(128),status VARCHAR(20))");
        jdbc.execute("CREATE TABLE mate_tool_approval(pending_id VARCHAR(128) PRIMARY KEY,conversation_id VARCHAR(128))");
        jdbc.update("INSERT INTO mate_conversation VALUES('conv',1,0)");
        jdbc.update("INSERT INTO mate_tool_approval VALUES('approval-one','conv')");
        var teams = mock(TeamWorkerConversationGovernanceService.class);
        when(teams.resolve("conv", null, null)).thenReturn(Optional.empty());
        when(teams.resolve("child", null, null)).thenReturn(Optional.empty());
        identities = new ExecutionIdentityResolver(jdbc, teams);
        transactions = new DataSourceTransactionManager(source);
        var properties = new ExecutionEvidenceProperties();
        store = new ExecutionEvidenceStore(jdbc, transactions, properties);
        store.setOwnershipValidator(identities);
        recorder = new ExecutionEvidenceRecorder(store, identities, properties, new SimpleMeterRegistry());
    }

    @Test void repeatedProviderIdsInDifferentRoundsProduceDifferentDurableAttempts() {
        var executor = executor(false);
        executor.execute(List.of(call()), "conv", "1", false, "owner", null, origin);
        executor.execute(List.of(call()), "conv", "1", false, "owner", null, origin);
        assertEquals(2, executions.get());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mate_execution_attempt", Integer.class));
        var reopened = new ExecutionEvidenceStore(jdbc, transactions, new ExecutionEvidenceProperties());
        assertEquals(4, reopened.list(1L, "conv", null, null, 20).size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_execution_evidence WHERE result='PASS'", Integer.class));
    }

    @Test void approvalReplayIsCapturedOnceAndDoesNotPoisonLaterOrdinaryTools() {
        ChatOrigin replay = origin.withApprovalId("approval-one");
        var executor = executor(false);
        executor.execute(List.of(call()), "conv", "1", true, "owner", null, replay);
        executor.execute(List.of(call()), "conv", "1", true, "owner", null, replay);
        assertEquals(1, executions.get());
        executor.execute(List.of(call()), "conv", "1", false, "owner", null, replay);
        assertEquals(2, executions.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_execution_attempt WHERE approval_id='approval-one'", Integer.class));
    }

    @Test void preapprovedDirectResultKeepsOnlyMetadataAndPreservesDirectOutput() {
        var outputs = new ArrayList<DirectToolOutput>();
        var events = new ArrayList<GraphEventPublisher.GraphEvent>();
        var response = executor(true).executePreApproved(call(), "{}", events, "conv", null,
                outputs, origin.withApprovalId("approval-one"));
        assertFalse(response.responseData().contains("private-output"));
        assertEquals("private-output", outputs.getFirst().fullResult());
        var evidence = store.list(1L, "conv", null, null, 20);
        assertEquals(1, evidence.size());
        assertEquals(EvidenceKind.TOOL_RETURNED, evidence.getFirst().observation().kind());
        assertFalse(evidence.toString().contains("private"));
    }

    @Test void attributionSurvivesOriginWithersAndJsonButMustMatchPersistedBusinessRows() throws Exception {
        jdbc.update("INSERT INTO mate_agent_goal VALUES(100,'conv',1,'active',0)");
        jdbc.update("INSERT INTO mate_goal_attempt VALUES('goal-attempt',100,'conv','lease','running',?)", LocalDateTime.now().plusMinutes(2));
        var attributed = origin.withExecutionAttribution(new ExecutionAttribution(100L, "goal-attempt", null, null, "lease"));
        var mapper = new ObjectMapper();
        attributed = mapper.readValue(mapper.writeValueAsString(attributed), ChatOrigin.class)
                .withAgent(20L).withSender("Owner", "web", null).withWorkspace(1L, null)
                .withConversationId("conv").withBaseUrl("http://localhost").withOriginMessageId(19L);
        var identity = identities.resolve(attributed, "invocation", "provider", "tool");
        assertEquals(100L, identity.goalId());
        assertEquals("goal-attempt", identity.goalAttemptId());
        assertNull(identities.resolve(attributed.withWorkspace(2L, null), "invocation", "provider", "tool"));
        assertNull(identities.resolve(attributed.withApprovalId("not-real"), "invocation", "provider", "tool"));
    }

    @Test void revokedOwnerCannotPublishAfterAConcurrentRevocationCommits() throws Exception {
        jdbc.update("INSERT INTO mate_agent_goal VALUES(100,'conv',1,'active',0)");
        jdbc.update("INSERT INTO mate_goal_attempt VALUES('goal-attempt',100,'conv','lease','running',?)", LocalDateTime.now().plusMinutes(2));
        var linked = origin.withExecutionAttribution(new ExecutionAttribution(100L, "goal-attempt", null, null, "lease"));
        var attempt = store.begin(identities.resolve(linked, "invocation", "provider", "tool"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revoke = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.update("UPDATE mate_goal_attempt SET state='cancelled' WHERE attempt_id='goal-attempt'");
                locked.countDown();
                await(release);
                return null;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var finish = pool.submit(() -> assertThrows(IllegalStateException.class,
                    () -> store.finish(attempt.id(), "lease", AttemptState.SUCCEEDED, EffectOutcome.UNCERTAIN,
                            List.of(new EvidenceObservation("callback", EvidenceKind.TOOL_RETURNED, EvidenceResult.OBSERVED, SourceLevel.PLATFORM_OBSERVED, "returned")))));
            release.countDown(); revoke.get(5, TimeUnit.SECONDS); finish.get(5, TimeUnit.SECONDS);
        }
        assertEquals(AttemptState.STARTED, store.findAttempt(attempt.id()).orElseThrow().state());
        assertTrue(store.list(1L, "conv", null, null, 20).isEmpty());
    }

    @Test void identicalReceiptRetryAfterOwnerSettlementReturnsOriginalEvidence() {
        jdbc.update("INSERT INTO mate_cron_job_run VALUES(100,'conv','running')");
        var linked = origin.withExecutionAttribution(new ExecutionAttribution(null, null, 100L, null, "cron:100"));
        var attempt = store.begin(identities.resolve(linked, "invocation", "provider", "tool"));
        var observations = List.of(new EvidenceObservation("callback", EvidenceKind.TOOL_RETURNED,
                EvidenceResult.OBSERVED, SourceLevel.PLATFORM_OBSERVED, "returned"));
        var first = store.finish(attempt.id(), "cron:100", AttemptState.SUCCEEDED, EffectOutcome.UNCERTAIN, observations);
        jdbc.update("UPDATE mate_cron_job_run SET status='completed' WHERE id=100");
        assertEquals(first, store.finish(attempt.id(), "cron:100", AttemptState.SUCCEEDED, EffectOutcome.UNCERTAIN, observations));
    }

    @Test void childConversationDropsParentGoalAndCronAttributionButCanRecordOwnTools() {
        jdbc.update("INSERT INTO mate_conversation VALUES('child',1,0)");
        for (ExecutionAttribution source : List.of(new ExecutionAttribution(100L, "parent-attempt", null, null, "lease"),
                new ExecutionAttribution(null, null, 100L, null, "cron:100"))) {
            var child = origin.withExecutionAttribution(source).withConversationId("child");
            assertNull(child.executionAttribution());
            var identity = identities.resolve(child, UUID.randomUUID().toString(), "provider", "tool");
            assertNotNull(identity);
            assertNull(identity.goalId());
            assertNull(identity.cronRunId());
            assertTrue(store.reserve(identity).created());
        }
    }

    @Test void deletionBetweenResolutionAndReservationCannotRecreateErasedEvidence() {
        var identity = identities.resolve(origin, "invocation", "provider", "tool");
        jdbc.update("UPDATE mate_conversation SET deleted=1 WHERE conversation_id='conv'");
        store.purgeConversation("conv");
        assertThrows(IllegalStateException.class, () -> store.reserve(identity));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_execution_attempt", Integer.class));
    }

    private ToolExecutionExecutor executor(boolean direct) {
        ToolCallback callback = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("evidence_tool").description("test").inputSchema("{}").build(); }
            public ToolMetadata getToolMetadata() { return ToolMetadata.builder().returnDirect(direct).build(); }
            public String call(String input) { throw new AssertionError("Explicit context required"); }
            public String call(String input, ToolContext context) {
                executions.incrementAndGet();
                var sink = ExecutionObservationSink.from(context);
                assertNotNull(sink);
                sink.command(1, false, false, false);
                return "private-output";
            }
        };
        ToolGuard guard = (name, args) -> ToolGuardResult.allow();
        var executor = new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), List.of(callback)), guard, null, null);
        executor.setExecutionEvidenceRecorder(recorder);
        return executor;
    }

    private AssistantMessage.ToolCall call() { return new AssistantMessage.ToolCall("provider-id", "function", "evidence_tool", "{}"); }
    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
