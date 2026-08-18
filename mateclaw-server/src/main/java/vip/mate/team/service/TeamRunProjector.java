package vip.mate.team.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.repository.TeamRunMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.util.List;

/** Projects task state into its owning run without exposing failures to task settlement. */
@Slf4j
@Service
public class TeamRunProjector {

    private final TeamRunMapper runMapper;
    private final TeamTaskMapper taskMapper;
    private final TeamRunStateMachine stateMachine;

    public TeamRunProjector(TeamRunMapper runMapper, TeamTaskMapper taskMapper) {
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
        this.stateMachine = new TeamRunStateMachine();
    }

    public TeamRunView project(Long runId) {
        if (runId == null) {
            return null;
        }
        try {
            return projectOnce(runId, 1);
        } catch (RuntimeException error) {
            log.warn("Failed to project team run {}", runId, error);
            return null;
        }
    }

    private TeamRunView projectOnce(Long runId, int retryRemaining) {
        TeamRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return null;
        }
        List<TeamTaskEntity> tasks = taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getRunId, runId)
                .orderByAsc(TeamTaskEntity::getTaskNumber));
        TeamRunStateMachine.Projection projection = stateMachine.project(run, tasks);
        if (TeamRunStatus.isTerminal(run.getStatus()) || TeamRunStatus.PLANNING.equals(run.getStatus())) {
            return view(run, projection, tasks);
        }

        JSONObject metadata = metadata(run.getMetadata());
        boolean metadataChanged;
        if (projection.projectedOutcome() == null) {
            metadataChanged = metadata.containsKey("projectedOutcome");
            metadata.remove("projectedOutcome");
        } else {
            metadataChanged = !projection.projectedOutcome().equals(metadata.getStr("projectedOutcome"));
            metadata.set("projectedOutcome", projection.projectedOutcome());
        }
        boolean statusChanged = !projection.status().equals(run.getStatus());
        if (!statusChanged && !metadataChanged) {
            return view(run, projection, tasks);
        }

        String metadataJson = metadata.toString();
        LambdaUpdateWrapper<TeamRunEntity> update = Wrappers.<TeamRunEntity>lambdaUpdate()
                .eq(TeamRunEntity::getId, run.getId())
                .eq(TeamRunEntity::getStatus, run.getStatus());
        if (run.getMetadata() == null) {
            update.isNull(TeamRunEntity::getMetadata);
        } else {
            update.eq(TeamRunEntity::getMetadata, run.getMetadata());
        }
        update
                .set(TeamRunEntity::getStatus, projection.status())
                .set(TeamRunEntity::getMetadata, metadataJson);
        int changed = runMapper.update(null, update);
        if (changed == 1) {
            run.setStatus(projection.status());
            run.setMetadata(metadataJson);
            return view(run, projection, tasks);
        }
        return retryRemaining > 0 ? projectOnce(runId, retryRemaining - 1) : null;
    }

    private TeamRunView view(TeamRunEntity run, TeamRunStateMachine.Projection projection,
                             List<TeamTaskEntity> tasks) {
        return TeamRunViewFactory.create(run, run.getStatus(), projection.progress(), tasks, true);
    }

    private JSONObject metadata(String value) {
        if (value == null || value.isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(value);
        } catch (RuntimeException invalidJson) {
            return new JSONObject();
        }
    }
}
