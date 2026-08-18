package vip.mate.team.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import vip.mate.team.repository.AgentTeamMapper;
import vip.mate.team.repository.AgentTeamMemberMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pins team creation guards. Any agent type may lead: a plan-execute lead's
 * multi-step plans hand off to the team board through the plan bridge, so the
 * former ReAct-only restriction no longer applies.
 */
class TeamServiceTest {

    private static final Long LEAD_ID = 1L;
    private static final Long MEMBER_ID = 2L;

    @BeforeAll
    static void initTableInfo() {
        // Lambda wrappers resolve columns from the static TableInfo cache;
        // plain Mockito tests must seed it manually.
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentTeamEntity.class);
        TableInfoHelper.initTableInfo(assistant, AgentTeamMemberEntity.class);
    }

    private AgentTeamMapper teamMapper;
    private AgentTeamMemberMapper memberMapper;
    private AgentMapper agentMapper;
    private TeamService service;

    @BeforeEach
    void setUp() {
        teamMapper = mock(AgentTeamMapper.class);
        memberMapper = mock(AgentTeamMemberMapper.class);
        agentMapper = mock(AgentMapper.class);
        service = new TeamService(teamMapper, memberMapper, agentMapper,
                mock(ApplicationEventPublisher.class));
    }

    private AgentEntity agent(Long id, String agentType) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName("agent-" + id);
        a.setAgentType(agentType);
        a.setWorkspaceId(1L);
        return a;
    }

    @Test
    @DisplayName("a plan-execute agent may lead — its plans hand off to the board via the bridge")
    void planExecuteLeadAllowed() {
        when(agentMapper.selectById(LEAD_ID)).thenReturn(agent(LEAD_ID, "plan_execute"));
        when(agentMapper.selectById(MEMBER_ID)).thenReturn(agent(MEMBER_ID, "react"));
        when(memberMapper.selectCount(any())).thenReturn(0L);

        assertDoesNotThrow(() ->
                service.createTeam(1L, "组", null, LEAD_ID, List.of(MEMBER_ID), "admin"));
        org.mockito.ArgumentCaptor<AgentTeamEntity> captor =
                org.mockito.ArgumentCaptor.forClass(AgentTeamEntity.class);
        verify(teamMapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getWorkspaceId());
    }

    @Test
    void rejectsMemberFromAnotherWorkspace() {
        AgentEntity lead = agent(LEAD_ID, "react");
        AgentEntity member = agent(MEMBER_ID, "react");
        member.setWorkspaceId(2L);
        when(agentMapper.selectById(LEAD_ID)).thenReturn(lead);
        when(agentMapper.selectById(MEMBER_ID)).thenReturn(member);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTeam(1L, "组", null, LEAD_ID, List.of(MEMBER_ID), "admin"));

        assertEquals("member agent does not belong to the current workspace: 2", error.getMessage());
        verify(teamMapper, never()).insert(any(AgentTeamEntity.class));
    }

    @Test
    void listTeamsAlwaysScopesByWorkspace() {
        when(teamMapper.selectList(any())).thenReturn(List.of());

        service.listTeams(7L);

        @SuppressWarnings("rawtypes")
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(teamMapper).selectList(captor.capture());
        com.baomidou.mybatisplus.core.conditions.Wrapper<?> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSegment().toLowerCase().contains("workspace"), wrapper.getSqlSegment());
        @SuppressWarnings("unchecked")
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentTeamEntity> lambda =
                (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentTeamEntity>) wrapper;
        assertTrue(lambda.getParamNameValuePairs().containsValue(7L));
    }

    @Test
    void agentCannotResolveTeamOwnedByAnotherWorkspace() {
        AgentEntity agent = agent(LEAD_ID, "react");
        AgentTeamMemberEntity membership = new AgentTeamMemberEntity();
        membership.setTeamId(10L);
        AgentTeamEntity foreignTeam = new AgentTeamEntity();
        foreignTeam.setId(10L);
        foreignTeam.setWorkspaceId(2L);
        foreignTeam.setStatus(TeamService.STATUS_ACTIVE);
        when(agentMapper.selectById(LEAD_ID)).thenReturn(agent);
        when(memberMapper.selectOne(any())).thenReturn(membership);
        when(teamMapper.selectById(10L)).thenReturn(foreignTeam);

        Optional<AgentTeamEntity> result = service.getTeamForAgent(LEAD_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    void addMemberRejectsAgentFromAnotherWorkspace() {
        AgentTeamEntity team = new AgentTeamEntity();
        team.setId(10L);
        team.setLeadAgentId(LEAD_ID);
        team.setWorkspaceId(1L);
        AgentEntity foreignMember = agent(MEMBER_ID, "react");
        foreignMember.setWorkspaceId(2L);
        when(teamMapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(team);
        when(agentMapper.selectById(MEMBER_ID)).thenReturn(foreignMember);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMember(10L, 1L, MEMBER_ID, "member"));

        assertEquals("member agent does not belong to the current workspace: 2", error.getMessage());
        verify(memberMapper, never()).insert(any(AgentTeamMemberEntity.class));
    }
}
