package vip.mate.team.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskCommentEntity;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.repository.TeamTaskCommentMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Pins the task board's transition guards: mandatory assignee, lead
 * self-assignment rejection, approval parking, blocker-comment auto-fail,
 * the dispatch circuit breaker, and dependency release semantics.
 */
class TeamTaskServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long LEAD_ID = 1L;
    private static final Long MEMBER_ID = 2L;

    private TeamTaskMapper taskMapper;
    private TeamTaskCommentMapper commentMapper;
    private TeamService teamService;
    private TeamTaskService service;

    @BeforeAll
    static void initTableInfo() {
        // Lambda wrappers resolve column names from MyBatis-Plus's static
        // TableInfo cache; in a Spring context this happens during mapper
        // scan, in a plain Mockito test we trigger it manually.
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, TeamTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, TeamTaskCommentEntity.class);
    }

    @BeforeEach
    void setUp() {
        taskMapper = mock(TeamTaskMapper.class);
        commentMapper = mock(TeamTaskCommentMapper.class);
        teamService = mock(TeamService.class);
        service = new TeamTaskService(taskMapper, commentMapper, teamService);

        AgentTeamEntity team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setLeadAgentId(LEAD_ID);
        team.setStatus(TeamService.STATUS_ACTIVE);
        when(teamService.getTeam(TEAM_ID)).thenReturn(team);
        when(teamService.isMember(TEAM_ID, MEMBER_ID)).thenReturn(true);
        when(teamService.nextTaskNumber(TEAM_ID)).thenReturn(1);
    }

    private TeamTaskCreateCommand.TeamTaskCreateCommandBuilder baseCreate() {
        return TeamTaskCreateCommand.builder()
                .teamId(TEAM_ID)
                .subject("write report")
                .assigneeAgentId(MEMBER_ID);
    }

    private TeamTaskEntity task(Long id, String status) {
        TeamTaskEntity t = new TeamTaskEntity();
        t.setId(id);
        t.setTeamId(TEAM_ID);
        t.setTaskNumber(7);
        t.setStatus(status);
        return t;
    }

    // ==================== creation guards ====================

    @Test
    @DisplayName("create without assignee is rejected")
    void createRequiresAssignee() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createTask(baseCreate().assigneeAgentId(null).build()));
        assertTrue(e.getMessage().contains("assignee is required"));
        verify(taskMapper, never()).insert(any(TeamTaskEntity.class));
    }

    @Test
    @DisplayName("assigning a task to the lead is rejected (dual-session loop guard)")
    void createRejectsLeadAssignee() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(baseCreate().assigneeAgentId(LEAD_ID).build()));
        verify(taskMapper, never()).insert(any(TeamTaskEntity.class));
    }

    @Test
    @DisplayName("blocking on an already-terminal task is rejected")
    void createRejectsTerminalBlocker() {
        when(taskMapper.selectById(99L)).thenReturn(task(99L, TeamTaskStatus.COMPLETED));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createTask(baseCreate().blockedBy(List.of(99L)).build()));
        assertTrue(e.getMessage().contains("already completed"));
    }

    @Test
    @DisplayName("a task with live blockers is created in blocked status with string-id JSON")
    void createWithBlockersStartsBlocked() {
        when(taskMapper.selectById(99L)).thenReturn(task(99L, TeamTaskStatus.PENDING));

        TeamTaskEntity created = service.createTask(baseCreate().blockedBy(List.of(99L)).build());

        assertEquals(TeamTaskStatus.BLOCKED, created.getStatus());
        // Ids must serialize as JSON strings to survive the JS frontend intact.
        assertEquals("[\"99\"]", created.getBlockedBy());
        verify(taskMapper).insert(created);
    }

    @Test
    @DisplayName("a plain task is created pending with the team-sequential number")
    void createPlainTaskPending() {
        TeamTaskEntity created = service.createTask(baseCreate().build());
        assertEquals(TeamTaskStatus.PENDING, created.getStatus());
        assertEquals(1, created.getTaskNumber());
        assertEquals(0, created.getDispatchCount());
    }

    // ==================== completion ====================

    @Test
    @DisplayName("completing a require-approval task parks it and releases nothing")
    void completeWithApprovalParksInReview() {
        TeamTaskEntity t = task(5L, TeamTaskStatus.IN_PROGRESS);
        t.setOwnerAgentId(MEMBER_ID);
        t.setRequireApproval(true);
        when(taskMapper.selectById(5L)).thenReturn(t);
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        List<Long> released = service.completeTask(5L, MEMBER_ID, "done");

        assertTrue(released.isEmpty(), "in_review must not release dependents yet");
        // Only the completion update ran; no dependent scan happened.
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("completion by a non-owner is rejected")
    void completeByNonOwnerRejected() {
        TeamTaskEntity t = task(5L, TeamTaskStatus.IN_PROGRESS);
        t.setOwnerAgentId(MEMBER_ID);
        when(taskMapper.selectById(5L)).thenReturn(t);

        assertThrows(IllegalStateException.class, () -> service.completeTask(5L, 3L, "hijack"));
        verify(taskMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("completing a terminal task fails with a state error")
    void completeTerminalRejected() {
        when(taskMapper.selectById(5L)).thenReturn(task(5L, TeamTaskStatus.CANCELLED));
        when(taskMapper.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.completeTask(5L, MEMBER_ID, "late"));
    }

    // ==================== blocker comment ====================

    @Test
    @DisplayName("a blocker comment auto-fails the task and reports escalation")
    void blockerCommentAutoFails() {
        when(taskMapper.selectById(5L)).thenReturn(task(5L, TeamTaskStatus.IN_PROGRESS));
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        boolean escalate = service.addComment(5L, TeamTaskService.AUTHOR_AGENT, "2",
                TeamTaskService.COMMENT_BLOCKER, "missing API docs");

        assertTrue(escalate, "caller must escalate to the lead");
        ArgumentCaptor<TeamTaskCommentEntity> captor =
                ArgumentCaptor.forClass(TeamTaskCommentEntity.class);
        verify(commentMapper).insert(captor.capture());
        assertEquals(TeamTaskService.COMMENT_BLOCKER, captor.getValue().getCommentType());
        verify(taskMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("a note comment neither fails the task nor escalates")
    void noteCommentIsInert() {
        when(taskMapper.selectById(5L)).thenReturn(task(5L, TeamTaskStatus.IN_PROGRESS));

        boolean escalate = service.addComment(5L, TeamTaskService.AUTHOR_USER, "admin",
                null, "looking good");

        assertFalse(escalate);
        verify(taskMapper, never()).update(isNull(), any());
    }

    // ==================== circuit breaker ====================

    @Test
    @DisplayName("dispatch within the cap succeeds without failing the task")
    void dispatchWithinCap() {
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        assertTrue(service.tryAcquireDispatch(5L));
        verify(taskMapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("exhausted dispatch cap auto-fails the task and returns false")
    void dispatchCapExhaustedFailsTask() {
        // First update (increment guarded by cap) misses; second (failTask) lands.
        when(taskMapper.update(isNull(), any())).thenReturn(0).thenReturn(1);

        assertFalse(service.tryAcquireDispatch(5L));
        verify(taskMapper, times(2)).update(isNull(), any());
    }

    // ==================== dependency release ====================

    @Test
    @DisplayName("a dependent is released only when ALL blockers reached a releasing status")
    void releaseWaitsForAllBlockers() {
        TeamTaskEntity finished = task(1L, TeamTaskStatus.COMPLETED);
        TeamTaskEntity dependent = task(3L, TeamTaskStatus.BLOCKED);
        dependent.setBlockedBy("[\"1\",\"2\"]");
        when(taskMapper.selectList(any())).thenReturn(List.of(dependent));
        // The sibling blocker is still failed (not a releasing status).
        when(taskMapper.selectById(2L)).thenReturn(task(2L, TeamTaskStatus.FAILED));

        assertTrue(service.releaseDependents(finished).isEmpty(),
                "failed sibling blocker must keep the dependent blocked");

        // Sibling now cancelled — cancellation releases dependents.
        when(taskMapper.selectById(2L)).thenReturn(task(2L, TeamTaskStatus.CANCELLED));
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        assertEquals(List.of(3L), service.releaseDependents(finished));
    }

    @Test
    @DisplayName("tasks not blocked on the finished task are ignored")
    void unrelatedBlockedTaskUntouched() {
        TeamTaskEntity finished = task(1L, TeamTaskStatus.COMPLETED);
        TeamTaskEntity unrelated = task(4L, TeamTaskStatus.BLOCKED);
        unrelated.setBlockedBy("[\"8\"]");
        when(taskMapper.selectList(any())).thenReturn(List.of(unrelated));

        assertTrue(service.releaseDependents(finished).isEmpty());
        verify(taskMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("malformed blocked_by JSON degrades to an empty blocker list")
    void parseIdArrayTolerant() {
        assertTrue(TeamTaskService.parseIdArray(null).isEmpty());
        assertTrue(TeamTaskService.parseIdArray(" ").isEmpty());
        assertTrue(TeamTaskService.parseIdArray("not-json").isEmpty());
        assertEquals(List.of(99L), TeamTaskService.parseIdArray("[\"99\"]"));
    }
}
