package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Schedules run projection outside the task mutation transaction. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamRunProjectionScheduler {

    private final TeamRunProjectionExecutor projectionExecutor;

    public void scheduleRun(Long runId) {
        if (runId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    projectRun(runId);
                }
            });
            return;
        }
        projectRun(runId);
    }

    public void scheduleTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    projectTask(taskId);
                }
            });
            return;
        }
        projectTask(taskId);
    }

    private void projectRun(Long runId) {
        try {
            projectionExecutor.execute(runId);
        } catch (RuntimeException error) {
            log.warn("Team run {} projection failed: {}", runId, error.getMessage());
        }
    }

    private void projectTask(Long taskId) {
        try {
            projectionExecutor.executeTask(taskId);
        } catch (RuntimeException error) {
            log.warn("Team run projection for task {} failed: {}", taskId, error.getMessage());
        }
    }
}
