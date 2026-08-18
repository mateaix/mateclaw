package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vip.mate.team.event.TeamRunCancelCommittedIntent;
import vip.mate.team.event.TeamRunDispatchCommittedIntent;
import vip.mate.team.model.TeamTaskEntity;

/** Executes run side effects only after their state transaction commits. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamRunCommittedIntentListener {

    private final TeamDispatchService dispatchService;
    private final TeamRunEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDispatchCommitted(TeamRunDispatchCommittedIntent intent) {
        try {
            dispatchService.requestDispatch(intent.teamId());
        } catch (Exception e) {
            log.warn("Team {} committed dispatch failed: {}", intent.teamId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCancelCommitted(TeamRunCancelCommittedIntent intent) {
        for (TeamRunCancelCommittedIntent.WorkerTask worker : intent.workers()) {
            try {
                dispatchService.interruptRun(snapshot(worker));
            } catch (Exception e) {
                log.warn("Team task {} committed interrupt failed: {}",
                        worker.taskId(), e.getMessage());
            }
        }
        try {
            eventPublisher.publishCancelled(intent.run());
        } catch (Exception e) {
            log.warn("Team run {} committed cancellation event failed: {}",
                    intent.run().id(), e.getMessage());
        }
    }

    private TeamTaskEntity snapshot(TeamRunCancelCommittedIntent.WorkerTask worker) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(worker.taskId());
        task.setTaskNumber(worker.taskNumber());
        task.setConversationId(worker.conversationId());
        return task;
    }
}
