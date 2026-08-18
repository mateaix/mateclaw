package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.event.TeamRunDispatchCommittedIntent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamRunCommittedIntentEventTest {

    private static final Long TEAM_ID = 10L;
    private static final Long RUN_ID = 20L;
    private static final Long WORKSPACE_ID = 30L;

    @Test
    void manualRunDispatchesOnlyAfterTheRealTransactionCommits() {
        try (AnnotationConfigApplicationContext context = context()) {
            TeamRunService runService = context.getBean(TeamRunService.class);
            TeamTaskService taskService = context.getBean(TeamTaskService.class);
            TeamDispatchService dispatchService = context.getBean(TeamDispatchService.class);
            TeamManualTaskService service = context.getBean(TeamManualTaskService.class);
            when(runService.startRun(any())).thenReturn(run(TeamRunStatus.PLANNING));
            when(taskService.createTask(any())).thenReturn(task(1L, TeamTaskStatus.PENDING, null));
            when(runService.sealRunWithResult(RUN_ID, WORKSPACE_ID))
                    .thenReturn(new TeamRunService.SealResult(run(TeamRunStatus.RUNNING), true));

            transactions(context).executeWithoutResult(status -> {
                service.createTask(team(), TeamTaskCreateCommand.builder()
                        .subject("dashboard task")
                        .assigneeAgentId(2L)
                        .build());
                verify(dispatchService, never()).requestDispatch(TEAM_ID);
            });

            verify(dispatchService).requestDispatch(TEAM_ID);
        }
    }

    @Test
    void cancellationInterruptsAndPublishesOnlyAfterTheRealTransactionCommits() {
        try (AnnotationConfigApplicationContext context = context()) {
            TeamRunService runService = context.getBean(TeamRunService.class);
            TeamTaskService taskService = context.getBean(TeamTaskService.class);
            TeamDispatchService dispatchService = context.getBean(TeamDispatchService.class);
            TeamRunEventPublisher eventPublisher = context.getBean(TeamRunEventPublisher.class);
            TeamRunApplicationService service = context.getBean(TeamRunApplicationService.class);
            TeamRunEntity cancelled = run(TeamRunStatus.CANCELLED);
            TeamRunView view = view();
            when(runService.cancelRunWithResult(RUN_ID, WORKSPACE_ID, "stop"))
                    .thenReturn(new TeamRunService.CancelResult(cancelled, true));
            when(taskService.listTasksByRun(RUN_ID)).thenReturn(List.of(
                    task(1L, TeamTaskStatus.PENDING, null),
                    task(2L, TeamTaskStatus.IN_PROGRESS, "worker-conversation")));
            when(runService.buildView(cancelled)).thenReturn(view);

            transactions(context).executeWithoutResult(status -> {
                service.cancelRun(RUN_ID, WORKSPACE_ID, "stop");
                verify(dispatchService, never()).interruptRun(any());
                verify(eventPublisher, never()).publishCancelled(any());
            });

            TeamTaskEntity expectedSnapshot = new TeamTaskEntity();
            expectedSnapshot.setId(2L);
            expectedSnapshot.setTaskNumber(2);
            expectedSnapshot.setConversationId("worker-conversation");
            verify(dispatchService).interruptRun(expectedSnapshot);
            verify(eventPublisher).publishCancelled(view);
        }
    }

    @Test
    void listenerFailureDoesNotEscapeTheCommittedTransaction() {
        try (AnnotationConfigApplicationContext context = context()) {
            TeamRunService runService = context.getBean(TeamRunService.class);
            TeamTaskService taskService = context.getBean(TeamTaskService.class);
            TeamDispatchService dispatchService = context.getBean(TeamDispatchService.class);
            TeamRunEventPublisher eventPublisher = context.getBean(TeamRunEventPublisher.class);
            TeamRunApplicationService service = context.getBean(TeamRunApplicationService.class);
            TeamRunEntity cancelled = run(TeamRunStatus.CANCELLED);
            TeamRunView view = view();
            when(runService.cancelRunWithResult(RUN_ID, WORKSPACE_ID, null))
                    .thenReturn(new TeamRunService.CancelResult(cancelled, true));
            when(taskService.listTasksByRun(RUN_ID)).thenReturn(List.of(
                    task(2L, TeamTaskStatus.IN_PROGRESS, "worker-conversation")));
            when(runService.buildView(cancelled)).thenReturn(view);
            doThrow(new IllegalStateException("interrupt failed"))
                    .when(dispatchService).interruptRun(any());

            assertDoesNotThrow(() -> transactions(context).executeWithoutResult(
                    status -> service.cancelRun(RUN_ID, WORKSPACE_ID, null)));

            verify(eventPublisher).publishCancelled(view);
        }
    }

    @Test
    void dispatchIntentFallsBackToImmediateExecutionWithoutATransaction() {
        try (AnnotationConfigApplicationContext context = context()) {
            TeamDispatchService dispatchService = context.getBean(TeamDispatchService.class);

            context.publishEvent(new TeamRunDispatchCommittedIntent(TEAM_ID));

            verify(dispatchService).requestDispatch(TEAM_ID);
        }
    }

    @Test
    void rolledBackManualRunNeverDispatches() {
        try (AnnotationConfigApplicationContext context = context()) {
            TeamRunService runService = context.getBean(TeamRunService.class);
            TeamTaskService taskService = context.getBean(TeamTaskService.class);
            TeamDispatchService dispatchService = context.getBean(TeamDispatchService.class);
            TeamRunEventPublisher eventPublisher = context.getBean(TeamRunEventPublisher.class);
            TeamManualTaskService service = context.getBean(TeamManualTaskService.class);
            when(runService.startRun(any())).thenReturn(run(TeamRunStatus.PLANNING));
            when(taskService.createTask(any())).thenReturn(task(1L, TeamTaskStatus.PENDING, null));
            when(runService.sealRunWithResult(RUN_ID, WORKSPACE_ID))
                    .thenReturn(new TeamRunService.SealResult(run(TeamRunStatus.RUNNING), true));

            transactions(context).executeWithoutResult(status -> {
                service.createTask(team(), TeamTaskCreateCommand.builder()
                        .subject("dashboard task")
                        .assigneeAgentId(2L)
                        .build());
                status.setRollbackOnly();
            });

            verify(dispatchService, never()).requestDispatch(any());
            verify(dispatchService, never()).interruptRun(any());
            verify(eventPublisher, never()).publishCancelled(any());
        }
    }

    @Test
    void rolledBackCancellationNeverInterruptsOrPublishes() {
        try (AnnotationConfigApplicationContext context = context()) {
            TeamRunService runService = context.getBean(TeamRunService.class);
            TeamTaskService taskService = context.getBean(TeamTaskService.class);
            TeamDispatchService dispatchService = context.getBean(TeamDispatchService.class);
            TeamRunEventPublisher eventPublisher = context.getBean(TeamRunEventPublisher.class);
            TeamRunApplicationService service = context.getBean(TeamRunApplicationService.class);
            TeamRunEntity cancelled = run(TeamRunStatus.CANCELLED);
            when(runService.cancelRunWithResult(RUN_ID, WORKSPACE_ID, "stop"))
                    .thenReturn(new TeamRunService.CancelResult(cancelled, true));
            when(taskService.listTasksByRun(RUN_ID)).thenReturn(List.of(
                    task(2L, TeamTaskStatus.IN_PROGRESS, "worker-conversation")));
            when(runService.buildView(cancelled)).thenReturn(view());

            assertThrows(IllegalStateException.class,
                    () -> transactions(context).executeWithoutResult(status -> {
                        service.cancelRun(RUN_ID, WORKSPACE_ID, "stop");
                        throw new IllegalStateException("roll back");
                    }));

            verify(dispatchService, never()).requestDispatch(any());
            verify(dispatchService, never()).interruptRun(any());
            verify(eventPublisher, never()).publishCancelled(any());
        }
    }

    private static AnnotationConfigApplicationContext context() {
        return new AnnotationConfigApplicationContext(TestConfig.class);
    }

    private static TransactionTemplate transactions(AnnotationConfigApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    private static AgentTeamEntity team() {
        AgentTeamEntity team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setWorkspaceId(WORKSPACE_ID);
        team.setLeadAgentId(1L);
        return team;
    }

    private static TeamRunEntity run(String status) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setTeamId(TEAM_ID);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setLeadConversationId("dashboard-team-10");
        run.setStatus(status);
        return run;
    }

    private static TeamTaskEntity task(Long id, String status, String conversationId) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(id);
        task.setTaskNumber(id.intValue());
        task.setRunId(RUN_ID);
        task.setStatus(status);
        task.setConversationId(conversationId);
        return task;
    }

    private static TeamRunView view() {
        return new TeamRunView(RUN_ID, TEAM_ID, WORKSPACE_ID, 1L, "dashboard-team-10",
                null, "Run", "Objective", TeamRunStatus.CANCELLED, null, "stop", null,
                null, null, null, null,
                new TeamRunView.Progress(2, 0, 0, 0, 2), List.of());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        TeamRunService runService() {
            return mock(TeamRunService.class);
        }

        @Bean
        TeamTaskService taskService() {
            return mock(TeamTaskService.class);
        }

        @Bean
        TeamDispatchService dispatchService() {
            return mock(TeamDispatchService.class);
        }

        @Bean
        TeamRunEventPublisher teamRunEventPublisher() {
            return mock(TeamRunEventPublisher.class);
        }

        @Bean
        TeamManualTaskService manualTaskService(TeamRunService runService,
                                                TeamTaskService taskService,
                                                ApplicationEventPublisher events) {
            return new TeamManualTaskService(runService, taskService, events);
        }

        @Bean
        TeamRunApplicationService teamRunApplicationService(TeamRunService runService,
                                                            TeamTaskService taskService,
                                                            ApplicationEventPublisher events) {
            return new TeamRunApplicationService(runService, taskService, events);
        }

        @Bean
        TeamRunCommittedIntentListener teamRunCommittedIntentListener(
                TeamDispatchService dispatchService, TeamRunEventPublisher eventPublisher) {
            return new TeamRunCommittedIntentListener(dispatchService, eventPublisher);
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {

        private final ThreadLocal<TestTransaction> current = new ThreadLocal<>();

        @Override
        protected Object doGetTransaction() {
            TestTransaction transaction = current.get();
            return transaction == null ? new TestTransaction() : transaction;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TestTransaction) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TestTransaction testTransaction = (TestTransaction) transaction;
            testTransaction.active = true;
            current.set(testTransaction);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            ((TestTransaction) status.getTransaction()).active = false;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            ((TestTransaction) status.getTransaction()).active = false;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            current.remove();
        }

        private static final class TestTransaction {
            private boolean active;
        }
    }
}
