package vip.mate.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.MateClawApplication;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.repository.TeamTaskMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MateClawApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:team_roster_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
@Transactional
class TeamRosterLifecycleTest {
    @Autowired private TeamService teams;
    @Autowired private AgentService agents;
    @Autowired private TeamTaskMapper tasks;
    @Autowired private TeamTaskService taskService;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private vip.mate.agent.repository.AgentMapper agentMapper;

    private AgentEntity lead;
    private AgentEntity member;
    private AgentTeamEntity team;

    @BeforeEach
    void seed() {
        lead = agent("lead");
        member = agent("member");
        team = teams.createTeam(1L, "Lifecycle test", null, lead.getId(), List.of(member.getId()), "test");
    }

    private AgentEntity agent(String name) {
        AgentEntity agent = new AgentEntity();
        agent.setName(name + UUID.randomUUID());
        agent.setWorkspaceId(1L);
        agent.setAgentType("react");
        return agents.createAgent(agent);
    }

    private TeamTaskEntity task(String status) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setTeamId(team.getId());
        task.setSubject("Protected work");
        task.setStatus(status);
        task.setAssigneeAgentId(member.getId());
        tasks.insert(task);
        return task;
    }

    private void conflict(Runnable action, String key) {
        MateClawException error = assertThrows(MateClawException.class, action::run);
        assertEquals(409, error.getCode());
        assertEquals(key, error.getMsgKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "blocked", "in_progress", "awaiting_approval", "in_review", "stale"})
    void unfinishedTaskProtectsAllDestructiveOperations(String status) {
        TeamTaskEntity task = task(status);
        conflict(() -> teams.removeMember(team.getId(), 1L, member.getId()), "err.team.member_has_unfinished_tasks");
        conflict(() -> teams.deleteTeam(team.getId(), 1L), "err.team.has_unfinished_tasks");
        conflict(() -> agents.deleteAgent(member.getId()), "err.team.member_has_unfinished_tasks");
        assertTrue(teams.isMember(team.getId(), member.getId()));
        assertNotNull(teams.getTeam(team.getId(), 1L));
        assertNotNull(agents.getAgent(member.getId()));
        assertEquals(status, tasks.selectById(task.getId()).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "failed", "cancelled"})
    void terminalTaskAllowsMemberRemovalAndTeamDeletion(String status) {
        TeamTaskEntity task = task(status);
        teams.removeMember(team.getId(), 1L, member.getId());
        assertFalse(teams.isMember(team.getId(), member.getId()));
        teams.deleteTeam(team.getId(), 1L);
        assertNull(teams.getTeam(team.getId(), 1L));
        assertNotNull(tasks.selectById(task.getId()), "Historical evidence must survive");
    }

    @Test
    void idleLeadCannotBeDeletedWhileTeamExists() {
        conflict(() -> agents.deleteAgent(lead.getId()), "err.team.lead_cannot_be_deleted");
        assertNotNull(agents.getAgent(lead.getId()));
        assertTrue(teams.isMember(team.getId(), lead.getId()));
    }

    @Test
    void idleMemberDeletionCleansUpMembership() {
        task("completed");
        agents.deleteAgent(member.getId());
        assertNull(agentMapper.selectById(member.getId()));
        assertTrue(teams.listMembers(team.getId()).stream().noneMatch(m -> m.getAgentId().equals(member.getId())));
    }

    @Test
    void deletingTeamReleasesItsLeadForDeletion() {
        teams.deleteTeam(team.getId(), 1L);
        assertDoesNotThrow(() -> agents.deleteAgent(lead.getId()));
        assertNull(agentMapper.selectById(lead.getId()));
    }

    @Test
    void currentOwnerIsProtectedEvenIfAssigneeDiffers() {
        TeamTaskEntity task = task("in_progress");
        task.setAssigneeAgentId(lead.getId());
        task.setOwnerAgentId(member.getId());
        tasks.updateById(task);
        conflict(() -> teams.removeMember(team.getId(), 1L, member.getId()), "err.team.member_has_unfinished_tasks");
        conflict(() -> agents.deleteAgent(member.getId()), "err.team.member_has_unfinished_tasks");
    }

    @Test
    void unrelatedTasksDoNotPreventIdleMemberRemoval() {
        AgentEntity other = agent("other");
        teams.addMember(team.getId(), 1L, other.getId(), "member");
        TeamTaskEntity task = task("pending");
        task.setAssigneeAgentId(other.getId());
        tasks.updateById(task);
        assertDoesNotThrow(() -> teams.removeMember(team.getId(), 1L, member.getId()));
        assertTrue(teams.isMember(team.getId(), other.getId()));
    }

    @Test
    void softDeletedTasksDoNotPreventTeamDeletion() {
        TeamTaskEntity task = task("pending");
        tasks.deleteById(task.getId());
        assertDoesNotThrow(() -> teams.deleteTeam(team.getId(), 1L));
    }

    @Test
    void foreignWorkspaceCannotRemoveOrDeleteTeam() {
        assertThrows(IllegalArgumentException.class, () -> teams.removeMember(team.getId(), 2L, member.getId()));
        assertThrows(IllegalArgumentException.class, () -> teams.deleteTeam(team.getId(), 2L));
        assertTrue(teams.isMember(team.getId(), member.getId()));
    }
    @Test
    void removedMemberCannotBeRetriedFromFailedHistory() {
        TeamTaskEntity task = task("failed");
        teams.removeMember(team.getId(), 1L, member.getId());
        assertFalse(taskService.retryTask(task.getId()));
        assertFalse(taskService.retryTaskFromLead(task.getId()));
        assertEquals("failed", tasks.selectById(task.getId()).getStatus());
    }

    @Test
    void deletedTeamCannotBeRetriedFromFailedHistory() {
        TeamTaskEntity task = task("failed");
        teams.deleteTeam(team.getId(), 1L);
        assertFalse(taskService.retryTask(task.getId()));
        assertEquals("failed", tasks.selectById(task.getId()).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove:create", "delete-agent:create", "delete-team:create", "remove:retry", "delete-agent:retry", "delete-team:retry"})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void taskMutationWaitsForRosterChangeThenRevalidates(String scenario) throws Exception {
        boolean retry = scenario.endsWith(":retry");
        TeamTaskEntity historicalTask = retry ? task("failed") : null;
        var removed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var removing = pool.submit(() -> {
                new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    switch (scenario.split(":")[0]) {
                        case "remove" -> teams.removeMember(team.getId(), 1L, member.getId());
                        case "delete-agent" -> agents.deleteAgent(member.getId());
                        case "delete-team" -> teams.deleteTeam(team.getId(), 1L);
                        default -> throw new IllegalArgumentException(scenario);
                    }
                    removed.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for release");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                });
            });
            assertTrue(removed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var command = vip.mate.team.model.TeamTaskCreateCommand.builder()
                    .teamId(team.getId()).assigneeAgentId(member.getId()).subject("Concurrent assignment").build();
            var creating = pool.submit(() -> retry
                    ? taskService.retryTask(historicalTask.getId()) : taskService.createTask(command));
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> creating.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            removing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (retry) {
                assertEquals(false, creating.get(5, java.util.concurrent.TimeUnit.SECONDS));
            } else {
                var error = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> creating.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(IllegalArgumentException.class, error.getCause());
            }
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_team_task WHERE team_id = ? AND status = 'pending'", Integer.class, team.getId()));
        } finally {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            jdbc.update("DELETE FROM mate_team_task_event WHERE team_id = ?", team.getId());
            jdbc.update("DELETE FROM mate_team_task WHERE team_id = ?", team.getId());
            jdbc.update("DELETE FROM mate_agent_team_member WHERE team_id = ?", team.getId());
            jdbc.update("DELETE FROM mate_agent_team WHERE id = ?", team.getId());
            jdbc.update("DELETE FROM mate_agent WHERE id IN (?, ?)", lead.getId(), member.getId());
        }
    }

}
