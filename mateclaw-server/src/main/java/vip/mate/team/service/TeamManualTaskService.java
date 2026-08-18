package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.team.event.TeamRunDispatchCommittedIntent;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamRunCreateCommand;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;

/** Coordinates dashboard task creation with the team run lifecycle. */
@Service
@RequiredArgsConstructor
public class TeamManualTaskService {

    private static final String DASHBOARD_CONVERSATION_PREFIX = "dashboard-team-";

    private final TeamRunService runService;
    private final TeamTaskService taskService;
    private final ApplicationEventPublisher events;

    @Transactional
    public TeamTaskEntity createTask(AgentTeamEntity team, TeamTaskCreateCommand command) {
        boolean autoRun = command.getRunId() == null;
        TeamRunEntity run = autoRun ? startRun(team, command) : requirePlanningRun(team, command.getRunId());
        command.setRunId(run.getId());
        command.setLeadConversationId(run.getLeadConversationId());
        TeamTaskEntity task = taskService.createTask(command);
        if (autoRun) {
            TeamRunService.SealResult sealed = runService.sealRunWithResult(
                    run.getId(), team.getWorkspaceId());
            if (sealed.transitioned()) {
                events.publishEvent(new TeamRunDispatchCommittedIntent(team.getId()));
            }
        }
        return task;
    }

    private TeamRunEntity startRun(AgentTeamEntity team, TeamTaskCreateCommand command) {
        String objective = command.getDescription() == null || command.getDescription().isBlank()
                ? command.getSubject() : command.getDescription();
        return runService.startRun(TeamRunCreateCommand.builder()
                .teamId(team.getId())
                .workspaceId(team.getWorkspaceId())
                .leadAgentId(team.getLeadAgentId())
                .leadConversationId(DASHBOARD_CONVERSATION_PREFIX + team.getId())
                .originMessageId(null)
                .title(command.getSubject())
                .objective(objective)
                .build());
    }

    private TeamRunEntity requirePlanningRun(AgentTeamEntity team, Long runId) {
        TeamRunEntity run = runService.requireRun(runId, team.getWorkspaceId());
        if (!team.getId().equals(run.getTeamId())) {
            throw new IllegalArgumentException("team task and run must belong to the same team");
        }
        if (!TeamRunStatus.PLANNING.equals(run.getStatus())) {
            throw new IllegalStateException("team run must be planning to accept tasks: " + runId);
        }
        return run;
    }
}
