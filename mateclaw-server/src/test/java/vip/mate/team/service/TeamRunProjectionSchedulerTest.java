package vip.mate.team.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.repository.TeamTaskMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamRunProjectionSchedulerTest {

    private static final Long RUN_ID = 20L;
    private static final Long TASK_ID = 5L;

    private TeamRunProjectionExecutor executor;
    private TeamRunProjectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        clearTransactionState();
        executor = mock(TeamRunProjectionExecutor.class);
        scheduler = new TeamRunProjectionScheduler(executor);
    }

    @AfterEach
    void tearDown() {
        clearTransactionState();
    }

    @Test
    void activeTransactionProjectsOnlyAfterCommit() {
        beginTransactionSynchronization();

        scheduler.scheduleRun(RUN_ID);

        verify(executor, never()).execute(RUN_ID);
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(executor).execute(RUN_ID);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void rolledBackTransactionDoesNotProject() {
        beginTransactionSynchronization();

        scheduler.scheduleRun(RUN_ID);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(executor, never()).execute(RUN_ID);
    }

    @Test
    void noTransactionProjectsImmediately() {
        scheduler.scheduleRun(RUN_ID);

        verify(executor).execute(RUN_ID);
    }

    @Test
    void projectionFailureIsSwallowed() {
        doThrow(new IllegalStateException("projection unavailable")).when(executor).execute(RUN_ID);

        assertDoesNotThrow(() -> scheduler.scheduleRun(RUN_ID));
    }

    @Test
    void taskLookupIsDeferredUntilAfterCommit() {
        TeamTaskMapper taskMapper = mock(TeamTaskMapper.class);
        TeamRunProjector projector = mock(TeamRunProjector.class);
        TeamTaskEntity task = new TeamTaskEntity();
        task.setRunId(RUN_ID);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        TeamRunProjectionScheduler taskScheduler = new TeamRunProjectionScheduler(
                new TeamRunProjectionExecutor(projector, taskMapper));
        beginTransactionSynchronization();

        taskScheduler.scheduleTask(TASK_ID);

        verify(taskMapper, never()).selectById(TASK_ID);
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(taskMapper).selectById(TASK_ID);
        verify(projector).project(RUN_ID);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
