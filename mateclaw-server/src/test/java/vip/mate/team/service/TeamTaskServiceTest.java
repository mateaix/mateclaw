package vip.mate.team.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamTaskCommentEntity;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskEventEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.repository.TeamTaskCommentMapper;
import vip.mate.team.repository.TeamTaskEventMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.util.List;
import java.util.Set;

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
    private static final Long RUN_ID = 20L;
    private static final Long WORKSPACE_ID = 30L;

    private TeamTaskMapper taskMapper;
    private TeamTaskCommentMapper commentMapper;
    private TeamTaskEventMapper eventMapper;
    private TeamService teamService;
    private TeamRunProjectionScheduler projectionScheduler;
    private TeamRunService runService;
    private TeamTaskService service;

    @BeforeAll
    static void initTableInfo() {
        // Lambda wrappers resolve column names from MyBatis-Plus's static
        // TableInfo cache; in a Spring context this happens during mapper
        // scan, in a plain Mockito test we trigger it manually.
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, TeamTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, TeamTaskCommentEntity.class);
        TableInfoHelper.initTableInfo(assistant, TeamTaskEventEntity.class);
    }

    @BeforeEach
    void setUp() {
        taskMapper = mock(TeamTaskMapper.class);
        commentMapper = mock(TeamTaskCommentMapper.class);
        eventMapper = mock(TeamTaskEventMapper.class);
        teamService = mock(TeamService.class);
        projectionScheduler = mock(TeamRunProjectionScheduler.class);
        runService = mock(TeamRunService.class);
        service = new TeamTaskService(taskMapper, commentMapper, eventMapper, teamService,
                projectionScheduler, runService);

        AgentTeamEntity team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setLeadAgentId(LEAD_ID);
        team.setStatus(TeamService.STATUS_ACTIVE);
        team.setWorkspaceId(WORKSPACE_ID);
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

    private TeamTaskEntity runTask(Long id, String status) {
        TeamTaskEntity task = task(id, status);
        task.setRunId(RUN_ID);
        return task;
    }

    private TeamRunEntity planningRun(Long teamId) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setTeamId(teamId);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setStatus(TeamRunStatus.PLANNING);
        return run;
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

    @Test
    @DisplayName("create copies an optional run id onto the persisted task")
    void createCopiesRunId() {
        when(runService.requireRun(RUN_ID, WORKSPACE_ID)).thenReturn(planningRun(TEAM_ID));
        service.createTask(baseCreate().runId(RUN_ID).build());

        ArgumentCaptor<TeamTaskEntity> captor = ArgumentCaptor.forClass(TeamTaskEntity.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(RUN_ID, captor.getValue().getRunId());
        verify(projectionScheduler).scheduleRun(RUN_ID);
    }

    @Test
    @DisplayName("run-aware task creation requires a planning run in the same team")
    void createRequiresPlanningRunInSameTeam() {
        when(runService.requireRun(RUN_ID, WORKSPACE_ID)).thenReturn(planningRun(999L));
        IllegalArgumentException wrongTeam = assertThrows(IllegalArgumentException.class,
                () -> service.createTask(baseCreate().runId(RUN_ID).build()));
        assertTrue(wrongTeam.getMessage().contains("same team"));

        TeamRunEntity running = planningRun(TEAM_ID);
        running.setStatus(TeamRunStatus.RUNNING);
        when(runService.requireRun(RUN_ID, WORKSPACE_ID)).thenReturn(running);
        IllegalStateException wrongStatus = assertThrows(IllegalStateException.class,
                () -> service.createTask(baseCreate().runId(RUN_ID).build()));
        assertTrue(wrongStatus.getMessage().contains("planning"));
        verify(taskMapper, never()).insert(any(TeamTaskEntity.class));
    }

    @Test
    @DisplayName("blockedBy tasks must belong to the same run")
    void createRequiresBlockersInSameRun() {
        when(runService.requireRun(RUN_ID, WORKSPACE_ID)).thenReturn(planningRun(TEAM_ID));
        when(taskMapper.selectById(99L)).thenReturn(task(99L, TeamTaskStatus.PENDING));

        IllegalArgumentException runTaskWithLegacyBlocker = assertThrows(IllegalArgumentException.class,
                () -> service.createTask(baseCreate().runId(RUN_ID).blockedBy(List.of(99L)).build()));
        assertTrue(runTaskWithLegacyBlocker.getMessage().contains("same run"));

        when(taskMapper.selectById(99L)).thenReturn(runTask(99L, TeamTaskStatus.PENDING));
        IllegalArgumentException legacyTaskWithRunBlocker = assertThrows(IllegalArgumentException.class,
                () -> service.createTask(baseCreate().blockedBy(List.of(99L)).build()));
        assertTrue(legacyTaskWithRunBlocker.getMessage().contains("same run"));
    }

    @Test
    @DisplayName("legacy task creation does not trigger run projection")
    void createLegacyTaskDoesNotProject() {
        service.createTask(baseCreate().build());

        verify(projectionScheduler, never()).scheduleRun(any());
        verify(projectionScheduler, never()).scheduleTask(any());
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
    @DisplayName("a non-assignee cannot auto-claim and complete a pending task")
    void completePendingByNonAssigneeRejected() {
        TeamTaskEntity t = task(5L, TeamTaskStatus.PENDING);
        t.setAssigneeAgentId(MEMBER_ID);
        when(taskMapper.selectById(5L)).thenReturn(t);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeTask(5L, 3L, "hijack"));
        assertTrue(e.getMessage().contains("only the assignee"));
        // Neither the claim nor the completion update may run.
        verify(taskMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("the assignee auto-claims a pending task when completing it directly")
    void completePendingByAssigneeAutoClaims() {
        TeamTaskEntity pending = task(5L, TeamTaskStatus.PENDING);
        pending.setAssigneeAgentId(MEMBER_ID);
        TeamTaskEntity claimed = task(5L, TeamTaskStatus.IN_PROGRESS);
        claimed.setAssigneeAgentId(MEMBER_ID);
        claimed.setOwnerAgentId(MEMBER_ID);
        // First read sees pending, the re-read after the atomic claim sees in_progress.
        when(taskMapper.selectById(5L)).thenReturn(pending).thenReturn(claimed);
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.completeTask(5L, MEMBER_ID, "done").isEmpty());
        // Claim update plus completion update.
        verify(taskMapper, times(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("completing a terminal task fails with a state error")
    void completeTerminalRejected() {
        when(taskMapper.selectById(5L)).thenReturn(task(5L, TeamTaskStatus.CANCELLED));
        when(taskMapper.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.completeTask(5L, MEMBER_ID, "late"));
    }

    @Test
    @DisplayName("successful completion triggers run projection")
    void completeProjectsRun() {
        TeamTaskEntity running = runTask(5L, TeamTaskStatus.IN_PROGRESS);
        running.setOwnerAgentId(MEMBER_ID);
        when(taskMapper.selectById(5L)).thenReturn(running);
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        service.completeTask(5L, MEMBER_ID, "done");

        verify(projectionScheduler).scheduleRun(RUN_ID);
    }

    @Test
    @DisplayName("successful failure triggers run projection")
    void failProjectsRun() {
        when(taskMapper.selectById(5L)).thenReturn(runTask(5L, TeamTaskStatus.IN_PROGRESS));
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        InOrder mutationOrder = inOrder(taskMapper);

        assertTrue(service.failTask(5L, "error"));

        mutationOrder.verify(taskMapper).selectById(5L);
        mutationOrder.verify(taskMapper).update(isNull(), any());
        mutationOrder.verifyNoMoreInteractions();
        verify(projectionScheduler).scheduleTask(5L);
    }

    @Test
    @DisplayName("successful cancellation triggers run projection")
    void cancelProjectsRun() {
        when(taskMapper.selectById(5L)).thenReturn(runTask(5L, TeamTaskStatus.IN_PROGRESS));
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        service.cancelTask(5L, "stop");

        verify(projectionScheduler).scheduleRun(RUN_ID);
    }

    @Test
    @DisplayName("successful retry triggers run projection")
    void retryProjectsRun() {
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectById(5L)).thenReturn(runTask(5L, TeamTaskStatus.PENDING));

        assertTrue(service.retryTask(5L));

        verify(projectionScheduler).scheduleTask(5L);
    }

    @Test
    @DisplayName("claim does not query the task after a successful mutation")
    void claimDoesNotQueryTaskAfterMutation() {
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(service.claimTask(5L, MEMBER_ID));
        verify(taskMapper, never()).selectById(5L);
        verify(projectionScheduler).scheduleTask(5L);
    }

    @Test
    @DisplayName("assign does not query the task after a successful mutation")
    void assignDoesNotQueryTaskAfterMutation() {
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(service.assignTask(5L, MEMBER_ID));
        verify(taskMapper, never()).selectById(5L);
        verify(projectionScheduler).scheduleTask(5L);
    }

    @Test
    @DisplayName("completion succeeds when projection scheduling fails")
    void completeIgnoresProjectionSchedulingFailure() {
        TeamTaskEntity running = runTask(5L, TeamTaskStatus.IN_PROGRESS);
        running.setOwnerAgentId(MEMBER_ID);
        when(taskMapper.selectById(5L)).thenReturn(running);
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectList(any())).thenReturn(List.of());
        doThrow(new IllegalStateException("scheduler unavailable"))
                .when(projectionScheduler).scheduleRun(RUN_ID);

        assertTrue(service.completeTask(5L, MEMBER_ID, "done").isEmpty());
    }

    @Test
    @DisplayName("successful progress update triggers run projection")
    void progressProjectsRun() {
        when(taskMapper.selectById(5L)).thenReturn(runTask(5L, TeamTaskStatus.IN_PROGRESS));
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        InOrder mutationOrder = inOrder(taskMapper);

        assertTrue(service.updateProgress(5L, MEMBER_ID, 50, "halfway"));

        mutationOrder.verify(taskMapper).selectById(5L);
        mutationOrder.verify(taskMapper).update(isNull(), any());
        mutationOrder.verifyNoMoreInteractions();
        verify(projectionScheduler).scheduleTask(5L);
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

    @Test
    @DisplayName("checkpoint evidence note is inserted only when its stable key is absent")
    void addCommentOnceIsIdempotent() {
        when(commentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L, 0L);
        when(taskMapper.selectById(5L)).thenReturn(task(5L, TeamTaskStatus.COMPLETED));

        assertFalse(service.addCommentOnce(5L, TeamTaskService.AUTHOR_SYSTEM, "bridge",
                TeamTaskService.COMMENT_NOTE, "[checkpoint:R001] acknowledged"));
        assertTrue(service.addCommentOnce(5L, TeamTaskService.AUTHOR_SYSTEM, "bridge",
                TeamTaskService.COMMENT_NOTE, "[checkpoint:R002] acknowledged"));

        verify(commentMapper, times(1)).insert(any(TeamTaskCommentEntity.class));
    }

    @Test
    @DisplayName("checkpoint notes are deduplicated by their embedded stable key")
    void checkpointCommentIsSemanticallyIdempotent() {
        TeamTaskEntity tracker = task(5L, TeamTaskStatus.IN_PROGRESS);
        tracker.setSubject("R001-R010 共享跟踪检查点");
        when(taskMapper.selectById(5L)).thenReturn(tracker);
        when(commentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertFalse(service.addComment(5L, TeamTaskService.AUTHOR_AGENT, "2",
                TeamTaskService.COMMENT_NOTE,
                "[运行台账] R001: [checkpoint:R001] acknowledged"));

        verify(commentMapper, never()).insert(any(TeamTaskCommentEntity.class));
        assertEquals("[checkpoint:R001] acknowledged",
                TeamTaskService.checkpointEvidenceKey(
                        "[运行台账] [CHECKPOINT:r001] acknowledged"));
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

    // ==================== deliverables ====================

    @Test
    @DisplayName("addDeliverable appends into metadata JSON and round-trips through listDeliverables")
    void addDeliverableRoundTrip() {
        TeamTaskEntity running = task(5L, TeamTaskStatus.IN_PROGRESS);
        running.setOwnerAgentId(MEMBER_ID);
        when(taskMapper.selectById(5L)).thenReturn(running);

        service.addDeliverable(5L, MEMBER_ID, "report.docx", "/api/v1/files/generated/abc");

        ArgumentCaptor<LambdaUpdateWrapper<TeamTaskEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskMapper).update(isNull(), captor.capture());
        String metadataJson = String.valueOf(captor.getValue().getParamNameValuePairs().values().stream()
                .filter(v -> String.valueOf(v).contains("deliverables")).findFirst().orElse(""));
        assertTrue(metadataJson.contains("report.docx"));

        TeamTaskEntity stored = task(5L, TeamTaskStatus.IN_PROGRESS);
        stored.setMetadata(metadataJson);
        List<TeamTaskService.Deliverable> files = service.listDeliverables(stored);
        assertEquals(1, files.size());
        assertEquals("report.docx", files.get(0).name());
        assertEquals("/api/v1/files/generated/abc", files.get(0).url());
    }

    @Test
    @DisplayName("deliverable guards: external URL, non-owner, terminal task and overflow are rejected")
    void addDeliverableGuards() {
        TeamTaskEntity running = task(5L, TeamTaskStatus.IN_PROGRESS);
        running.setOwnerAgentId(MEMBER_ID);
        when(taskMapper.selectById(5L)).thenReturn(running);

        assertThrows(IllegalArgumentException.class,
                () -> service.addDeliverable(5L, MEMBER_ID, "x", "https://evil.example.com/f.docx"));
        assertThrows(IllegalStateException.class,
                () -> service.addDeliverable(5L, 999L, "x", "/api/v1/files/generated/abc"));

        when(taskMapper.selectById(6L)).thenReturn(task(6L, TeamTaskStatus.COMPLETED));
        assertThrows(IllegalStateException.class,
                () -> service.addDeliverable(6L, MEMBER_ID, "x", "/api/v1/files/generated/abc"));

        TeamTaskEntity full = task(7L, TeamTaskStatus.IN_PROGRESS);
        full.setOwnerAgentId(MEMBER_ID);
        StringBuilder many = new StringBuilder("{\"deliverables\":[");
        for (int i = 0; i < 10; i++) {
            many.append(i > 0 ? "," : "")
                    .append("{\"name\":\"f").append(i).append("\",\"url\":\"/api/v1/files/generated/x\"}");
        }
        full.setMetadata(many.append("]}").toString());
        when(taskMapper.selectById(7L)).thenReturn(full);
        assertThrows(IllegalStateException.class,
                () -> service.addDeliverable(7L, MEMBER_ID, "x", "/api/v1/files/generated/abc"));

        verify(taskMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("absolute generated-file URLs pass validation; listDeliverables tolerates junk metadata")
    void deliverableUrlAndParsingTolerance() {
        TeamTaskEntity running = task(5L, TeamTaskStatus.IN_PROGRESS);
        running.setOwnerAgentId(MEMBER_ID);
        when(taskMapper.selectById(5L)).thenReturn(running);

        service.addDeliverable(5L, MEMBER_ID, "a.xlsx",
                "https://claw.example.com/api/v1/files/generated/xyz");
        verify(taskMapper).update(isNull(), any());

        TeamTaskEntity junk = task(8L, TeamTaskStatus.IN_PROGRESS);
        junk.setMetadata("not-json");
        assertTrue(service.listDeliverables(junk).isEmpty());
        assertTrue(service.listDeliverables(null).isEmpty());
    }

    @Test
    @DisplayName("dispatch candidates exclude planning runs but keep running and legacy tasks")
    void findDispatchableExcludesPlanningRuns() {
        TeamTaskEntity planning = runTask(1L, TeamTaskStatus.PENDING);
        TeamTaskEntity running = task(2L, TeamTaskStatus.PENDING);
        running.setRunId(21L);
        TeamTaskEntity legacy = task(3L, TeamTaskStatus.PENDING);
        when(taskMapper.selectList(any())).thenReturn(List.of(planning, running, legacy));
        when(runService.findPlanningRunIds(Set.of(RUN_ID, 21L))).thenReturn(Set.of(RUN_ID));

        assertEquals(List.of(running, legacy), service.findDispatchable(TEAM_ID));
        verify(runService).findPlanningRunIds(Set.of(RUN_ID, 21L));
    }

    @Test
    @DisplayName("run task lookup is scoped by run id")
    void listTasksByRunScopesQuery() {
        TeamTaskEntity first = runTask(1L, TeamTaskStatus.PENDING);
        when(taskMapper.selectList(any())).thenReturn(List.of(first));

        assertEquals(List.of(first), service.listTasksByRun(RUN_ID));

        ArgumentCaptor<LambdaQueryWrapper<TeamTaskEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectList(query.capture());
        query.getValue().getSqlSegment();
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(RUN_ID));
    }

    @Test
    @DisplayName("checkpoint range detection ignores the injected overall plan context")
    void checkpointTerminalTagUsesOnlyLocalTaskText() {
        TeamTaskEntity tracker = task(1L, TeamTaskStatus.IN_PROGRESS);
        tracker.setSubject("R001-R300 唯一共享跟踪条目");
        tracker.setDescription("每轮登记证据，R300 前保持进行中\n\n[Plan context]\nOverall request");
        assertEquals("R300", service.checkpointTerminalTag(tracker));

        TeamTaskEntity ordinary = task(2L, TeamTaskStatus.IN_PROGRESS);
        ordinary.setSubject("设计稳定性指标");
        ordinary.setDescription("产出指标清单\n\n[Plan context]\nOverall request: R001-R300 共享跟踪");
        assertNull(service.checkpointTerminalTag(ordinary));
    }
}
