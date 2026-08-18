package vip.mate.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import vip.mate.team.model.TeamRole;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pins the role split of the team prompt block: lead gets the delegation
 * playbook, members get execution instructions, non-team agents get the
 * negative notice that keeps the model away from team_tasks.
 */
class TeamContextBuilderTest {

    private static final Long TEAM_ID = 10L;
    private static final Long LEAD_ID = 1L;
    private static final Long MEMBER_ID = 2L;

    private TeamService teamService;
    private TeamTaskService taskService;
    private AgentMapper agentMapper;
    private TeamContextBuilder builder;
    private AgentTeamEntity team;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        taskService = mock(TeamTaskService.class);
        agentMapper = mock(AgentMapper.class);
        builder = new TeamContextBuilder(teamService, taskService, agentMapper);

        team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setName("内容组");
        team.setDescription("负责内容生产");
        team.setLeadAgentId(LEAD_ID);

        AgentTeamMemberEntity lead = member(LEAD_ID, TeamRole.LEAD);
        AgentTeamMemberEntity writer = member(MEMBER_ID, TeamRole.MEMBER);
        when(teamService.listMembers(TEAM_ID)).thenReturn(List.of(lead, writer));

        AgentEntity leadAgent = agent("主管", "orchestrates");
        AgentEntity writerAgent = agent("写手", "writes articles");
        when(agentMapper.selectById(LEAD_ID)).thenReturn(leadAgent);
        when(agentMapper.selectById(MEMBER_ID)).thenReturn(writerAgent);
    }

    private static AgentTeamMemberEntity member(Long agentId, String role) {
        AgentTeamMemberEntity m = new AgentTeamMemberEntity();
        m.setTeamId(TEAM_ID);
        m.setAgentId(agentId);
        m.setRole(role);
        return m;
    }

    private static AgentEntity agent(String name, String description) {
        AgentEntity a = new AgentEntity();
        a.setName(name);
        a.setDescription(description);
        return a;
    }

    @Test
    @DisplayName("agents outside any team get the negative notice only")
    void noTeamNegativeNotice() {
        when(teamService.getTeamForAgent(99L)).thenReturn(Optional.empty());
        String ctx = builder.buildTeamContext(99L);
        assertTrue(ctx.contains("not part of any agent team"));
        assertFalse(ctx.contains("Delegation workflow"));
    }

    @Test
    @DisplayName("the lead gets the orchestration playbook and the member roster")
    void leadGetsPlaybook() {
        when(teamService.getTeamForAgent(LEAD_ID)).thenReturn(Optional.of(team));
        when(teamService.isLead(team, LEAD_ID)).thenReturn(true);

        String ctx = builder.buildTeamContext(LEAD_ID);

        assertTrue(ctx.contains("## Team: 内容组"));
        assertTrue(ctx.contains("LEAD — you orchestrate"));
        assertTrue(ctx.contains("Delegation workflow (mandatory)"));
        assertTrue(ctx.contains("Delegation is NOT completion"));
        int start = ctx.indexOf("team_tasks(action=\"start_run\"");
        int create = ctx.indexOf("team_tasks(action=\"create\"");
        int seal = ctx.indexOf("team_tasks(action=\"seal_run\"");
        assertTrue(start >= 0 && start < create && create < seal,
                "lead playbook must require start_run -> create* -> seal_run");
        assertTrue(ctx.contains("写手"));
        assertTrue(ctx.contains("agentId: " + MEMBER_ID));
        // The lead must not receive member execution instructions.
        assertFalse(ctx.contains("Working on assigned tasks"));
    }

    @Test
    @DisplayName("a member gets execution instructions, not the delegation playbook")
    void memberGetsExecutionRules() {
        when(teamService.getTeamForAgent(MEMBER_ID)).thenReturn(Optional.of(team));
        when(teamService.isLead(team, MEMBER_ID)).thenReturn(false);

        String ctx = builder.buildTeamContext(MEMBER_ID);

        assertTrue(ctx.contains("Your role: MEMBER."));
        assertTrue(ctx.contains("Working on assigned tasks"));
        assertTrue(ctx.contains("type=\"blocker\""));
        assertFalse(ctx.contains("Delegation workflow"));
        // Self is marked in the roster instead of repeating its description.
        assertTrue(ctx.contains("— you"));
    }

    // ==================== live board snapshot ====================

    private TeamTaskEntity boardTask(Long id, int number, String status) {
        TeamTaskEntity t = new TeamTaskEntity();
        t.setId(id);
        t.setTeamId(TEAM_ID);
        t.setTaskNumber(number);
        t.setSubject("task " + number);
        t.setStatus(status);
        t.setAssigneeAgentId(MEMBER_ID);
        return t;
    }

    @Test
    @DisplayName("the lead's snapshot lists in-flight tasks with progress and blockers")
    void snapshotForLeadShowsActiveTasks() {
        when(teamService.getTeamForAgent(LEAD_ID)).thenReturn(Optional.of(team));
        when(teamService.isLead(team, LEAD_ID)).thenReturn(true);
        TeamTaskEntity running = boardTask(11L, 1, TeamTaskStatus.IN_PROGRESS);
        running.setProgressPercent(40);
        TeamTaskEntity waiting = boardTask(12L, 2, TeamTaskStatus.BLOCKED);
        waiting.setBlockedBy("[\"11\"]");
        TeamTaskEntity done = boardTask(13L, 3, TeamTaskStatus.COMPLETED);
        when(taskService.listTasks(TEAM_ID, null)).thenReturn(List.of(running, waiting, done));

        String snapshot = builder.buildBoardSnapshot(LEAD_ID);

        assertNotNull(snapshot);
        assertTrue(snapshot.contains("#1 [in_progress] task 1"));
        assertTrue(snapshot.contains("40%"));
        assertTrue(snapshot.contains("#2 [blocked] task 2"));
        assertTrue(snapshot.contains("waits on #1"));
        // Terminal tasks stay off the snapshot — it is about in-flight work.
        assertFalse(snapshot.contains("#3"));
        assertTrue(snapshot.contains("Do NOT create a task duplicating"));
    }

    @Test
    @DisplayName("members and idle boards produce no snapshot")
    void snapshotNullForMemberAndIdleBoard() {
        when(teamService.getTeamForAgent(MEMBER_ID)).thenReturn(Optional.of(team));
        when(teamService.isLead(team, MEMBER_ID)).thenReturn(false);
        assertNull(builder.buildBoardSnapshot(MEMBER_ID));

        when(teamService.getTeamForAgent(LEAD_ID)).thenReturn(Optional.of(team));
        when(teamService.isLead(team, LEAD_ID)).thenReturn(true);
        when(taskService.listTasks(TEAM_ID, null))
                .thenReturn(List.of(boardTask(13L, 3, TeamTaskStatus.COMPLETED)));
        assertNull(builder.buildBoardSnapshot(LEAD_ID));

        when(teamService.getTeamForAgent(99L)).thenReturn(Optional.empty());
        assertNull(builder.buildBoardSnapshot(99L));
    }

    @Test
    @DisplayName("boards larger than the line cap fold into an overflow line")
    void snapshotFoldsLargeBoards() {
        when(teamService.getTeamForAgent(LEAD_ID)).thenReturn(Optional.of(team));
        when(teamService.isLead(team, LEAD_ID)).thenReturn(true);
        List<TeamTaskEntity> tasks = new java.util.ArrayList<>();
        for (int i = 1; i <= TeamContextBuilder.SNAPSHOT_MAX_LINES + 3; i++) {
            tasks.add(boardTask(100L + i, i, TeamTaskStatus.PENDING));
        }
        when(taskService.listTasks(TEAM_ID, null)).thenReturn(tasks);

        String snapshot = builder.buildBoardSnapshot(LEAD_ID);

        assertNotNull(snapshot);
        assertTrue(snapshot.contains("#" + TeamContextBuilder.SNAPSHOT_MAX_LINES + " [pending]"));
        assertFalse(snapshot.contains("#" + (TeamContextBuilder.SNAPSHOT_MAX_LINES + 1) + " [pending]"));
        assertTrue(snapshot.contains("…and 3 more"));
    }
}
