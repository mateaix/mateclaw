package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.repository.TeamRunMapper;
import vip.mate.team.repository.TeamTaskMapper;
import vip.mate.workspace.core.service.WorkspaceService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamWorkerConversationGovernanceServiceTest {

    @Mock private TeamTaskMapper taskMapper;
    @Mock private TeamRunMapper runMapper;
    @Mock private ConversationMapper conversationMapper;
    @Mock private AuthService authService;
    @Mock private WorkspaceService workspaceService;

    @Test
    void returnsVerifiedCanonicalContextOnlyWhenRequestedLinkageMatches() {
        TeamTaskEntity task = task(501L, 77L, "worker-conversation");
        TeamRunEntity run = run(77L, 20L, "lead-conversation");
        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "worker-conversation", 30L, 41L, "lead-conversation", "team_worker"));
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(runMapper.selectById(77L)).thenReturn(run);
        TeamWorkerConversationGovernanceService service = service();

        assertThat(service.resolve("worker-conversation", 77L, 501L))
                .isPresent().get()
                .extracting(TeamWorkerConversationContext::verified,
                        TeamWorkerConversationContext::conversationKind,
                        TeamWorkerConversationContext::runId,
                        TeamWorkerConversationContext::taskId)
                .containsExactly(true, "team_worker", 77L, 501L);

        assertThat(service.resolve("worker-conversation", 88L, 501L)).isEmpty();
        assertThat(service.resolve("worker-conversation", 77L, 999L)).isEmpty();
    }

    @Test
    void ordinaryConversationCannotBecomeWorkerFromForgedRouteIds() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "ordinary-conversation", 30L, 41L, null, "primary"));

        assertThat(service().resolve("ordinary-conversation", 77L, 501L)).isEmpty();
    }

    @Test
    void rejectsCrossWorkspaceAgentAndParentConversationMismatches() {
        TeamTaskEntity task = task(501L, 77L, "worker-conversation");
        TeamRunEntity run = run(77L, 20L, "lead-conversation");
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(runMapper.selectById(77L)).thenReturn(run);

        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "worker-conversation", 99L, 41L, "lead-conversation", "team_worker"));
        assertThat(service().resolve("worker-conversation", null, null)).isEmpty();

        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "worker-conversation", 30L, 999L, "lead-conversation", "team_worker"));
        assertThat(service().resolve("worker-conversation", null, null)).isEmpty();

        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "worker-conversation", 30L, 41L, "other-lead", "team_worker"));
        assertThat(service().resolve("worker-conversation", null, null)).isEmpty();
    }

    @Test
    void ordinaryDelegatedChildIsNotATeamWorker() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "delegate-child", 30L, 41L, "lead-conversation", "primary"));

        assertThat(service().resolve("delegate-child", null, null)).isEmpty();
    }

    @Test
    void recognizesPersistedLegacyWorkerLinkageWithoutTrustingItsPrefix() {
        TeamTaskEntity task = task(501L, 77L, "team-task-legacy");
        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "team-task-legacy", 30L, 41L, "lead", null));
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(runMapper.selectById(77L)).thenReturn(run(77L, 20L, "lead"));

        assertThat(service().resolve("team-task-legacy", null, null)).isPresent();
    }

    @Test
    void recognizesLegacyWorkerWithoutPersistedParentFromCanonicalTaskRunLinkage() {
        TeamTaskEntity task = task(501L, 77L, "team-task-legacy-no-parent");
        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "team-task-legacy-no-parent", 30L, 41L, null, null));
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(runMapper.selectById(77L)).thenReturn(run(77L, 20L, "lead"));

        assertThat(service().resolve("team-task-legacy-no-parent", 77L, 501L)).isPresent();
    }

    @Test
    void allowsWorkspaceAdminToReadVerifiedWorkerTranscript() {
        TeamTaskEntity task = task(501L, 77L, "worker-conversation");
        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "worker-conversation", 30L, 41L, "lead-conversation", "team_worker"));
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(runMapper.selectById(77L)).thenReturn(run(77L, 20L, "lead-conversation"));
        when(authService.findByUsername("workspace-admin")).thenReturn(user(900L, "user"));
        when(workspaceService.hasPermissionCached(30L, 900L, "viewer")).thenReturn(true);

        assertThat(service().canReadTranscript("worker-conversation", 77L, 501L,
                "workspace-admin")).isTrue();
    }

    @Test
    void rejectsWorkerTranscriptReadWhenRouteLinkageOrWorkspaceMembershipDoesNotMatch() {
        TeamTaskEntity task = task(501L, 77L, "worker-conversation");
        when(conversationMapper.selectOne(any())).thenReturn(conversation(
                "worker-conversation", 30L, 41L, "lead-conversation", "team_worker"));
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(runMapper.selectById(77L)).thenReturn(run(77L, 20L, "lead-conversation"));
        when(authService.findByUsername("outsider")).thenReturn(user(901L, "user"));
        when(workspaceService.hasPermissionCached(30L, 901L, "viewer")).thenReturn(false);

        assertThat(service().canReadTranscript("worker-conversation", 88L, 501L,
                "outsider")).isFalse();
        assertThat(service().canReadTranscript("worker-conversation", 77L, 501L,
                "outsider")).isFalse();
    }

    private TeamWorkerConversationGovernanceService service() {
        return new TeamWorkerConversationGovernanceService(taskMapper, runMapper, conversationMapper,
                authService, workspaceService);
    }

    private static TeamTaskEntity task(Long id, Long runId, String conversationId) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(id);
        task.setRunId(runId);
        task.setTeamId(20L);
        task.setAssigneeAgentId(41L);
        task.setConversationId(conversationId);
        return task;
    }

    private static TeamRunEntity run(Long id, Long teamId, String leadConversationId) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(id);
        run.setTeamId(teamId);
        run.setWorkspaceId(30L);
        run.setLeadConversationId(leadConversationId);
        return run;
    }

    private static ConversationEntity conversation(String id, Long workspaceId, Long agentId,
                                                     String parentId, String kind) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setConversationId(id);
        conversation.setWorkspaceId(workspaceId);
        conversation.setAgentId(agentId);
        conversation.setParentConversationId(parentId);
        conversation.setConversationKind(kind);
        return conversation;
    }

    private static UserEntity user(long id, String role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
