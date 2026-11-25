package vip.mate.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.team.event.TeamChangedEvent;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.repository.AgentTeamMapper;
import vip.mate.team.repository.AgentTeamMemberMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.util.List;

/** Persisted roster lifecycle checks; depends only on mappers to avoid runtime service cycles. */
@Service
@RequiredArgsConstructor
public class TeamRosterLifecycleService {
    private final AgentMapper agentMapper;
    private final TeamTaskMapper taskMapper;
    private final AgentTeamMapper teamMapper;
    private final AgentTeamMemberMapper memberMapper;
    private final ApplicationEventPublisher events;

    /** Current reads held until the caller's transaction commits. Lock agents before teams. */
    public void lockAgents(List<Long> agentIds) {
        agentIds.stream().distinct().sorted().forEach(id -> agentMapper.selectOne(
                Wrappers.<AgentEntity>lambdaQuery()
                        .eq(AgentEntity::getId, id).last("FOR UPDATE")));
    }

    public void lockTeam(Long teamId) {
        teamMapper.selectOne(Wrappers.<AgentTeamEntity>lambdaQuery()
                .eq(AgentTeamEntity::getId, teamId).last("FOR UPDATE"));
    }

    public void assertMemberRemovable(Long teamId, Long agentId) {
        assertNoMemberWork(unfinishedTasks().eq(TeamTaskEntity::getTeamId, teamId), agentId);
    }

    public void assertTeamDeletable(Long teamId) {
        if (taskMapper.selectCount(unfinishedTasks().eq(TeamTaskEntity::getTeamId, teamId)) > 0) {
            throw new MateClawException("err.team.has_unfinished_tasks", 409,
                    "Complete or cancel unfinished tasks on the team board before deleting this team.");
        }
    }

    /** Called inside AgentService's deletion transaction, before the agent row is deleted. */
    public void prepareAgentDeletion(Long agentId) {
        lockAgents(List.of(agentId));
        // Agent lock prevents new memberships. Discover without locking member
        // rows: removeMember acquires team then membership, so the inverse
        // order here would deadlock. READ_COMMITTED keeps later checks fresh.
        List<AgentTeamMemberEntity> memberships = memberMapper.selectList(
                Wrappers.<AgentTeamMemberEntity>lambdaQuery().eq(AgentTeamMemberEntity::getAgentId, agentId));
        memberships.stream().map(AgentTeamMemberEntity::getTeamId).distinct().sorted().forEach(this::lockTeam);
        if (teamMapper.selectCount(Wrappers.<AgentTeamEntity>lambdaQuery()
                .eq(AgentTeamEntity::getLeadAgentId, agentId)) > 0) {
            throw new MateClawException("err.team.lead_cannot_be_deleted", 409,
                    "Delete the agent's team before deleting its lead.");
        }
        assertNoMemberWork(unfinishedTasks(), agentId);
        memberMapper.delete(Wrappers.<AgentTeamMemberEntity>lambdaQuery()
                .eq(AgentTeamMemberEntity::getAgentId, agentId));
        for (Long teamId : memberships.stream().map(AgentTeamMemberEntity::getTeamId).distinct().toList()) {
            List<Long> remaining = memberMapper.selectList(Wrappers.<AgentTeamMemberEntity>lambdaQuery()
                    .eq(AgentTeamMemberEntity::getTeamId, teamId)).stream()
                    .map(AgentTeamMemberEntity::getAgentId).toList();
            if (!remaining.isEmpty()) events.publishEvent(new TeamChangedEvent(remaining));
        }
    }

    private void assertNoMemberWork(LambdaQueryWrapper<TeamTaskEntity> query, Long agentId) {
        query.and(q -> q.eq(TeamTaskEntity::getAssigneeAgentId, agentId)
                .or().eq(TeamTaskEntity::getOwnerAgentId, agentId));
        if (taskMapper.selectCount(query) > 0) {
            throw new MateClawException("err.team.member_has_unfinished_tasks", 409,
                    "Complete or cancel this member's unfinished tasks on the team board before removing or deleting it.");
        }
    }

    private LambdaQueryWrapper<TeamTaskEntity> unfinishedTasks() {
        return Wrappers.<TeamTaskEntity>lambdaQuery()
                .and(q -> q.notIn(TeamTaskEntity::getStatus, TeamTaskStatus.TERMINAL)
                        .or().isNull(TeamTaskEntity::getStatus));
    }
}
