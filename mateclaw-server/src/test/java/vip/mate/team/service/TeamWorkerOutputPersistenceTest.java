package vip.mate.team.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import vip.mate.agent.AgentService;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.agent.graph.executor.ToolResultProperties;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.auth.service.AuthService;
import vip.mate.channel.repository.ChannelSessionMapper;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.config.MybatisPlusConfig;
import vip.mate.i18n.I18nService;
import vip.mate.task.repository.AsyncTaskMapper;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.repository.TeamRunMapper;
import vip.mate.team.repository.TeamTaskCommentMapper;
import vip.mate.team.repository.TeamTaskEventMapper;
import vip.mate.team.repository.TeamTaskMapper;
import vip.mate.team.tool.TeamTasksTool;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.tool.builtin.ReadFileTool;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MyBatis/H2 persistence and service gates; only the model and external scheduling are stubbed. */
class TeamWorkerOutputPersistenceTest {
    private JdbcTemplate jdbc;
    private ConversationService conversations;
    private TeamTaskService tasks;
    private TeamTasksTool tool;
    private TeamDispatchService dispatch;
    private AgentService agents;
    private TeamEventChannel events;
    private TeamAnnounceService announcements;

    @BeforeEach
    void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:team_output_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        String[] migrations = {"V1__baseline_schema.sql", "V5__conversation_parent.sql",
                "V114__conversation_pinned.sql", "V116__conversation_model.sql",
                "V123__conversation_progress_ledger.sql", "V151__webchat_session_id.sql",
                "V152__webchat_archive_and_revocation.sql", "V166__message_usage_detail.sql",
                "V172__agent_team_foundation.sql", "V174__team_task_event_timeline.sql",
                "V181__team_run_foundation.sql", "V183__conversation_kind.sql"};
        var populator = new ResourceDatabasePopulator();
        for (String migration : migrations) {
            populator.addScript(new ClassPathResource("db/migration/h2/" + migration));
        }
        populator.execute(source);
        jdbc = new JdbcTemplate(source);
        var configuration = new MybatisConfiguration();
        var global = GlobalConfigUtils.defaults().setMetaObjectHandler(new MybatisPlusConfig());
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addMapper(TeamTaskMapper.class);
        configuration.addMapper(TeamTaskCommentMapper.class);
        configuration.addMapper(TeamTaskEventMapper.class);
        configuration.addMapper(TeamRunMapper.class);
        configuration.addMapper(ConversationMapper.class);
        configuration.addMapper(MessageMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        var session = new SqlSessionTemplate(factory.getObject());
        var teamService = mock(TeamService.class);
        var team = new AgentTeamEntity();
        team.setId(10L);
        team.setWorkspaceId(30L);
        team.setLeadAgentId(1L);
        when(teamService.getTeam(10L)).thenReturn(team);
        when(teamService.getTeamForAgent(1L)).thenReturn(Optional.of(team));
        when(teamService.getTeamForAgent(2L)).thenReturn(Optional.of(team));
        when(teamService.isLead(team, 1L)).thenReturn(true);
        var runService = new TeamRunService(session.getMapper(TeamRunMapper.class),
                session.getMapper(TeamTaskMapper.class), teamService);
        var taskTarget = new TeamTaskService(session.getMapper(TeamTaskMapper.class),
                session.getMapper(TeamTaskCommentMapper.class), session.getMapper(TeamTaskEventMapper.class),
                teamService, mock(TeamRunProjectionScheduler.class), runService);
        var proxy = new ProxyFactory(taskTarget);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        tasks = (TeamTaskService) proxy.getProxy();
        var agentMapper = mock(AgentMapper.class);
        conversations = new ConversationService(session.getMapper(ConversationMapper.class),
                session.getMapper(MessageMapper.class), agentMapper, new ObjectMapper(),
                mock(ToolApprovalMapper.class), mock(AsyncTaskMapper.class), mock(ChannelSessionMapper.class),
                mock(ApplicationEventPublisher.class), mock(AuthService.class), mock(WorkspaceService.class),
                mock(ChatUploadLocationResolver.class));
        conversations.getOrCreateConversation("lead-conv", 1L, "system", 30L);
        jdbc.update("""
                INSERT INTO mate_team_run(id,team_id,workspace_id,lead_agent_id,lead_conversation_id,
                    title,objective,status) VALUES(20,10,30,1,'lead-conv','Run','Analyze results','running')
                """);
        jdbc.update("""
                INSERT INTO mate_team_task(id,team_id,run_id,task_number,subject,status,assignee_agent_id,
                    owner_agent_id,dispatch_count,lead_conversation_id)
                    VALUES(5,10,20,5,'Analyze results','in_progress',2,2,1,'lead-conv')
                """);
        agents = mock(AgentService.class);
        events = mock(TeamEventChannel.class);
        announcements = mock(TeamAnnounceService.class);
        dispatch = spy(new TeamDispatchService(teamService, tasks, agents, conversations,
                mock(ChatStreamTracker.class), announcements, events, mock(ApprovalWorkflowService.class)));
        doNothing().when(dispatch).requestDispatch(anyLong());
        tool = new TeamTasksTool(teamService, tasks, runService, dispatch, events, conversations, agentMapper);
        ToolExecutionContext.set("lead-conv", "system");
    }

    @AfterEach
    void cleanup() {
        ToolExecutionContext.clear();
    }

    private String output() {
        return tool.team_tasks("output", "5", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private void runWorker(String reply) {
        when(agents.chatWithUsage(eq(2L), anyString(), anyString()))
                .thenReturn(AgentService.ChatResult.contentOnly(reply));
        dispatch.runTask(10L, tasks.getTask(5L));
    }

    @Test
    void completeReplySurvivesPersistenceWhileBoardSummaryIsStrictlyBounded() {
        String full = "Opening findings\n" + "Evidence line\n".repeat(2000) + "Final verification: passed";
        runWorker(full);

        var task = tasks.getTask(5L);
        assertEquals(TeamTaskStatus.COMPLETED, task.getStatus());
        assertTrue(task.getResult().length() <= 8000);
        assertTrue(task.getResult().endsWith("Final verification: passed"));
        assertTrue(task.getResult().contains("action=\"output\""));
        assertEquals(full, jdbc.queryForObject(
                "SELECT content FROM mate_message WHERE conversation_id=? AND role='assistant'",
                String.class, task.getConversationId()));
        assertTrue(output().endsWith(full));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_team_task_event WHERE task_id=5 AND event_type='completed'", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker_workspace", "run_workspace", "run_team", "task_parent", "task_assignee"})
    void persistedLinkageMismatchCannotDiscloseWorkerText(String mismatch) {
        runWorker("CONFIDENTIAL-WORKER-RESULT");
        switch (mismatch) {
            case "worker_workspace" -> jdbc.update(
                    "UPDATE mate_conversation SET workspace_id=999 WHERE conversation_id=?",
                    tasks.getTask(5L).getConversationId());
            case "run_workspace" -> jdbc.update("UPDATE mate_team_run SET workspace_id=999 WHERE id=20");
            case "run_team" -> jdbc.update("UPDATE mate_team_run SET team_id=999 WHERE id=20");
            case "task_parent" -> jdbc.update("UPDATE mate_team_task SET lead_conversation_id='unrelated' WHERE id=5");
            case "task_assignee" -> jdbc.update("UPDATE mate_team_task SET assignee_agent_id=999 WHERE id=5");
            default -> throw new AssertionError(mismatch);
        }

        String result = output();

        assertTrue(result.startsWith("Error:"));
        assertFalse(result.contains("CONFIDENTIAL"));
    }

    @Test
    void retryReadsReplacementConversationEvenWhenDispatchCounterRepeats() {
        runWorker("OLD-ATTEMPT-RESULT");
        String originalConversation = tasks.getTask(5L).getConversationId();
        assertEquals(1, tasks.getTask(5L).getDispatchCount());
        assertTrue(tasks.resumeForWorkerFeedback(5L));
        assertTrue(tasks.failTask(5L, "follow-up failed"));
        assertTrue(tasks.retryTask(5L));
        assertTrue(tasks.assignTask(5L, 2L));
        assertTrue(tasks.tryAcquireDispatch(5L));

        runWorker("NEW-ATTEMPT-RESULT");

        assertEquals(1, tasks.getTask(5L).getDispatchCount());
        assertNotEquals(originalConversation, tasks.getTask(5L).getConversationId());
        String result = output();
        assertTrue(result.endsWith("NEW-ATTEMPT-RESULT"));
        assertFalse(result.contains("OLD-ATTEMPT-RESULT"));
        assertEquals("OLD-ATTEMPT-RESULT", jdbc.queryForObject(
                "SELECT content FROM mate_message WHERE conversation_id=? AND role='assistant'",
                String.class, originalConversation));
    }

    @Test
    void normalReturnAfterDurableCancellationDoesNotAnnounceCompletion() {
        when(agents.chatWithUsage(eq(2L), anyString(), anyString())).thenAnswer(invocation -> {
            tasks.cancelTask(5L, "cancelled during model invocation");
            return AgentService.ChatResult.contentOnly("late normal return");
        });

        dispatch.runTask(10L, tasks.getTask(5L));

        assertEquals(TeamTaskStatus.CANCELLED, tasks.getTask(5L).getStatus());
        assertNull(tasks.getTask(5L).getResult());
        verify(events, never()).publishTaskEvent(any(), eq("team_task_completed"), any());
        verifyNoInteractions(announcements);
    }

    @Test
    void approvalTaskRemainsInReviewWithCompleteOutputAvailable() {
        jdbc.update("UPDATE mate_team_task SET require_approval=TRUE WHERE id=5");

        runWorker("Produced draft; human approval required");

        assertEquals(TeamTaskStatus.IN_REVIEW, tasks.getTask(5L).getStatus());
        assertTrue(output().contains("Produced draft; human approval required"));
        verify(events).publishTaskEvent(any(), eq("team_task_in_review"), any());
        verify(events, never()).publishTaskEvent(any(), eq("team_task_completed"), any());
    }

    @Test
    void explicitOversizedCompletionIsRejectedAndFullFinalReplyRemainsRetrievable() {
        String full = "Full analysis\n" + "evidence\n".repeat(2000) + "Final verification";
        when(agents.chatWithUsage(eq(2L), anyString(), anyString())).thenAnswer(invocation -> {
            ToolExecutionContext.set(invocation.getArgument(2), "system");
            try {
                String response = tool.team_tasks("complete", "5", null, null, null, null, null,
                        null, null, null, null, full, null, null, null, null, null, null, null);
                assertTrue(response.startsWith("Error:"));
                assertTrue(response.contains("8000"));
                assertEquals(TeamTaskStatus.IN_PROGRESS, tasks.getTask(5L).getStatus());
                return AgentService.ChatResult.contentOnly(full);
            } finally {
                ToolExecutionContext.set("lead-conv", "system");
            }
        });

        dispatch.runTask(10L, tasks.getTask(5L));

        assertEquals(TeamTaskStatus.COMPLETED, tasks.getTask(5L).getStatus());
        assertTrue(tasks.getTask(5L).getResult().length() <= 8000);
        assertTrue(output().endsWith(full));
    }

    @Test
    void largeRetrievedOutputSpillsLosslesslyAndItsTailCanBeRead(@TempDir Path workspace) throws Exception {
        String full = "Opening findings\n" + "evidence line\n".repeat(4000) + "Final verification: passed";
        runWorker(full);
        String retrieved = output();
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(workspace.resolve("tool-results").toString());
        var storage = new ToolResultStorage(properties);

        String preview = storage.persistIfOversized(retrieved, "team_tasks", "output-5",
                "lead-conv", workspace.toString());

        assertTrue(preview.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX));
        assertTrue(preview.contains("read_file"));
        var pathMatcher = java.util.regex.Pattern.compile("path=(\\S+)").matcher(preview);
        assertTrue(pathMatcher.find());
        Path spill = Path.of(pathMatcher.group(1));
        assertEquals(retrieved, Files.readString(spill));
        ToolExecutionContext.set("lead-conv", "system", workspace.toString());
        try {
            var reader = new ReadFileTool(mock(I18nService.class));
            String tail = reader.read_file(spill.toString(), 4000, null, null, null);
            assertTrue(tail.contains("Final verification: passed"), tail);
        } finally {
            ToolExecutionContext.set("lead-conv", "system");
        }
    }

    /** Opt-in verification across the real 60-minute lease and 20-minute heartbeat cadence. */
    @Test
    @Timeout(value = 105, unit = TimeUnit.MINUTES)
    @EnabledIfSystemProperty(named = "mateclaw.team.lease-soak", matches = "true")
    void longWorkerRetainsLeaseAcrossOriginalExpiryAndSettlesAfterCleanup() throws Exception {
        long minutes = Long.getLong("mateclaw.team.lease-soak.minutes", 95L);
        assertTrue(minutes >= 65 && minutes <= 100, "Soak must cross the actual initial lease expiry");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(agents.chatWithUsage(eq(2L), anyString(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(102, TimeUnit.MINUTES));
            return AgentService.ChatResult.contentOnly("Long-running worker verification complete");
        });
        jdbc.update("UPDATE mate_team_task SET lock_expires_at=? WHERE id=5",
                LocalDateTime.now().plusMinutes(TeamTaskService.LOCK_MINUTES));
        LocalDateTime originalExpiry = tasks.getTask(5L).getLockExpiresAt();
        long started = System.nanoTime();
        long duration = Duration.ofMinutes(minutes).toNanos();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = executor.submit(() -> dispatch.runTask(10L, tasks.getTask(5L)));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                while (System.nanoTime() - started < duration) {
                    Thread.sleep(Duration.ofSeconds(30));
                    assertTrue(tasks.recoverStaleTasks().isEmpty(), "Live worker was reclaimed as stale");
                    var current = tasks.getTask(5L);
                    assertEquals(TeamTaskStatus.IN_PROGRESS, current.getStatus());
                    assertTrue(current.getLockExpiresAt().isAfter(LocalDateTime.now()));
                    System.out.printf("TEAM_LEASE_SOAK elapsedSeconds=%d originalExpiry=%s renewedExpiry=%s status=%s%n",
                            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started), originalExpiry,
                            current.getLockExpiresAt(), current.getStatus());
                }
                assertTrue(LocalDateTime.now().isAfter(originalExpiry));
                assertTrue(tasks.getTask(5L).getLockExpiresAt().isAfter(originalExpiry));
            } finally {
                release.countDown();
            }
            worker.get(10, TimeUnit.SECONDS);
        }
        assertEquals(TeamTaskStatus.COMPLETED, tasks.getTask(5L).getStatus());
        assertNull(tasks.getTask(5L).getLockExpiresAt());
        assertTrue(output().contains("Long-running worker verification complete"));
    }
    private String retryAsLead() {
        return tool.team_tasks("retry", "5", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void leadCannotResetExhaustedDispatchBudgetEvenAfterRepeatedRequests() {
        jdbc.update("UPDATE mate_team_task SET status='failed',dispatch_count=3,reason='dispatch limit' WHERE id=5");
        for (int request=0; request<12; request++) {
            String reply=retryAsLead();
            assertTrue(reply.startsWith("Error:"), reply);
            assertTrue(reply.contains("limit"), reply);
            assertEquals(3,tasks.getTask(5L).getDispatchCount());
            assertEquals("failed",tasks.getTask(5L).getStatus());
            assertEquals("dispatch limit",tasks.getTask(5L).getReason());
        }
        verify(dispatch,never()).requestDispatch(anyLong());
        verifyNoInteractions(events);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mate_team_task_event WHERE task_id=5",Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings={"failed","stale"})
    void leadRetryKeepsAttemptsAndFailureFeedback(String status) {
        jdbc.update("UPDATE mate_team_task SET status=?,dispatch_count=2,reason='Missing input' WHERE id=5",status);
        assertTrue(retryAsLead().startsWith("✓"));
        assertEquals("pending",tasks.getTask(5L).getStatus());
        assertEquals(2,tasks.getTask(5L).getDispatchCount());
        assertEquals("Missing input",tasks.getTask(5L).getReason());
        assertTrue(tasks.assignTask(5L,2L));
        assertTrue(tasks.tryAcquireDispatch(5L));
        assertEquals(3,tasks.getTask(5L).getDispatchCount());
        assertTrue(tasks.failTask(5L,"Still missing input"));
        assertTrue(retryAsLead().startsWith("Error:"));
        verify(dispatch,times(1)).requestDispatch(10L);
    }

    @Test
    void fullLeadRetryCycleStopsAtThreeTotalDispatchesButHumanCanReset() {
        when(agents.chatWithUsage(eq(2L),anyString(),anyString()))
                .thenThrow(new IllegalStateException("Simulated unavailable input"));
        for(int attempt=1;attempt<=3;attempt++) {
            dispatch.runTask(10L,tasks.getTask(5L));
            assertEquals("failed",tasks.getTask(5L).getStatus());
            assertEquals(attempt,tasks.getTask(5L).getDispatchCount());
            if(attempt<3) {
                assertTrue(retryAsLead().startsWith("✓"));
                assertTrue(tasks.assignTask(5L,2L));
                assertTrue(tasks.tryAcquireDispatch(5L));
            }
        }
        for(int request=0;request<12;request++) assertTrue(retryAsLead().startsWith("Error:"));
        verify(agents,times(3)).chatWithUsage(eq(2L),anyString(),anyString());
        assertEquals(3,tasks.getTask(5L).getDispatchCount());
        // The authenticated human-only controller intentionally retains its explicit reset path.
        assertTrue(tasks.retryTask(5L));
        assertEquals(0,tasks.getTask(5L).getDispatchCount());
        assertTrue(tasks.assignTask(5L,2L));
        assertTrue(tasks.tryAcquireDispatch(5L));
        assertEquals(1,tasks.getTask(5L).getDispatchCount());
    }

    @Test
    void concurrentLeadRetriesHaveOneWinnerAndCannotResetBudget() throws Exception {
        jdbc.update("UPDATE mate_team_task SET status='failed',dispatch_count=2 WHERE id=5");
        var start=new CountDownLatch(1);
        java.util.concurrent.Callable<String> retry=()->{
            ToolExecutionContext.set("lead-conv","system");
            try {start.await();return retryAsLead();}
            finally {ToolExecutionContext.clear();}
        };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(retry);var second=pool.submit(retry);start.countDown();
            var replies=java.util.List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(1,replies.stream().filter(r->r.startsWith("✓")).count());
            assertEquals(1,replies.stream().filter(r->r.startsWith("Error:")).count());
        }
        assertEquals(2,tasks.getTask(5L).getDispatchCount());
        assertEquals("pending",tasks.getTask(5L).getStatus());
        verify(dispatch,times(1)).requestDispatch(10L);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM mate_team_task_event WHERE task_id=5 AND event_type='retried'",Integer.class));
    }

}
