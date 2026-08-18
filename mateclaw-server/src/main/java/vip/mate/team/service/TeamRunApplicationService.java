package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.team.event.TeamRunCancelCommittedIntent;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.ArrayList;
import java.util.List;

/** Coordinates cancellation side effects around the run domain lifecycle. */
@Service
@RequiredArgsConstructor
public class TeamRunApplicationService {

    private final TeamRunService runService;
    private final TeamTaskService taskService;
    private final ApplicationEventPublisher events;

    @Transactional
    public TeamRunView cancelRun(Long runId, Long workspaceId, String reason) {
        TeamRunService.CancelResult cancelled = runService.cancelRunWithResult(
                runId, workspaceId, reason);
        List<TeamRunCancelCommittedIntent.WorkerTask> workers = new ArrayList<>();
        if (cancelled.transitioned()) {
            for (TeamTaskEntity task : taskService.listTasksByRun(runId)) {
                if (TeamTaskStatus.isTerminal(task.getStatus())) {
                    continue;
                }
                if (TeamTaskStatus.IN_PROGRESS.equals(task.getStatus())) {
                    workers.add(new TeamRunCancelCommittedIntent.WorkerTask(
                            task.getId(), task.getTaskNumber(), task.getConversationId()));
                }
                taskService.cancelTask(task.getId(), reason);
            }
        }
        TeamRunView view = runService.buildView(cancelled.run());
        if (cancelled.transitioned()) {
            events.publishEvent(new TeamRunCancelCommittedIntent(view, workers));
        }
        return view;
    }
}
