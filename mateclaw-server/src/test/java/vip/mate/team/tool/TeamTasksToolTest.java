package vip.mate.team.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.service.TeamDispatchService;
import vip.mate.team.service.TeamService;
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

    private TeamService teamService;
    private TeamTaskService taskService;
    private TeamDispatchService dispatchService;
    private ConversationService conversationService;
    private AgentMapper agentMapper;
    private TeamTasksTool tool;
    private AgentTeamEntity team;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        taskService = mock(TeamTaskService.class);
        dispatchService = mock(TeamDispatchService.class);
        conversationService = mock(ConversationService.class);
        agentMapper = mock(AgentMapper.class);
        tool = new TeamTasksTool(teamService, taskService, dispatchService,
                conversationService, agentMapper);

        team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setName("研发组");
        team.setLeadAgentId(LEAD_ID);

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
        return tool.team_tasks(action, taskId, null, null, null, null, null,
                null, null, null, null, null, null);
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
        when(conversationService.findByConversationId(CONV)).thenReturn(conv);
        when(teamService.getTeamForAgent(99L)).thenReturn(Optional.empty());

        assertTrue(invoke("list", null).contains("not part of any agent team"));
    }

    @Test
    @DisplayName("unknown action lists the valid ones")
    void unknownAction() {
        callerIs(LEAD_ID);
        assertTrue(invoke("destroy", null).contains("unknown action"));
    }

    // ==================== role gating ====================

    @Test
    @DisplayName("member create is refused — only the lead delegates")
    void memberCannotCreate() {
        callerIs(MEMBER_ID);
        String out = tool.team_tasks("create", null, "subj", "desc",
                String.valueOf(MEMBER_ID), null, null, null, null, null, null, null, null);
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

    // ==================== create pass-through ====================

    @Test
    @DisplayName("lead create parses ids, wires the lead conversation and reports the assignee")
    void leadCreatePassesThrough() {
        callerIs(LEAD_ID);
        TeamTaskEntity created = task(50L, TeamTaskStatus.PENDING);
        when(taskService.createTask(any())).thenReturn(created);
        AgentEntity member = new AgentEntity();
        member.setName("写手");
        when(agentMapper.selectById(MEMBER_ID)).thenReturn(member);

        String out = tool.team_tasks("create", null, "collect data", "step details",
                String.valueOf(MEMBER_ID), "11,12", 5, null, null, null, null, null, null);

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
        verify(dispatchService).requestDispatch(TEAM_ID);
    }

    @Test
    @DisplayName("creating a blocked task does not trigger a dispatch sweep")
    void blockedCreateDoesNotDispatch() {
        callerIs(LEAD_ID);
        TeamTaskEntity blocked = task(51L, TeamTaskStatus.BLOCKED);
        when(taskService.createTask(any())).thenReturn(blocked);

        tool.team_tasks("create", null, "later step", null,
                String.valueOf(MEMBER_ID), "50", null, null, null, null, null, null, null);

        verify(dispatchService, never()).requestDispatch(any());
    }

    @Test
    @DisplayName("service validation errors surface as Error: strings, not exceptions")
    void serviceErrorsBecomeStrings() {
        callerIs(LEAD_ID);
        when(taskService.createTask(any()))
                .thenThrow(new IllegalArgumentException("assignee is required"));
        String out = tool.team_tasks("create", null, "s", null,
                String.valueOf(MEMBER_ID), null, null, null, null, null, null, null, null);
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

        String ok = tool.team_tasks("complete", "5", null, null, null, null, null,
                "done, see report", null, null, null, null, null);
        assertTrue(ok.contains("Released 1 dependent task(s)"));
    }

    @Test
    @DisplayName("a blocker comment tells the member to stop working")
    void blockerCommentStops() {
        callerIs(MEMBER_ID);
        when(taskService.getTask(5L)).thenReturn(task(5L, TeamTaskStatus.IN_PROGRESS));
        when(taskService.addComment(eq(5L), eq(TeamTaskService.AUTHOR_AGENT),
                anyString(), eq("blocker"), anyString())).thenReturn(true);

        String out = tool.team_tasks("comment", "5", null, null, null, null, null,
                null, null, null, "missing credentials", "blocker", null);
        assertTrue(out.contains("stop working"));
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
