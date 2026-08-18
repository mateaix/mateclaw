package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.repository.TeamTaskMapper;

/** Executes each run projection in an independent transaction. */
@Service
@RequiredArgsConstructor
public class TeamRunProjectionExecutor {

    private final TeamRunProjector runProjector;
    private final TeamTaskMapper taskMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Long runId) {
        runProjector.project(runId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeTask(Long taskId) {
        TeamTaskEntity task = taskMapper.selectById(taskId);
        if (task != null && task.getRunId() != null) {
            runProjector.project(task.getRunId());
        }
    }
}
