package vip.mate.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Pins the dispatch loop's arbitration rules: one task per member per sweep,
 * busy members skipped, the conditional assign as the only winner gate, the
 * circuit breaker short-circuit, and outcome settling (auto-complete vs
 * respecting a state the member already set).
 */
class TeamDispatchServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long MEMBER_A = 2L;
    private static final Long MEMBER_B = 3L;

    private TeamService teamService;
    private TeamTaskService taskService;
    private AgentService agentService;
    private ConversationService conversationService;
    private ChatStreamTracker streamTracker;
    private TeamAnnounceService announceService;
    private TeamDispatchService service;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        taskService = mock(TeamTaskService.class);
        agentService = mock(AgentService.class);
        conversationService = mock(ConversationService.class);
        streamTracker = mock(ChatStreamTracker.class);
        announceService = mock(TeamAnnounceService.class);
        service = new TeamDispatchService(teamService, taskService, agentService,
                conversationService, streamTracker, announceService);
    }

    private TeamTaskEntity task(Long id, Long assignee) {
        TeamTaskEntity t = new TeamTaskEntity();
        t.setId(id);
        t.setTeamId(TEAM_ID);
        t.setTaskNumber(id.intValue());
        t.setSubject("task " + id);
        t.setStatus(TeamTaskStatus.PENDING);
        t.setAssigneeAgentId(assignee);
        t.setLeadConversationId("lead-conv");
        return t;
    }

    // ==================== sweep arbitration ====================

    @Test
    @DisplayName("one task per assignee per sweep — the second task for the same member waits")
    void onePerAssigneePerSweep() {
        TeamTaskEntity first = task(1L, MEMBER_A);
        TeamTaskEntity second = task(2L, MEMBER_A);
        TeamTaskEntity other = task(3L, MEMBER_B);
        // Second stub is empty so the async post-run re-sweep chain terminates.
        when(taskService.findDispatchable(TEAM_ID))
                .thenReturn(List.of(first, second, other))
                .thenReturn(List.of());
        when(taskService.hasActiveTask(eq(TEAM_ID), any())).thenReturn(false);
        when(taskService.assignTask(any(), any())).thenReturn(true);
        when(taskService.tryAcquireDispatch(any())).thenReturn(true);
        when(taskService.getTask(any())).thenAnswer(inv ->
                task(inv.getArgument(0), MEMBER_A));

        service.sweep(TEAM_ID);

        verify(taskService).assignTask(1L, MEMBER_A);
        verify(taskService, never()).assignTask(eq(2L), any());
        verify(taskService).assignTask(3L, MEMBER_B);
    }

    @Test
    @DisplayName("a member already executing a task is skipped entirely")
    void busyMemberSkipped() {
        when(taskService.findDispatchable(TEAM_ID)).thenReturn(List.of(task(1L, MEMBER_A)));
        when(taskService.hasActiveTask(TEAM_ID, MEMBER_A)).thenReturn(true);

        service.sweep(TEAM_ID);

        verify(taskService, never()).assignTask(any(), any());
    }

    @Test
    @DisplayName("losing the conditional assign means another sweep won — no dispatch")
    void assignRaceLostSkips() {
        when(taskService.findDispatchable(TEAM_ID)).thenReturn(List.of(task(1L, MEMBER_A)));
        when(taskService.hasActiveTask(TEAM_ID, MEMBER_A)).thenReturn(false);
        when(taskService.assignTask(1L, MEMBER_A)).thenReturn(false);

        service.sweep(TEAM_ID);

        verify(taskService, never()).tryAcquireDispatch(any());
    }

    @Test
    @DisplayName("a tripped circuit breaker skips the run but announces the auto-fail to the lead")
    void breakerStopsDispatch() {
        when(taskService.findDispatchable(TEAM_ID)).thenReturn(List.of(task(1L, MEMBER_A)));
        when(taskService.hasActiveTask(TEAM_ID, MEMBER_A)).thenReturn(false);
        when(taskService.assignTask(1L, MEMBER_A)).thenReturn(true);
        when(taskService.tryAcquireDispatch(1L)).thenReturn(false);
        TeamTaskEntity failed = task(1L, MEMBER_A);
        failed.setStatus(TeamTaskStatus.FAILED);
        when(taskService.getTask(1L)).thenReturn(failed);

        service.sweep(TEAM_ID);

        // The work must not vanish silently: the lead hears about the auto-fail.
        verify(announceService).announceTaskSettled(failed);
        verifyNoInteractions(agentService);
    }

    // ==================== outcome settling ====================

    @Test
    @DisplayName("an in_progress task is auto-completed with the member's final reply")
    void settleAutoCompletes() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        TeamTaskEntity done = task(1L, MEMBER_A);
        done.setStatus(TeamTaskStatus.COMPLETED);
        done.setResult("analysis finished");
        when(taskService.getTask(1L)).thenReturn(running, done);
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());

        service.settleOutcome(running, "analysis finished");

        verify(taskService).completeTask(1L, null, "analysis finished");
        verify(streamTracker).broadcastObject(eq("lead-conv"), eq("team_task_completed"), any());
        verify(announceService).announceTaskSettled(done);
    }

    @Test
    @DisplayName("a task the member already failed via blocker is not completed on top")
    void settleRespectsMemberFailure() {
        TeamTaskEntity failed = task(1L, MEMBER_A);
        failed.setStatus(TeamTaskStatus.FAILED);
        failed.setReason("blocked: missing docs");
        when(taskService.getTask(1L)).thenReturn(failed);

        service.settleOutcome(failed, "irrelevant reply");

        verify(taskService, never()).completeTask(any(), any(), anyString());
        verify(streamTracker).broadcastObject(eq("lead-conv"), eq("team_task_failed"), any());
    }

    @Test
    @DisplayName("an oversized member reply is truncated before persisting")
    void settleTruncatesLongReply() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        when(taskService.getTask(1L)).thenReturn(running, running);
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());

        service.settleOutcome(running, "x".repeat(TeamDispatchService.MAX_RESULT_CHARS + 500));

        verify(taskService).completeTask(eq(1L), isNull(), argThat(r ->
                r.length() <= TeamDispatchService.MAX_RESULT_CHARS + 20
                        && r.endsWith("...(truncated)")));
    }
}
