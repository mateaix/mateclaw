package vip.mate.team.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.agent.model.AgentEntity;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.config.WorkspaceAccessInterceptor;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamRunCreateCommand;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.event.TeamRunDispatchCommittedIntent;
import vip.mate.team.service.TeamAnnounceService;
import vip.mate.team.service.TeamDispatchService;
import vip.mate.team.service.TeamEventChannel;
import vip.mate.team.service.TeamManualTaskService;
import vip.mate.team.service.TeamRunService;
import vip.mate.team.service.TeamService;
import vip.mate.team.service.TeamTaskService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that service-layer validation verdicts (IllegalArgumentException /
 * IllegalStateException) surface through the R envelope as readable messages
 * instead of escaping to the global catch-all 500 handler.
 */
@ExtendWith(MockitoExtension.class)
class TeamControllerTest {

    private static final Long TEAM_ID = 1L;
    private static final Long TASK_ID = 100L;
    private static final Long RUN_ID = 200L;

    @Mock private TeamService teamService;
    @Mock private TeamTaskService taskService;
    @Mock private TeamRunService runService;
    @Mock private ApplicationEventPublisher events;
    @Mock private TeamDispatchService dispatchService;
    @Mock private TeamAnnounceService announceService;
    @Mock private TeamEventChannel eventChannel;
    @Mock private AgentMapper agentMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;

    private TeamController controller;
    private TeamManualTaskService manualTaskService;

    @BeforeEach
    void setUp() {
        manualTaskService = new TeamManualTaskService(runService, taskService, events);
        controller = new TeamController(teamService, taskService, manualTaskService, dispatchService,
                announceService, eventChannel, agentMapper);
        AgentTeamEntity team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setWorkspaceId(1L);
        team.setLeadAgentId(10L);
        lenient().when(teamService.getTeam(TEAM_ID, 1L)).thenReturn(team);
    }

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private TeamTaskEntity task(Long teamId, String status) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(TASK_ID);
        task.setTeamId(teamId);
        task.setTaskNumber(7);
        task.setStatus(status);
        task.setSubject("subject");
        return task;
    }

    private TeamRunEntity run(Long teamId, Long workspaceId, String status) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setTeamId(teamId);
        run.setWorkspaceId(workspaceId);
        run.setStatus(status);
        return run;
    }

    // ==================== team / membership ====================

    @Test
    void createTeamSurfacesMembershipConflictAsReadableFailure() {
        when(teamService.createTeam(anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException(
                        "agent 5 already belongs to team 2; an agent can join only one team"));
        TeamController.CreateTeamRequest req = new TeamController.CreateTeamRequest();
        req.setName("t");
        req.setLeadAgentId(5L);

        R<TeamController.TeamVO> r = controller.create(req, null);

        assertEquals(500, r.getCode());
        assertEquals("agent 5 already belongs to team 2; an agent can join only one team", r.getMsg());
    }

    @Test
    void updateTeamSurfacesUnknownTeamAsReadableFailure() {
        when(teamService.updateTeam(eq(TEAM_ID), eq(1L), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("team not found: " + TEAM_ID));

        R<TeamController.TeamVO> r = controller.update(TEAM_ID, new TeamController.UpdateTeamRequest());

        assertEquals(500, r.getCode());
        assertEquals("team not found: 1", r.getMsg());
    }

    @Test
    void addMemberSurfacesValidationAsReadableFailure() {
        doThrow(new IllegalArgumentException("agent is already the team lead"))
                .when(teamService).addMember(TEAM_ID, 1L, 5L, "member");
        TeamController.MemberRequest req = new TeamController.MemberRequest();
        req.setAgentId(5L);
        req.setRole("member");

        R<Void> r = controller.addMember(TEAM_ID, req);

        assertEquals(500, r.getCode());
        assertEquals("agent is already the team lead", r.getMsg());
    }

    @Test
    void removeMemberSurfacesLeadProtectionAsReadableFailure() {
        doThrow(new IllegalArgumentException("cannot remove the team lead; delete the team instead"))
                .when(teamService).removeMember(TEAM_ID, 1L, 5L);

        R<Void> r = controller.removeMember(TEAM_ID, 5L);

        assertEquals(500, r.getCode());
        assertEquals("cannot remove the team lead; delete the team instead", r.getMsg());
    }

    // ==================== task board ====================

    @Test
    void listTasksBatchLoadsDistinctAssigneesAndOwnersOnce() {
        TeamTaskEntity first = task(TEAM_ID, TeamTaskStatus.PENDING);
        first.setId(101L);
        first.setAssigneeAgentId(11L);
        first.setOwnerAgentId(12L);
        TeamTaskEntity second = task(TEAM_ID, TeamTaskStatus.IN_PROGRESS);
        second.setId(102L);
        second.setAssigneeAgentId(11L);
        second.setOwnerAgentId(13L);
        TeamTaskEntity third = task(TEAM_ID, TeamTaskStatus.COMPLETED);
        third.setId(103L);
        third.setAssigneeAgentId(13L);
        third.setOwnerAgentId(null);
        when(taskService.listTasks(TEAM_ID, null, null, null, null)).thenReturn(List.of(first, second, third));
        AgentEntity assignee = new AgentEntity();
        assignee.setId(11L);
        assignee.setName("Assignee");
        AgentEntity owner = new AgentEntity();
        owner.setId(12L);
        owner.setName("Owner");
        AgentEntity shared = new AgentEntity();
        shared.setId(13L);
        shared.setName("Shared");
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(assignee, owner, shared));

        R<List<TeamController.TaskVO>> response = controller.listTasks(TEAM_ID, null, null, null, null);

        assertEquals(List.of("Assignee", "Assignee", "Shared"),
                response.getData().stream().map(TeamController.TaskVO::assigneeName).toList());
        assertEquals(java.util.Arrays.asList("Owner", "Shared", null),
                response.getData().stream().map(TeamController.TaskVO::ownerName).toList());
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(agentMapper, times(1)).selectBatchIds(ids.capture());
        assertEquals(Set.of(11L, 12L, 13L), Set.copyOf(ids.getValue()));
        verify(agentMapper, never()).selectById(anyLong());
    }

    @Test
    void createTaskSurfacesUnknownAssigneeAsReadableFailure() {
        TeamRunEntity planning = run(TEAM_ID, 1L, TeamRunStatus.PLANNING);
        when(runService.requireRun(RUN_ID, 1L)).thenReturn(planning);
        when(taskService.createTask(any(TeamTaskCreateCommand.class)))
                .thenThrow(new IllegalArgumentException("assignee 9 is not a member of this team"));
        TeamController.CreateTaskRequest req = new TeamController.CreateTaskRequest();
        req.setRunId(RUN_ID);
        req.setSubject("do the thing");
        req.setAssigneeAgentId(9L);

        R<TeamController.TaskVO> r = controller.createTask(TEAM_ID, req, null);

        assertEquals(500, r.getCode());
        assertEquals("assignee 9 is not a member of this team", r.getMsg());
        verify(eventChannel, never()).publishTaskEvent(any(), anyString(), any());
        verify(dispatchService, never()).requestDispatch(anyLong());
    }

    @Test
    void createTaskWithoutRunCreatesSealsAndPublishesOneDispatchIntent() {
        TeamRunEntity planning = run(TEAM_ID, 1L, TeamRunStatus.PLANNING);
        TeamRunEntity running = run(TEAM_ID, 1L, TeamRunStatus.RUNNING);
        when(runService.startRun(any())).thenReturn(planning);
        TeamTaskEntity created = task(TEAM_ID, TeamTaskStatus.PENDING);
        created.setRunId(RUN_ID);
        when(taskService.createTask(any())).thenReturn(created);
        when(runService.sealRunWithResult(RUN_ID, 1L))
                .thenReturn(new TeamRunService.SealResult(running, true));
        TeamController.CreateTaskRequest req = new TeamController.CreateTaskRequest();
        req.setSubject("dashboard task");
        req.setDescription("details");
        req.setAssigneeAgentId(9L);

        R<TeamController.TaskVO> result = controller.createTask(TEAM_ID, req, null);

        assertEquals(RUN_ID, result.getData().runId());
        ArgumentCaptor<TeamRunCreateCommand> runCommand =
                ArgumentCaptor.forClass(TeamRunCreateCommand.class);
        verify(runService).startRun(runCommand.capture());
        assertEquals("dashboard-team-1", runCommand.getValue().getLeadConversationId());
        assertEquals("dashboard task", runCommand.getValue().getTitle());
        assertEquals("details", runCommand.getValue().getObjective());
        assertEquals(null, runCommand.getValue().getOriginMessageId());
        ArgumentCaptor<TeamTaskCreateCommand> taskCommand =
                ArgumentCaptor.forClass(TeamTaskCreateCommand.class);
        verify(taskService).createTask(taskCommand.capture());
        assertEquals(RUN_ID, taskCommand.getValue().getRunId());
        verify(runService).sealRunWithResult(RUN_ID, 1L);
        verify(events, times(1)).publishEvent(new TeamRunDispatchCommittedIntent(TEAM_ID));
    }

    @Test
    void createTaskWithoutRunDoesNotPublishWhenSealAlreadyTransitioned() {
        TeamRunEntity planning = run(TEAM_ID, 1L, TeamRunStatus.PLANNING);
        TeamRunEntity running = run(TEAM_ID, 1L, TeamRunStatus.RUNNING);
        when(runService.startRun(any())).thenReturn(planning);
        TeamTaskEntity created = task(TEAM_ID, TeamTaskStatus.PENDING);
        created.setRunId(RUN_ID);
        when(taskService.createTask(any())).thenReturn(created);
        when(runService.sealRunWithResult(RUN_ID, 1L))
                .thenReturn(new TeamRunService.SealResult(running, false));
        TeamController.CreateTaskRequest req = new TeamController.CreateTaskRequest();
        req.setSubject("dashboard task");
        req.setAssigneeAgentId(9L);

        controller.createTask(TEAM_ID, req, null);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void createTaskWithExplicitRunDoesNotSealOrDispatch() {
        when(runService.requireRun(RUN_ID, 1L))
                .thenReturn(run(TEAM_ID, 1L, TeamRunStatus.PLANNING));
        TeamTaskEntity created = task(TEAM_ID, TeamTaskStatus.PENDING);
        created.setRunId(RUN_ID);
        when(taskService.createTask(any())).thenReturn(created);
        TeamController.CreateTaskRequest req = new TeamController.CreateTaskRequest();
        req.setRunId(RUN_ID);
        req.setSubject("another task");
        req.setAssigneeAgentId(9L);

        R<TeamController.TaskVO> result = controller.createTask(TEAM_ID, req, null);

        assertEquals(RUN_ID, result.getData().runId());
        verify(runService, never()).startRun(any());
        verify(runService, never()).sealRunWithResult(anyLong(), anyLong());
        verify(events, never()).publishEvent(any());
        verify(dispatchService, never()).requestDispatch(anyLong());
    }

    @Test
    void createTaskRejectsExplicitRunFromAnotherTeam() {
        when(runService.requireRun(RUN_ID, 1L))
                .thenReturn(run(99L, 1L, TeamRunStatus.PLANNING));
        TeamController.CreateTaskRequest req = new TeamController.CreateTaskRequest();
        req.setRunId(RUN_ID);
        req.setSubject("another task");
        req.setAssigneeAgentId(9L);

        R<TeamController.TaskVO> result = controller.createTask(TEAM_ID, req, null);

        assertEquals(500, result.getCode());
        assertEquals("team task and run must belong to the same team", result.getMsg());
        verify(taskService, never()).createTask(any());
    }

    @Test
    void approveRejectsTaskFromAnotherTeamsBoard() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(2L, "in_review"));

        R<TeamController.TaskVO> r = controller.approve(TEAM_ID, TASK_ID, null);

        assertEquals(500, r.getCode());
        assertEquals("task not found on this team's board", r.getMsg());
        verify(taskService, never()).approveTask(anyLong());
    }

    @Test
    void approveSurfacesWrongStatusAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "pending"));
        when(taskService.approveTask(TASK_ID))
                .thenThrow(new IllegalStateException("task #7 is not awaiting review"));

        R<TeamController.TaskVO> r = controller.approve(TEAM_ID, TASK_ID, null);

        assertEquals(500, r.getCode());
        assertEquals("task #7 is not awaiting review", r.getMsg());
    }

    @Test
    void approveHappyPathStillReturnsTask() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "in_review"));
        when(taskService.approveTask(TASK_ID)).thenReturn(List.of(200L));

        R<TeamController.TaskVO> r = controller.approve(TEAM_ID, TASK_ID, null);

        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
        verify(dispatchService).requestDispatch(TEAM_ID);
    }

    @Test
    void rejectSurfacesWrongStatusAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "pending"));
        doThrow(new IllegalStateException("task #7 is not awaiting review"))
                .when(taskService).rejectTask(TASK_ID, null);

        R<TeamController.TaskVO> r = controller.reject(TEAM_ID, TASK_ID, null, null);

        assertEquals(500, r.getCode());
        assertEquals("task #7 is not awaiting review", r.getMsg());
        verify(announceService, never()).announceTaskSettled(any());
    }

    @Test
    void retrySurfacesTaskFromAnotherTeamAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(2L, "failed"));

        R<TeamController.TaskVO> r = controller.retry(TEAM_ID, TASK_ID, null);

        assertEquals(500, r.getCode());
        assertEquals("task not found on this team's board", r.getMsg());
        verify(taskService, never()).retryTask(anyLong());
    }

    @Test
    void retryOnNonRetryableStatusKeepsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "done"));
        when(taskService.retryTask(TASK_ID)).thenReturn(false);

        R<TeamController.TaskVO> r = controller.retry(TEAM_ID, TASK_ID, null);

        assertEquals(500, r.getCode());
        assertEquals("only failed or stale tasks can be retried", r.getMsg());
    }

    @Test
    void cancelSurfacesTerminalTaskAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "done"));
        when(taskService.cancelTask(TASK_ID, null))
                .thenThrow(new IllegalStateException("task #7 is already terminal"));

        R<TeamController.TaskVO> r = controller.cancel(TEAM_ID, TASK_ID, null, null);

        assertEquals(500, r.getCode());
        assertEquals("task #7 is already terminal", r.getMsg());
        verify(dispatchService, never()).interruptRun(any());
    }

    @Test
    void commentSurfacesUnknownTaskAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(null);
        TeamController.CommentRequest req = new TeamController.CommentRequest();
        req.setContent("hi");

        R<Void> r = controller.comment(TEAM_ID, TASK_ID, req, null);

        assertEquals(500, r.getCode());
        assertEquals("task not found on this team's board", r.getMsg());
        verify(taskService, never()).addComment(anyLong(), anyString(), any(), anyString(), any());
    }

    @Test
    void getTaskSurfacesUnknownTaskAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(null);

        R<TeamController.TaskDetailVO> r = controller.getTask(TEAM_ID, TASK_ID);

        assertEquals(500, r.getCode());
        assertEquals("task not found on this team's board", r.getMsg());
    }

    @Test
    void crossWorkspaceTeamIsRejectedBeforeTaskBoardRead() {
        when(teamService.getTeam(TEAM_ID, 1L)).thenReturn(null);

        R<List<TeamController.TaskVO>> r = controller.listTasks(TEAM_ID, null, null, null, null);

        assertEquals(500, r.getCode());
        assertEquals("team not found: 1", r.getMsg());
        verify(taskService, never()).listTasks(anyLong(), any(), any(), any(), any());
    }

    @Test
    void everyEndpointDeclaresWorkspaceRole() {
        assertRole("list", "viewer");
        assertRole("get", "viewer", Long.class);
        assertRole("create", "admin", TeamController.CreateTeamRequest.class, java.security.Principal.class);
        assertRole("update", "admin", Long.class, TeamController.UpdateTeamRequest.class);
        assertRole("delete", "admin", Long.class);
        assertRole("addMember", "admin", Long.class, TeamController.MemberRequest.class);
        assertRole("removeMember", "admin", Long.class, Long.class);
        assertRole("listTasks", "viewer", Long.class, List.class, Integer.class, Integer.class, Long.class);
        assertRole("getTask", "viewer", Long.class, Long.class);
        assertRole("createTask", "admin", Long.class, TeamController.CreateTaskRequest.class, java.security.Principal.class);
        assertRole("approve", "admin", Long.class, Long.class, java.security.Principal.class);
        assertRole("reject", "admin", Long.class, Long.class, TeamController.ReasonRequest.class, java.security.Principal.class);
        assertRole("retry", "admin", Long.class, Long.class, java.security.Principal.class);
        assertRole("cancel", "admin", Long.class, Long.class, TeamController.ReasonRequest.class, java.security.Principal.class);
        assertRole("taskEvents", "viewer", Long.class, Long.class);
        assertRole("events", "viewer", Long.class, Long.class);
        assertRole("comment", "admin", Long.class, Long.class, TeamController.CommentRequest.class, java.security.Principal.class);
        assertRole("taskStats", "viewer", Long.class, Long.class);
    }

    @Test
    void viewerCannotDeleteTeam() throws Exception {
        UserEntity viewer = new UserEntity();
        viewer.setId(42L);
        viewer.setUsername("viewer");
        viewer.setRole("user");
        when(authService.findByUsername("viewer")).thenReturn(viewer);
        when(workspaceService.hasPermissionCached(1L, 42L, "admin")).thenReturn(false);
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "viewer", "", List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(authentication);
        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .addInterceptors(new WorkspaceAccessInterceptor(workspaceService, authService, agentMapper))
                .build();

        mockMvc.perform(delete("/api/v1/teams/{id}", TEAM_ID)
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isForbidden());

        verify(teamService, never()).deleteTeam(anyLong(), anyLong());
    }

    private void assertRole(String method, String role, Class<?>... parameterTypes) {
        try {
            RequireWorkspaceRole annotation = TeamController.class
                    .getDeclaredMethod(method, parameterTypes)
                    .getAnnotation(RequireWorkspaceRole.class);
            assertNotNull(annotation, method + " must require a workspace role");
            assertEquals(role, annotation.value(), method + " role");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
