package vip.mate.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.repository.TeamRunMapper;
import vip.mate.team.repository.TeamTaskMapper;
import vip.mate.workspace.core.service.WorkspaceService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.ConversationService;

import java.util.Optional;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TeamWorkerConversationGovernanceService {

    private final TeamTaskMapper taskMapper;
    private final TeamRunMapper runMapper;
    private final ConversationMapper conversationMapper;
    private final AuthService authService;
    private final WorkspaceService workspaceService;

    public Optional<TeamWorkerConversationContext> resolve(
            String conversationId, Long requestedRunId, Long requestedTaskId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        ConversationEntity conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId)
                        .last("LIMIT 1"));
        if (!ConversationService.isTeamWorkerConversation(conversation)) {
            return Optional.empty();
        }
        TeamTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<TeamTaskEntity>()
                .eq(TeamTaskEntity::getConversationId, conversationId)
                .last("LIMIT 1"));
        if (task == null || task.getRunId() == null
                || requestedRunId != null && !requestedRunId.equals(task.getRunId())
                || requestedTaskId != null && !requestedTaskId.equals(task.getId())) {
            return Optional.empty();
        }
        TeamRunEntity run = runMapper.selectById(task.getRunId());
        boolean legacyMissingParent = conversation.getParentConversationId() == null
                && !"team_worker".equals(conversation.getConversationKind())
                && conversationId.startsWith("team-task-");
        if (run == null
                || !Objects.equals(run.getTeamId(), task.getTeamId())
                || !Objects.equals(conversation.getWorkspaceId(), run.getWorkspaceId())
                || !Objects.equals(conversation.getAgentId(), task.getAssigneeAgentId())
                || !legacyMissingParent
                && !Objects.equals(conversation.getParentConversationId(), run.getLeadConversationId())) {
            return Optional.empty();
        }
        return Optional.of(new TeamWorkerConversationContext(
                true, "team_worker", conversationId, run.getId(), task.getId(), run.getTeamId(),
                run.getLeadConversationId(), task.getAssigneeAgentId()));
    }

    /**
     * Team worker transcripts are read-only evidence for a team run. The worker
     * conversation is owned by the executing agent/user, so workspace admins and
     * reviewers are not direct conversation owners. Allow them to read only when
     * the persisted task/run/conversation linkage is canonical and they belong
     * to that run's workspace.
     */
    public boolean canReadTranscript(String conversationId, Long requestedRunId, Long requestedTaskId,
                                     String username) {
        TeamWorkerAccess access = resolveAccess(conversationId, requestedRunId, requestedTaskId);
        if (access == null) {
            return false;
        }
        UserEntity requester = authService.findByUsername(username);
        if (requester == null) {
            return false;
        }
        if ("admin".equalsIgnoreCase(requester.getRole())) {
            return true;
        }
        return workspaceService.hasPermissionCached(access.workspaceId(), requester.getId(), "viewer");
    }

    private TeamWorkerAccess resolveAccess(String conversationId, Long requestedRunId, Long requestedTaskId) {
        if (resolve(conversationId, requestedRunId, requestedTaskId).isEmpty()) {
            return null;
        }
        TeamTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<TeamTaskEntity>()
                .eq(TeamTaskEntity::getConversationId, conversationId)
                .last("LIMIT 1"));
        if (task == null || task.getRunId() == null) {
            return null;
        }
        TeamRunEntity run = runMapper.selectById(task.getRunId());
        if (run == null || run.getWorkspaceId() == null) {
            return null;
        }
        return new TeamWorkerAccess(run.getWorkspaceId());
    }

    private record TeamWorkerAccess(Long workspaceId) {
    }
}
