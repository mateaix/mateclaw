package vip.mate.team.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamRunCreateCommand;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.service.TeamDispatchService;
import vip.mate.team.service.TeamEventChannel;
import vip.mate.team.service.TeamService;
import vip.mate.team.service.TeamRunService;
import vip.mate.team.service.TeamTaskService;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the tool facade's contracts: caller resolution via the conversation,
 * team membership gating, lead-only actions, LLM-friendly error strings, and
 * the pass-through into TeamTaskService.
 */
class TeamTasksToolTest {

    private static final String CONV = "conv-1";
    private static final Long TEAM_ID = 10L;
    private static final Long LEAD_ID = 1L;
    private static final Long MEMBER_ID = 2L;
    private static final Long WORKSPACE_ID = 30L;
    private static final Long RUN_ID = 20L;

    private TeamService teamService;
    private TeamTaskService taskService;
    private TeamRunService runService;
    private TeamDispatchService dispatchService;
    private TeamEventChannel eventChannel;
    private ConversationService conversationService;
    private AgentMapper agentMapper;
    private TeamTasksTool tool;
    private AgentTeamEntity team;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        taskService = mock(TeamTaskService.class);
        runService = mock(TeamRunService.class);
        dispatchService = mock(TeamDispatchService.class);
        eventChannel = mock(TeamEventChannel.class);
        conversationService = mock(ConversationService.class);
        agentMapper = mock(AgentMapper.class);
        tool = new TeamTasksTool(teamService, taskService, runService, dispatchService,
                eventChannel, conversationService, agentMapper);

        team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setName("研发组");
        team.setLeadAgentId(LEAD_ID);
        team.setWorkspaceId(WORKSPACE_ID);

        ToolExecutionContext.set(CONV, "admin");
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    private void callerIs(Long agentId) {
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId(CONV);
        conv.setAgentId(agentId);
        conv.setWorkspaceId(WORKSPACE_ID);
        when(conversationService.findByConversationId(CONV)).thenReturn(conv);
        when(teamService.getTeamForAgent(agentId)).thenReturn(Optional.of(team));
        when(teamService.isLead(team, agentId)).thenReturn(agentId.equals(LEAD_ID));
    }

    private TeamTaskEntity task(Long id, String status) {
        TeamTaskEntity t = new TeamTaskEntity();
        t.setId(id);
        t.setTeamId(TEAM_ID);
        t.setTaskNumber(3);
        t.setSubject("collect data");
        t.setStatus(status);
        t.setAssigneeAgentId(MEMBER_ID);
        return t;
    }

    private String invoke(String action, String taskId) {
        return tool.team_tasks(action, taskId, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private TeamRunEntity run(Long teamId, String conversationId, String status) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setTeamId(teamId);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setLeadConversationId(conversationId);
        run.setStatus(status);
        return run;
    }

    // ==================== context & membership gating ====================

    @Test
    @DisplayName("no conversation context yields a structured error")
    void noContextError() {
        ToolExecutionContext.clear();
        assertTrue(invoke("list", null).startsWith("Error: no conversation context"));
    }

    @Test
    @DisplayName("an agent outside any team is refused")
    void nonTeamAgentRefused() {
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId(CONV);
        conv.setAgentId(99L);
        conv.setWorkspaceId(WORKSPACE_ID);
        when(conversationService.findByConversationId(CONV)).thenReturn(conv);
        when(teamService.getTeamForAgent(99L)).thenReturn(Optional.empty());

        assertTrue(invoke("list", null).contains("not part of any agent team"));
    }

    @Test
    @DisplayName("unknown action lists the valid ones")
    void unknownAction() {
        callerIs(LEAD_ID);
        String output = invoke("destroy", null);
        assertTrue(output.contains("unknown action"));
        assertTrue(output.contains("start_run"));
        assertTrue(output.contains("seal_run"));
    }

    @Test
    @DisplayName("a conversation without workspace context yields a structured error")
    void missingWorkspaceError() {
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId(CONV);
        conv.setAgentId(LEAD_ID);
        when(conversationService.findByConversationId(CONV)).thenReturn(conv);

        assertTrue(invoke("list", null).contains("workspaceId"));
    }

    // ==================== role gating ====================

    @Test
    @DisplayName("member create is refused — only the lead delegates")
    void memberCannotCreate() {
        callerIs(MEMBER_ID);
        String out = tool.team_tasks("create", null, "subj", "desc",
                null, null, null, String.valueOf(MEMBER_ID), null, null, null, null, null, null,
                null, null, null, null, null);
        assertTrue(out.contains("only the team lead can create"));
        verify(taskService, never()).createTask(any());
    }

    @Test
    @DisplayName("member cancel and retry are refused")
    void memberCannotCancelOrRetry() {
        callerIs(MEMBER_ID);
        when(taskService.getTask(5L)).thenReturn(task(5L, TeamTaskStatus.PENDING));
        assertTrue(invoke("cancel", "5").contains("only the team lead"));
        assertTrue(invoke("retry", "5").contains("only the team lead"));
        verify(taskService, never()).cancelTask(any(), any());
        verify(taskService, never()).retryTask(any());
    }

    @Test
    @DisplayName("members cannot start or seal runs")
    void memberCannotStartOrSealRuns() {
        callerIs(MEMBER_ID);

        String start = tool.team_tasks("start_run", null, null, "Run", "Objective",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        String seal = tool.team_tasks("seal_run", null, String.valueOf(RUN_ID), null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertTrue(start.contains("only the team lead"));
        assertTrue(seal.contains("only the team lead"));
        verifyNoInteractions(runService);
    }

    @Test
    @DisplayName("start_run keeps the explicit origin id after later conversation activity")
    void startRunUsesExplicitOriginMessage() {
        callerIs(LEAD_ID);
        when(runService.startRun(any())).thenReturn(run(TEAM_ID, CONV, TeamRunStatus.PLANNING));
        ToolContext originalTurn = ChatOrigin.web(CONV, "admin", WORKSPACE_ID, null)
                .withOriginMessageId(99L)
                .toToolContext();

        String output = tool.team_tasks("start_run", null, null, "Research", "Find evidence",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                originalTurn);

        assertEquals(String.valueOf(RUN_ID), output);
        ArgumentCaptor<TeamRunCreateCommand> captor = ArgumentCaptor.forClass(TeamRunCreateCommand.class);
        verify(runService).startRun(captor.capture());
        TeamRunCreateCommand command = captor.getValue();
        assertEquals(TEAM_ID, command.getTeamId());
        assertEquals(WORKSPACE_ID, command.getWorkspaceId());
        assertEquals(LEAD_ID, command.getLeadAgentId());
        assertEquals(CONV, command.getLeadConversationId());
        assertEquals(99L, command.getOriginMessageId());
        assertEquals("Research", command.getTitle());
        assertEquals("Find evidence", command.getObjective());
    }

    // ==================== create pass-through ====================

    @Test
    @DisplayName("lead create parses ids, wires the lead conversation and reports the assignee")
    void leadCreatePassesThrough() {
        callerIs(LEAD_ID);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID))
                .thenReturn(run(TEAM_ID, CONV, TeamRunStatus.PLANNING));
        TeamTaskEntity created = task(50L, TeamTaskStatus.PENDING);
        when(taskService.createTask(any())).thenReturn(created);
        AgentEntity member = new AgentEntity();
        member.setName("写手");
        when(agentMapper.selectById(MEMBER_ID)).thenReturn(member);

        String out = tool.team_tasks("create", null, String.valueOf(RUN_ID), null, null,
                "collect data", "step details", String.valueOf(MEMBER_ID), "11,12", 5,
                null, null, null, null, null, null, null, null, null);

        assertTrue(out.startsWith("✓ Created task #3"));
        assertTrue(out.contains("写手"));
        ArgumentCaptor<TeamTaskCreateCommand> captor =
                ArgumentCaptor.forClass(TeamTaskCreateCommand.class);
        verify(taskService).createTask(captor.capture());
        TeamTaskCreateCommand cmd = captor.getValue();
        assertEquals(MEMBER_ID, cmd.getAssigneeAgentId());
        assertEquals(List.of(11L, 12L), cmd.getBlockedBy());
        assertEquals(LEAD_ID, cmd.getCreatedByAgentId());
        assertEquals(CONV, cmd.getLeadConversationId());
        assertEquals(RUN_ID, cmd.getRunId());
        verify(dispatchService, never()).requestDispatch(any());
    }

    @Test
    @DisplayName("creating a blocked task does not trigger a dispatch sweep")
    void blockedCreateDoesNotDispatch() {
        callerIs(LEAD_ID);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID))
                .thenReturn(run(TEAM_ID, CONV, TeamRunStatus.PLANNING));
        TeamTaskEntity blocked = task(51L, TeamTaskStatus.BLOCKED);
        when(taskService.createTask(any())).thenReturn(blocked);

        tool.team_tasks("create", null, String.valueOf(RUN_ID), null, null, "later step", null,
                String.valueOf(MEMBER_ID), "50", null, null, null, null, null, null, null,
                null, null, null);

        verify(dispatchService, never()).requestDispatch(any());
    }

    @Test
    @DisplayName("lead create passes requireApproval through to the command")
    void createPassesRequireApproval() {
        callerIs(LEAD_ID);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID))
                .thenReturn(run(TEAM_ID, CONV, TeamRunStatus.PLANNING));
        when(taskService.createTask(any())).thenReturn(task(52L, TeamTaskStatus.PENDING));

        tool.team_tasks("create", null, String.valueOf(RUN_ID), null, null, "publish notes", null,
                String.valueOf(MEMBER_ID), null, null, true, null, null, null, null, null,
                null, null, null);

        ArgumentCaptor<TeamTaskCreateCommand> captor =
                ArgumentCaptor.forClass(TeamTaskCreateCommand.class);
        verify(taskService).createTask(captor.capture());
        assertTrue(captor.getValue().isRequireApproval());
    }

    @Test
    @DisplayName("file-producing create task records a required deliverable contract")
    void createInfersRequiredDeliverableFromDescription() {
        callerIs(LEAD_ID);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID))
                .thenReturn(run(TEAM_ID, CONV, TeamRunStatus.PLANNING));
        when(taskService.createTask(any())).thenReturn(task(53L, TeamTaskStatus.PENDING));

        tool.team_tasks("create", null, String.valueOf(RUN_ID), null, null,
                "生成报告", "请生成最终 DOCX 文件", String.valueOf(MEMBER_ID), null, null,
                null, null, null, null, null, null, null, null, null);

        ArgumentCaptor<TeamTaskCreateCommand> captor =
                ArgumentCaptor.forClass(TeamTaskCreateCommand.class);
        verify(taskService).createTask(captor.capture());
        assertTrue(captor.getValue().getMetadata().contains("\"deliverableRequired\":true"));
    }

    @Test
    @DisplayName("create requires an explicit run id")
    void createRequiresRunId() {
        callerIs(LEAD_ID);

        String output = tool.team_tasks("create", null, null, null, null, "subject", "details",
                String.valueOf(MEMBER_ID), null, null, null, null, null, null, null, null,
                null, null, null);

        assertTrue(output.contains("runId is required"));
        verify(taskService, never()).createTask(any());
    }

    @Test
    @DisplayName("create rejects a run owned by another team")
    void createRejectsForeignRun() {
        callerIs(LEAD_ID);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID))
                .thenReturn(run(999L, CONV, TeamRunStatus.PLANNING));

        String output = tool.team_tasks("create", null, String.valueOf(RUN_ID), null, null,
                "subject", "details", String.valueOf(MEMBER_ID), null, null, null, null,
                null, null, null, null, null, null, null);

        assertTrue(output.contains("runId"));
        verify(taskService, never()).createTask(any());
    }

    @Test
    @DisplayName("create rejects a run owned by another lead conversation")
    void createRejectsForeignConversationRun() {
        callerIs(LEAD_ID);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID))
                .thenReturn(run(TEAM_ID, "other-conversation", TeamRunStatus.PLANNING));

        String output = tool.team_tasks("create", null, String.valueOf(RUN_ID), null, null,
                "subject", "details", String.valueOf(MEMBER_ID), null, null, null, null,
                null, null, null, null, null, null, null);

        assertTrue(output.contains("lead conversation"));
        verify(taskService, never()).createTask(any());
    }

    @Test
    @DisplayName("seal_run dispatches once only after the run is sealed")
    void sealRunDispatchesAfterSeal() {
        callerIs(LEAD_ID);
        TeamRunEntity planning = run(TEAM_ID, CONV, TeamRunStatus.PLANNING);
        TeamRunEntity running = run(TEAM_ID, CONV, TeamRunStatus.RUNNING);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID)).thenReturn(planning);
        when(runService.sealRunWithResult(RUN_ID, WORKSPACE_ID))
                .thenReturn(new TeamRunService.SealResult(running, true));

        String output = tool.team_tasks("seal_run", null, String.valueOf(RUN_ID), null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertTrue(output.contains("sealed"));
        InOrder order = inOrder(runService, dispatchService);
        order.verify(runService).sealRunWithResult(RUN_ID, WORKSPACE_ID);
        order.verify(dispatchService).requestDispatch(TEAM_ID);
        verify(dispatchService, times(1)).requestDispatch(TEAM_ID);
    }

    @Test
    @DisplayName("repeated seal_run does not dispatch again")
    void repeatedSealRunDoesNotDispatch() {
        callerIs(LEAD_ID);
        TeamRunEntity running = run(TEAM_ID, CONV, TeamRunStatus.RUNNING);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID)).thenReturn(running);
        when(runService.sealRunWithResult(RUN_ID, WORKSPACE_ID))
                .thenReturn(new TeamRunService.SealResult(running, false));

        String output = tool.team_tasks("seal_run", null, String.valueOf(RUN_ID), null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertTrue(output.contains("already sealed"));
        verify(dispatchService, never()).requestDispatch(any());
    }

    @Test
    @DisplayName("seal_run does not dispatch when sealing fails")
    void sealRunFailureDoesNotDispatch() {
        callerIs(LEAD_ID);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID))
                .thenReturn(run(TEAM_ID, CONV, TeamRunStatus.PLANNING));
        when(runService.sealRunWithResult(RUN_ID, WORKSPACE_ID))
                .thenThrow(new IllegalStateException("cannot seal a team run without tasks"));

        String output = tool.team_tasks("seal_run", null, String.valueOf(RUN_ID), null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertTrue(output.startsWith("Error:"));
        verify(dispatchService, never()).requestDispatch(any());
    }

    @Test
    @DisplayName("lead cancel interrupts the running member conversation")
    void cancelInterruptsRun() {
        callerIs(LEAD_ID);
        TeamTaskEntity running = task(5L, TeamTaskStatus.IN_PROGRESS);
        running.setConversationId("team-task-abc");
        when(taskService.getTask(5L)).thenReturn(running);
        when(taskService.cancelTask(eq(5L), any())).thenReturn(List.of(6L));

        String out = invoke("cancel", "5");

        assertTrue(out.startsWith("✓ Task cancelled"));
        verify(dispatchService).interruptRun(running);
        verify(dispatchService).requestDispatch(TEAM_ID);
    }

    @Test
    @DisplayName("service validation errors surface as Error: strings, not exceptions")
    void serviceErrorsBecomeStrings() {
        callerIs(LEAD_ID);
        when(taskService.createTask(any()))
                .thenThrow(new IllegalArgumentException("assignee is required"));
        when(runService.requireRun(RUN_ID, WORKSPACE_ID))
                .thenReturn(run(TEAM_ID, CONV, TeamRunStatus.PLANNING));
        String out = tool.team_tasks("create", null, String.valueOf(RUN_ID), null, null,
                "s", null, String.valueOf(MEMBER_ID), null, null, null, null, null, null,
                null, null, null, null, null);
        assertEquals("Error: assignee is required", out);
    }

    // ==================== member execution actions ====================

    @Test
    @DisplayName("complete requires a result and reports released dependents")
    void completeReportsRelease() {
        callerIs(MEMBER_ID);
        when(taskService.getTask(5L)).thenReturn(task(5L, TeamTaskStatus.COMPLETED));
        when(taskService.completeTask(5L, MEMBER_ID, "done, see report"))
                .thenReturn(List.of(6L));

        assertTrue(invoke("complete", "5").startsWith("Error: result is required"));

        String ok = tool.team_tasks("complete", "5", null, null, null, null, null, null, null,
                null, null, "done, see report", null, null, null, null, null, null, null);
        assertTrue(ok.contains("Released 1 dependent task(s)"));
    }

    @Test
    @DisplayName("a blocker comment tells the member to stop working")
    void blockerCommentStops() {
        callerIs(MEMBER_ID);
        when(taskService.getTask(5L)).thenReturn(task(5L, TeamTaskStatus.IN_PROGRESS));
        when(taskService.addComment(eq(5L), eq(TeamTaskService.AUTHOR_AGENT),
                anyString(), eq("blocker"), anyString())).thenReturn(true);

        String out = tool.team_tasks("comment", "5", null, null, null, null, null, null, null,
                null, null, null, null, null, "missing credentials", "blocker", null, null, null);
        assertTrue(out.contains("stop working"));
    }

    @Test
    @DisplayName("attach registers a deliverable and reminds the member to keep the result a summary")
    void attachRegistersDeliverable() {
        callerIs(MEMBER_ID);
        when(taskService.getTask(5L)).thenReturn(task(5L, TeamTaskStatus.IN_PROGRESS));

        String out = tool.team_tasks("attach", "5", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                "report.docx", "/api/v1/files/generated/abc", null);

        assertTrue(out.startsWith("✓ Deliverable attached: report.docx"));
        verify(taskService).addDeliverable(5L, MEMBER_ID, "report.docx",
                "/api/v1/files/generated/abc");
    }

    @Test
    @DisplayName("a task from another team is invisible")
    void foreignTaskRejected() {
        callerIs(MEMBER_ID);
        TeamTaskEntity foreign = task(5L, TeamTaskStatus.PENDING);
        foreign.setTeamId(999L);
        when(taskService.getTask(5L)).thenReturn(foreign);

        assertTrue(invoke("get", "5").contains("not found on this team's board"));
    }
}
