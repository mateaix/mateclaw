package vip.mate.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamRunCreateCommand;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.repository.TeamRunMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class TeamRunServiceTest {

    private static final Long RUN_ID = 20L;
    private static final Long TEAM_ID = 10L;
    private static final Long WORKSPACE_ID = 30L;
    private static final Long LEAD_ID = 40L;

    private TeamRunMapper runMapper;
    private TeamTaskMapper taskMapper;
    private TeamService teamService;
    private TeamRunService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, TeamRunEntity.class);
        TableInfoHelper.initTableInfo(assistant, TeamTaskEntity.class);
    }

    @BeforeEach
    void setUp() {
        runMapper = mock(TeamRunMapper.class);
        taskMapper = mock(TeamTaskMapper.class);
        teamService = mock(TeamService.class);
        service = new TeamRunService(runMapper, taskMapper, teamService);
        when(teamService.getTeam(TEAM_ID)).thenReturn(team(TeamService.STATUS_ACTIVE, WORKSPACE_ID, LEAD_ID));
    }

    @Test
    void startRunValidatesActiveTeamWorkspaceAndLead() {
        when(teamService.getTeam(TEAM_ID)).thenReturn(team(TeamService.STATUS_PAUSED, WORKSPACE_ID, LEAD_ID));
        assertThrows(IllegalArgumentException.class, () -> service.startRun(command().build()));

        when(teamService.getTeam(TEAM_ID)).thenReturn(team(TeamService.STATUS_ACTIVE, 999L, LEAD_ID));
        assertThrows(IllegalArgumentException.class, () -> service.startRun(command().build()));

        when(teamService.getTeam(TEAM_ID)).thenReturn(team(TeamService.STATUS_ACTIVE, WORKSPACE_ID, 999L));
        assertThrows(IllegalArgumentException.class, () -> service.startRun(command().build()));

        verify(runMapper, never()).insert(any(TeamRunEntity.class));
    }

    @Test
    void startRunValidatesConversationAndObjective() {
        assertThrows(IllegalArgumentException.class,
                () -> service.startRun(command().leadConversationId(" ").build()));
        assertThrows(IllegalArgumentException.class,
                () -> service.startRun(command().objective(null).build()));
    }

    @Test
    void startRunCreatesPlanningRunAndDerivesBoundedTitle() {
        String objective = "x".repeat(300);
        TeamRunCreateCommand command = command().title(" ").objective(objective).build();

        TeamRunEntity created = service.startRun(command);

        assertEquals(TeamRunStatus.PLANNING, created.getStatus());
        assertEquals(255, created.getTitle().length());
        assertTrue(objective.startsWith(created.getTitle()));
        assertEquals(WORKSPACE_ID, created.getWorkspaceId());
        assertEquals(LEAD_ID, created.getLeadAgentId());
        verify(runMapper).insert(created);
    }

    @Test
    void startRunReturnsExistingIdempotentRun() {
        TeamRunEntity existing = run(TeamRunStatus.RUNNING);
        when(runMapper.selectOne(any())).thenReturn(existing);

        assertSame(existing, service.startRun(command().build()));

        verify(runMapper, never()).insert(any(TeamRunEntity.class));
    }

    @Test
    void startRunScopesIdempotencyByWorkspaceAndDoesNotOwnATransaction() throws Exception {
        TeamRunEntity existing = run(TeamRunStatus.RUNNING);
        when(runMapper.selectOne(any())).thenReturn(existing);

        service.startRun(command().build());

        ArgumentCaptor<LambdaQueryWrapper<TeamRunEntity>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runMapper).selectOne(query.capture());
        query.getValue().getSqlSegment();
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(WORKSPACE_ID));
        assertFalse(TeamRunService.class
                .getDeclaredMethod("startRun", TeamRunCreateCommand.class)
                .isAnnotationPresent(Transactional.class));
    }

    @Test
    void startRunRecoversDuplicateKeyRaceByReadingWinner() {
        TeamRunEntity winner = run(TeamRunStatus.PLANNING);
        when(runMapper.selectOne(any())).thenReturn(null, winner);
        when(runMapper.insert(any(TeamRunEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate origin"));

        assertSame(winner, service.startRun(command().build()));
    }

    @Test
    void requireRunRejectsCrossWorkspaceAccess() {
        TeamRunEntity foreign = run(TeamRunStatus.RUNNING);
        foreign.setWorkspaceId(999L);
        when(runMapper.selectById(RUN_ID)).thenReturn(foreign);

        assertThrows(IllegalArgumentException.class, () -> service.requireRun(RUN_ID, WORKSPACE_ID));
    }

    @Test
    void sealRunRejectsEmptyPlanningRun() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(TeamRunStatus.PLANNING));
        when(taskMapper.selectCount(any())).thenReturn(0L);

        assertThrows(IllegalStateException.class, () -> service.sealRun(RUN_ID, WORKSPACE_ID));

        verify(runMapper, never()).update(isNull(), any());
    }

    @Test
    void sealRunStartsPopulatedPlanningRun() {
        TeamRunEntity planning = run(TeamRunStatus.PLANNING);
        when(runMapper.selectById(RUN_ID)).thenReturn(planning);
        when(taskMapper.selectCount(any())).thenReturn(2L);
        when(runMapper.update(isNull(), any())).thenReturn(1);

        TeamRunEntity sealed = service.sealRun(RUN_ID, WORKSPACE_ID);

        assertEquals(TeamRunStatus.RUNNING, sealed.getStatus());
        assertNotNull(sealed.getStartedAt());
        verify(runMapper).update(isNull(), any());
    }

    @Test
    void sealRunWithResultReportsFirstTransition() {
        TeamRunEntity planning = run(TeamRunStatus.PLANNING);
        when(runMapper.selectById(RUN_ID)).thenReturn(planning);
        when(taskMapper.selectCount(any())).thenReturn(2L);
        when(runMapper.update(isNull(), any())).thenReturn(1);

        TeamRunService.SealResult result = service.sealRunWithResult(RUN_ID, WORKSPACE_ID);

        assertSame(planning, result.run());
        assertTrue(result.transitioned());
        assertEquals(TeamRunStatus.RUNNING, result.run().getStatus());
    }

    @Test
    void sealRunWithResultReportsRepeatedSealWithoutTransition() {
        TeamRunEntity running = run(TeamRunStatus.RUNNING);
        when(runMapper.selectById(RUN_ID)).thenReturn(running);

        TeamRunService.SealResult result = service.sealRunWithResult(RUN_ID, WORKSPACE_ID);

        assertSame(running, result.run());
        assertFalse(result.transitioned());
        verify(runMapper, never()).update(isNull(), any());
    }

    @Test
    void sealRunWithResultReportsConcurrentWinnerWithoutTransition() {
        TeamRunEntity planning = run(TeamRunStatus.PLANNING);
        TeamRunEntity winner = run(TeamRunStatus.RUNNING);
        when(runMapper.selectById(RUN_ID)).thenReturn(planning, winner);
        when(taskMapper.selectCount(any())).thenReturn(2L);
        when(runMapper.update(isNull(), any())).thenReturn(0);

        TeamRunService.SealResult result = service.sealRunWithResult(RUN_ID, WORKSPACE_ID);

        assertSame(winner, result.run());
        assertFalse(result.transitioned());
    }

    @Test
    void sealRunReturnsRunsThatAlreadyLeftPlanning() {
        for (String status : List.of(TeamRunStatus.RUNNING, TeamRunStatus.FINALIZING,
                TeamRunStatus.COMPLETED, TeamRunStatus.CANCELLED)) {
            TeamRunEntity current = run(status);
            when(runMapper.selectById(RUN_ID)).thenReturn(current);

            assertSame(current, service.sealRun(RUN_ID, WORKSPACE_ID));
        }

        verify(taskMapper, never()).selectCount(any());
        verify(runMapper, never()).update(isNull(), any());
    }

    @Test
    void cancelRunWithResultReportsOnlyTheFirstTransition() {
        TeamRunEntity running = run(TeamRunStatus.RUNNING);
        when(runMapper.selectById(RUN_ID)).thenReturn(running);
        when(runMapper.update(isNull(), any())).thenReturn(1);

        TeamRunService.CancelResult result = service.cancelRunWithResult(
                RUN_ID, WORKSPACE_ID, "stop");

        assertTrue(result.transitioned());
        assertEquals(TeamRunStatus.CANCELLED, result.run().getStatus());
        assertEquals("stop", result.run().getStopReason());
    }

    @Test
    void cancelRunWithResultIsIdempotentAfterCancellation() {
        TeamRunEntity cancelled = run(TeamRunStatus.CANCELLED);
        when(runMapper.selectById(RUN_ID)).thenReturn(cancelled);

        TeamRunService.CancelResult result = service.cancelRunWithResult(
                RUN_ID, WORKSPACE_ID, null);

        assertFalse(result.transitioned());
        assertSame(cancelled, result.run());
        verify(runMapper, never()).update(isNull(), any());
    }

    @Test
    void cancelRunWithResultReportsConcurrentWinnerWithoutTransition() {
        TeamRunEntity running = run(TeamRunStatus.RUNNING);
        TeamRunEntity winner = run(TeamRunStatus.CANCELLED);
        when(runMapper.selectById(RUN_ID)).thenReturn(running, winner);
        when(runMapper.update(isNull(), any())).thenReturn(0);

        TeamRunService.CancelResult result = service.cancelRunWithResult(
                RUN_ID, WORKSPACE_ID, null);

        assertFalse(result.transitioned());
        assertSame(winner, result.run());
    }

    @Test
    void markFinalizedUsesProjectedOutcomeAndWritesSummary() {
        TeamRunEntity finalizing = run(TeamRunStatus.FINALIZING);
        finalizing.setMetadata("{\"traceId\":\"abc\",\"projectedOutcome\":\"partial\"}");
        when(runMapper.selectById(RUN_ID)).thenReturn(finalizing);
        when(runMapper.update(isNull(), any())).thenReturn(1);

        TeamRunEntity finalized = service.markFinalized(RUN_ID, WORKSPACE_ID, "usable result");

        assertEquals(TeamRunStatus.PARTIAL, finalized.getStatus());
        assertEquals("usable result", finalized.getFinalSummary());
        assertNotNull(finalized.getCompletedAt());
    }

    @Test
    void markFinalizedRejectsInvalidOutcome() {
        TeamRunEntity finalizing = run(TeamRunStatus.FINALIZING);
        finalizing.setMetadata("{\"projectedOutcome\":\"running\"}");
        when(runMapper.selectById(RUN_ID)).thenReturn(finalizing);

        assertThrows(IllegalStateException.class,
                () -> service.markFinalized(RUN_ID, WORKSPACE_ID, "summary"));
    }

    @Test
    void markFinalizedReturnsAlreadyTerminalRuns() {
        for (String status : Set.of(TeamRunStatus.COMPLETED, TeamRunStatus.PARTIAL,
                TeamRunStatus.FAILED, TeamRunStatus.CANCELLED)) {
            TeamRunEntity current = run(status);
            when(runMapper.selectById(RUN_ID)).thenReturn(current);

            assertSame(current, service.markFinalized(RUN_ID, WORKSPACE_ID, "summary"));
        }

        verify(runMapper, never()).update(isNull(), any());
    }

    @Test
    void reconcileMarksRawTaskSummaryAsFallbackQuality() {
        TeamRunEntity finalizing = run(TeamRunStatus.FINALIZING);
        finalizing.setMetadata("{\"traceId\":\"abc\"}");
        TeamTaskEntity completed = task(101L, TeamTaskStatus.COMPLETED);
        completed.setTaskNumber(1);
        completed.setResult("raw result");
        when(runMapper.selectList(any())).thenReturn(List.of(finalizing));
        when(taskMapper.selectList(any())).thenReturn(List.of(completed));

        service.reconcileFinalizingRuns();

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<TeamRunEntity>> update =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(runMapper).update(isNull(), update.capture());
        assertTrue(update.getValue().getParamNameValuePairs().values().stream()
                .map(String::valueOf)
                .anyMatch(value -> value.contains("summaryQuality") && value.contains("fallback")));
    }

    @Test
    void getRunBuildsStableViewWithTasksAndProgress() {
        TeamRunEntity running = run(TeamRunStatus.RUNNING);
        TeamTaskEntity completed = task(1L, TeamTaskStatus.COMPLETED);
        TeamTaskEntity pending = task(2L, TeamTaskStatus.PENDING);
        when(runMapper.selectById(RUN_ID)).thenReturn(running);
        when(taskMapper.selectList(any())).thenReturn(List.of(completed, pending));

        var view = service.getRun(RUN_ID, WORKSPACE_ID);

        assertEquals(RUN_ID, view.id());
        assertEquals(2, view.tasks().size());
        assertEquals(50, view.progress().percent());
    }

    @Test
    void pagedTeamRunListUsesOneBoundedTaskSummaryQueryWithoutTaskResults() {
        TeamRunEntity newest = run(TeamRunStatus.COMPLETED);
        newest.setId(22L);
        newest.setCreateTime(LocalDateTime.of(2026, 8, 14, 12, 0));
        TeamRunEntity older = run(TeamRunStatus.RUNNING);
        older.setId(21L);
        older.setCreateTime(LocalDateTime.of(2026, 8, 14, 11, 0));
        TeamRunEntity lookahead = run(TeamRunStatus.RUNNING);
        lookahead.setId(20L);
        lookahead.setCreateTime(LocalDateTime.of(2026, 8, 14, 10, 0));
        TeamTaskEntity summary = task(101L, TeamTaskStatus.COMPLETED);
        summary.setRunId(22L);
        summary.setSubject("Summary task");
        summary.setDescription("must not be returned");
        summary.setConversationId("worker-101");
        summary.setProgressPercent(100);
        summary.setProgressStep("done");
        summary.setResult("must not be selected or returned");
        when(runMapper.selectList(any())).thenReturn(List.of(newest, older, lookahead));
        when(taskMapper.selectList(any())).thenReturn(List.of(summary));

        TeamRunService.RunPage page = service.pageTeamRuns(TEAM_ID, WORKSPACE_ID, false, null, 2);

        assertEquals(2, page.items().size());
        assertNotNull(page.nextCursor());
        assertTrue(page.items().stream().allMatch(view -> "summary".equals(view.projectionCompleteness())));
        assertEquals(1, page.items().getFirst().tasks().size());
        var lightweight = page.items().getFirst().tasks().getFirst();
        assertEquals(101L, lightweight.id());
        assertEquals(22L, lightweight.runId());
        assertEquals("worker-101", lightweight.conversationId());
        assertEquals("Summary task", lightweight.subject());
        assertEquals(100, lightweight.progressPercent());
        assertEquals("done", lightweight.progressStep());
        assertNull(lightweight.description());
        assertNull(lightweight.result());
        verify(taskMapper, times(1)).selectList(any());
    }

    @Test
    void lightweightListAggregatesRealDeliverableAndTaskCounts() {
        TeamRunEntity run = run(TeamRunStatus.COMPLETED);
        run.setCreateTime(LocalDateTime.now());
        TeamTaskEntity summary = task(101L, TeamTaskStatus.COMPLETED);
        summary.setMetadata("{\"deliverables\":[{\"name\":\"report\","
                + "\"url\":\"/api/v1/files/generated/report.pdf\"}]}");
        when(runMapper.selectList(any())).thenReturn(List.of(run));
        when(taskMapper.selectList(any())).thenReturn(List.of(summary));

        TeamRunService.RunPage page = service.pageTeamRuns(TEAM_ID, WORKSPACE_ID, false, null, 20);

        assertEquals(1, page.items().getFirst().metrics().deliverableCount());
        assertEquals(1, page.items().getFirst().metrics().totalTasks());
    }

    @Test
    void legacyArrayListIsNotTruncatedByPageLimit() {
        List<TeamRunEntity> runs = java.util.stream.LongStream.rangeClosed(1, 101)
                .mapToObj(id -> {
                    TeamRunEntity run = run(TeamRunStatus.COMPLETED);
                    run.setId(id);
                    run.setCreateTime(LocalDateTime.now());
                    return run;
                }).toList();
        when(runMapper.selectList(any())).thenReturn(runs);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        assertEquals(101, service.listTeamRuns(TEAM_ID, WORKSPACE_ID, false).size());
    }

    @Test
    void legacyArraySummaryLoadsTasksInSafeBatches() {
        List<TeamRunEntity> runs = java.util.stream.LongStream.rangeClosed(1, 1201)
                .mapToObj(id -> {
                    TeamRunEntity run = run(TeamRunStatus.COMPLETED);
                    run.setId(id);
                    run.setCreateTime(LocalDateTime.now());
                    return run;
                }).toList();
        when(runMapper.selectList(any())).thenReturn(runs);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        assertEquals(1201, service.listTeamRuns(TEAM_ID, WORKSPACE_ID, false).size());
        verify(taskMapper, times(3)).selectList(any());
    }

    @Test
    void cursorPaginationIsStableForRunsWithTheSameCreateTime() {
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 14, 12, 0);
        TeamRunEntity firstRun = run(TeamRunStatus.COMPLETED);
        firstRun.setId(40L);
        firstRun.setCreateTime(sameTime);
        TeamRunEntity secondRun = run(TeamRunStatus.COMPLETED);
        secondRun.setId(39L);
        secondRun.setCreateTime(sameTime);
        when(runMapper.selectList(any())).thenReturn(List.of(firstRun, secondRun), List.of(secondRun));
        when(taskMapper.selectList(any())).thenReturn(List.of());

        TeamRunService.RunPage first = service.pageTeamRuns(TEAM_ID, WORKSPACE_ID, false, null, 1);
        TeamRunService.RunPage second = service.pageTeamRuns(
                TEAM_ID, WORKSPACE_ID, false, first.nextCursor(), 1);

        assertEquals(40L, first.items().getFirst().id());
        assertEquals(39L, second.items().getFirst().id());
        assertFalse(first.nextCursor().isBlank());
        verify(runMapper, times(2)).selectList(any());
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<TeamRunEntity>> queries =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(runMapper, times(2)).selectList(queries.capture());
        LambdaQueryWrapper<TeamRunEntity> secondQuery = queries.getAllValues().get(1);
        String sql = secondQuery.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("create_time") || sql.contains("createtime"), sql);
        assertTrue(sql.contains("id"));
        assertTrue(secondQuery.getParamNameValuePairs().containsValue(sameTime));
        assertTrue(secondQuery.getParamNameValuePairs().containsValue(40L));
    }

    @Test
    void invalidCursorIsRejectedBeforeQuerying() {
        assertThrows(IllegalArgumentException.class,
                () -> service.pageTeamRuns(TEAM_ID, WORKSPACE_ID, false, "not-a-cursor", 20));
        verify(runMapper, never()).selectList(any());
    }

    @Test
    void detailStillReturnsLongTaskResultWhileListSummaryDoesNot() {
        TeamRunEntity run = run(TeamRunStatus.COMPLETED);
        TeamTaskEntity task = task(101L, TeamTaskStatus.COMPLETED);
        task.setResult("full markdown result");
        when(runMapper.selectById(RUN_ID)).thenReturn(run);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        var detail = service.getRun(RUN_ID, WORKSPACE_ID);

        assertEquals("full markdown result", detail.tasks().getFirst().result());
    }

    private TeamRunCreateCommand.TeamRunCreateCommandBuilder command() {
        return TeamRunCreateCommand.builder()
                .teamId(TEAM_ID)
                .workspaceId(WORKSPACE_ID)
                .leadAgentId(LEAD_ID)
                .leadConversationId("conversation")
                .originMessageId(50L)
                .title("Research")
                .objective("Research the topic");
    }

    private AgentTeamEntity team(String status, Long workspaceId, Long leadId) {
        AgentTeamEntity team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setStatus(status);
        team.setWorkspaceId(workspaceId);
        team.setLeadAgentId(leadId);
        return team;
    }

    private TeamRunEntity run(String status) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setTeamId(TEAM_ID);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setLeadAgentId(LEAD_ID);
        run.setLeadConversationId("conversation");
        run.setTitle("Research");
        run.setObjective("Research the topic");
        run.setStatus(status);
        return run;
    }

    private TeamTaskEntity task(Long id, String status) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(id);
        task.setTeamId(TEAM_ID);
        task.setRunId(RUN_ID);
        task.setStatus(status);
        return task;
    }
}
