package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;

import java.util.List;
import java.util.Optional;

/**
 * Renders the team context block ("TEAM.md") appended to an agent's system
 * prompt. The lead receives the full orchestration playbook; members receive
 * execution-focused instructions; agents outside any team receive a one-line
 * negative notice so the model never probes the team_tasks tool speculatively.
 *
 * The block is baked into the cached agent instance; {@code TeamChangedEvent}
 * evicts affected agents so composition changes surface on the next turn.
 *
 * @author MateClaw Team
 */
@Component
@RequiredArgsConstructor
public class TeamContextBuilder {

    static final String NO_TEAM_NOTICE = """

            ## Team
            You are not part of any agent team. Do NOT call the team_tasks tool.
            """;

    private final TeamService teamService;
    private final AgentMapper agentMapper;

    /** Build the team context block for the given agent; never returns null. */
    public String buildTeamContext(Long agentId) {
        Optional<AgentTeamEntity> teamOpt = teamService.getTeamForAgent(agentId);
        if (teamOpt.isEmpty()) {
            return NO_TEAM_NOTICE;
        }
        AgentTeamEntity team = teamOpt.get();
        List<AgentTeamMemberEntity> members = teamService.listMembers(team.getId());
        boolean isLead = teamService.isLead(team, agentId);

        StringBuilder sb = new StringBuilder(2048);
        sb.append("\n\n## Team: ").append(team.getName()).append('\n');
        if (team.getDescription() != null && !team.getDescription().isBlank()) {
            sb.append(team.getDescription()).append('\n');
        }
        sb.append("Your role: ").append(isLead ? "LEAD — you orchestrate this team." : "MEMBER.")
                .append('\n');

        sb.append("""

                ### Members
                This is the complete and authoritative list of your team. Do NOT use tools to verify it.
                """);
        for (AgentTeamMemberEntity member : members) {
            AgentEntity agent = agentMapper.selectById(member.getAgentId());
            String name = agent != null && agent.getName() != null ? agent.getName()
                    : String.valueOf(member.getAgentId());
            sb.append("- **").append(name).append("** (agentId: ").append(member.getAgentId())
                    .append(", ").append(member.getRole()).append(')');
            if (member.getAgentId().equals(agentId)) {
                sb.append(" — you");
            } else if (agent != null && agent.getDescription() != null
                    && !agent.getDescription().isBlank()) {
                sb.append(": ").append(agent.getDescription().strip());
            }
            sb.append('\n');
        }

        sb.append(isLead ? leadPlaybook() : memberPlaybook());
        return sb.toString();
    }

    private static String leadPlaybook() {
        return """

                ### Delegation workflow (mandatory)
                - Delegate work by creating tasks on the team board: `team_tasks(action="create", subject=..., description=..., assigneeAgentId=...)`. Every delegation MUST go through the board — never pretend a teammate did something without a task backing it.
                - Check the board FIRST: call `team_tasks(action="list")` before creating tasks so you never create duplicates.
                - Create ALL tasks for the request up front in one batch. Order dependent work with `blockedBy` (ids of prerequisite tasks). Then announce the assignments to the user and STOP — do not keep reasoning while members work.
                - Delegation is NOT completion. After creating tasks, never say the work is "done" or "finished"; say it has been assigned and results will follow.
                - Never assign a task to yourself — the lead orchestrates, members execute.
                - Task sizing: one task = one specific action producing one output. Split a task if it needs two different skills; do not over-split mechanical steps.
                - If a prerequisite task is already completed, pass its result inside the new task's description instead of blocking on it.

                ### When results arrive
                Member results are delivered to you as system messages in this conversation. Review them, cross-check against the original request, then synthesize ONE coherent reply for the user. Do not forward raw member output unedited.

                ### Handling blockers
                When a member reports a blocker the task auto-fails and you are notified with the reason. Resolve the missing input (provide context, adjust the description), then re-dispatch it with `team_tasks(action="retry", taskId=...)`, or cancel it with `action="cancel"` if it is no longer needed.
                """;
    }

    private static String memberPlaybook() {
        return """

                ### Working on assigned tasks
                - When a task is dispatched to you, focus entirely on executing it. Your final reply becomes the task result and is reported back to the lead automatically.
                - Report meaningful milestones with `team_tasks(action="progress", taskId=..., percent=..., step=...)`. The taskId is included in the dispatch message.
                - Leave findings other teammates may need as comments: `team_tasks(action="comment", taskId=..., text=...)`.
                - If you cannot proceed (missing input, unclear scope, failed dependency), report it with `team_tasks(action="comment", taskId=..., type="blocker", text="what you need")`. This fails the task and notifies the lead — do NOT silently improvise around a blocker.
                - You may inspect the board with `action="list"` or `action="get"` for context, but do not create or cancel tasks.
                """;
    }
}
