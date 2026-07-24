package vip.mate.team.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.team.event.TeamChangedEvent;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import vip.mate.team.model.TeamRole;
import vip.mate.team.repository.AgentTeamMapper;
import vip.mate.team.repository.AgentTeamMemberMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Team registry: create/update/delete teams and manage membership.
 *
 * Invariants enforced here:
 * - every team has exactly one lead (the creating lead is auto-added with the lead role);
 * - an agent belongs to at most one active team (keeps system-prompt team context unambiguous);
 * - the lead cannot be removed or demoted while the team exists.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";

    private final AgentTeamMapper teamMapper;
    private final AgentTeamMemberMapper memberMapper;
    private final AgentMapper agentMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public AgentTeamEntity createTeam(String name, String description, Long leadAgentId,
                                      List<Long> memberAgentIds, String createdBy) {
        requireAgentExists(leadAgentId, "lead");
        requireNotInAnyTeam(leadAgentId);
        if (memberAgentIds != null) {
            for (Long memberId : memberAgentIds) {
                if (memberId.equals(leadAgentId)) {
                    throw new IllegalArgumentException("lead agent cannot also be listed as a member");
                }
                requireAgentExists(memberId, "member");
                requireNotInAnyTeam(memberId);
            }
        }

        AgentTeamEntity team = new AgentTeamEntity();
        team.setName(name);
        team.setDescription(description);
        team.setLeadAgentId(leadAgentId);
        team.setStatus(STATUS_ACTIVE);
        team.setTaskSeq(0);
        team.setCreatedBy(createdBy);
        teamMapper.insert(team);

        insertMember(team.getId(), leadAgentId, TeamRole.LEAD);
        if (memberAgentIds != null) {
            memberAgentIds.forEach(id -> insertMember(team.getId(), id, TeamRole.MEMBER));
        }
        log.info("Created agent team {} ({}) lead={} members={}", team.getId(), name,
                leadAgentId, memberAgentIds == null ? 0 : memberAgentIds.size());
        notifyTeamChanged(team.getId());
        return team;
    }

    @Transactional
    public void addMember(Long teamId, Long agentId, String role) {
        AgentTeamEntity team = requireTeam(teamId);
        if (TeamRole.LEAD.equals(role)) {
            throw new IllegalArgumentException("a team has exactly one lead; role must be member or reviewer");
        }
        if (agentId.equals(team.getLeadAgentId())) {
            throw new IllegalArgumentException("agent is already the team lead");
        }
        requireAgentExists(agentId, "member");
        requireNotInAnyTeam(agentId);
        insertMember(teamId, agentId, role == null ? TeamRole.MEMBER : role);
        notifyTeamChanged(teamId);
    }

    @Transactional
    public void removeMember(Long teamId, Long agentId) {
        AgentTeamEntity team = requireTeam(teamId);
        if (agentId.equals(team.getLeadAgentId())) {
            throw new IllegalArgumentException("cannot remove the team lead; delete the team instead");
        }
        memberMapper.delete(Wrappers.<AgentTeamMemberEntity>lambdaQuery()
                .eq(AgentTeamMemberEntity::getTeamId, teamId)
                .eq(AgentTeamMemberEntity::getAgentId, agentId));
        // The removed agent's prompt must drop the team block too.
        eventPublisher.publishEvent(new TeamChangedEvent(List.of(agentId)));
        notifyTeamChanged(teamId);
    }

    @Transactional
    public void deleteTeam(Long teamId) {
        requireTeam(teamId);
        // Capture membership before it is wiped so every agent gets evicted.
        List<Long> agentIds = listMembers(teamId).stream()
                .map(AgentTeamMemberEntity::getAgentId).toList();
        memberMapper.delete(Wrappers.<AgentTeamMemberEntity>lambdaQuery()
                .eq(AgentTeamMemberEntity::getTeamId, teamId));
        teamMapper.deleteById(teamId);
        eventPublisher.publishEvent(new TeamChangedEvent(agentIds));
    }

    @Transactional
    public AgentTeamEntity updateTeam(Long teamId, String name, String description, String settings) {
        AgentTeamEntity team = requireTeam(teamId);
        if (name != null) {
            team.setName(name);
        }
        if (description != null) {
            team.setDescription(description);
        }
        if (settings != null) {
            team.setSettings(settings);
        }
        teamMapper.updateById(team);
        notifyTeamChanged(teamId);
        return team;
    }

    public List<AgentTeamEntity> listTeams() {
        return teamMapper.selectList(Wrappers.<AgentTeamEntity>lambdaQuery()
                .orderByDesc(AgentTeamEntity::getCreateTime));
    }

    public AgentTeamEntity getTeam(Long teamId) {
        return teamMapper.selectById(teamId);
    }

    public List<AgentTeamMemberEntity> listMembers(Long teamId) {
        return memberMapper.selectList(Wrappers.<AgentTeamMemberEntity>lambdaQuery()
                .eq(AgentTeamMemberEntity::getTeamId, teamId)
                .orderByAsc(AgentTeamMemberEntity::getCreateTime));
    }

    /**
     * Resolve the (single) active team an agent belongs to. Used by the prompt
     * builder to inject team context and by the task tool to scope board access.
     */
    public Optional<AgentTeamEntity> getTeamForAgent(Long agentId) {
        AgentTeamMemberEntity member = memberMapper.selectOne(Wrappers.<AgentTeamMemberEntity>lambdaQuery()
                .eq(AgentTeamMemberEntity::getAgentId, agentId)
                .last("LIMIT 1"));
        if (member == null) {
            return Optional.empty();
        }
        AgentTeamEntity team = teamMapper.selectById(member.getTeamId());
        if (team == null || !STATUS_ACTIVE.equals(team.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(team);
    }

    public boolean isMember(Long teamId, Long agentId) {
        return memberMapper.selectCount(Wrappers.<AgentTeamMemberEntity>lambdaQuery()
                .eq(AgentTeamMemberEntity::getTeamId, teamId)
                .eq(AgentTeamMemberEntity::getAgentId, agentId)) > 0;
    }

    public boolean isLead(AgentTeamEntity team, Long agentId) {
        return team != null && agentId != null && agentId.equals(team.getLeadAgentId());
    }

    /**
     * Atomically advance and return the team's task counter. The UPDATE takes a
     * row lock so concurrent creators serialize on the counter instead of racing.
     */
    @Transactional
    public int nextTaskNumber(Long teamId) {
        int rows = teamMapper.update(null, Wrappers.<AgentTeamEntity>lambdaUpdate()
                .eq(AgentTeamEntity::getId, teamId)
                .setSql("task_seq = task_seq + 1"));
        if (rows != 1) {
            throw new IllegalStateException("team not found: " + teamId);
        }
        return teamMapper.selectById(teamId).getTaskSeq();
    }

    /** Evict every current member's cached agent so team context rebuilds next turn. */
    private void notifyTeamChanged(Long teamId) {
        List<Long> agentIds = new ArrayList<>(listMembers(teamId).stream()
                .map(AgentTeamMemberEntity::getAgentId).toList());
        if (!agentIds.isEmpty()) {
            eventPublisher.publishEvent(new TeamChangedEvent(agentIds));
        }
    }

    private void insertMember(Long teamId, Long agentId, String role) {
        AgentTeamMemberEntity member = new AgentTeamMemberEntity();
        member.setTeamId(teamId);
        member.setAgentId(agentId);
        member.setRole(role);
        memberMapper.insert(member);
    }

    private AgentTeamEntity requireTeam(Long teamId) {
        AgentTeamEntity team = teamMapper.selectById(teamId);
        if (team == null) {
            throw new IllegalArgumentException("team not found: " + teamId);
        }
        return team;
    }

    private void requireAgentExists(Long agentId, String roleLabel) {
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException(roleLabel + " agent not found: " + agentId);
        }
    }

    private void requireNotInAnyTeam(Long agentId) {
        // Membership check ignores team status on purpose: an agent parked in a
        // paused team must not silently join a second one.
        AgentTeamMemberEntity member = memberMapper.selectOne(Wrappers.<AgentTeamMemberEntity>lambdaQuery()
                .eq(AgentTeamMemberEntity::getAgentId, agentId)
                .last("LIMIT 1"));
        if (member != null) {
            throw new IllegalStateException("agent " + agentId + " already belongs to team "
                    + member.getTeamId() + "; an agent can join only one team");
        }
    }
}
