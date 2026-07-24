package vip.mate.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import vip.mate.team.model.TeamRole;

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
    private AgentMapper agentMapper;
    private TeamContextBuilder builder;
    private AgentTeamEntity team;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        agentMapper = mock(AgentMapper.class);
        builder = new TeamContextBuilder(teamService, agentMapper);

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
}
