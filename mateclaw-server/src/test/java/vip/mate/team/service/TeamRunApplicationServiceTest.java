package vip.mate.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.team.event.TeamRunCancelCommittedIntent;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TeamRunApplicationServiceTest {

    private static final Long RUN_ID = 20L;
    private static final Long WORKSPACE_ID = 30L;

    private TeamRunService runService;
    private TeamTaskService taskService;
    private ApplicationEventPublisher events;
    private TeamRunApplicationService service;

    @BeforeEach
    void setUp() {
        runService = mock(TeamRunService.class);
        taskService = mock(TeamTaskService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TeamRunApplicationService(runService, taskService, events);
    }

    @Test
    void firstCancellationCancelsActiveTasksAndPublishesDetachedIntentOnce() {
        TeamRunEntity cancelled = run(TeamRunStatus.CANCELLED);
        TeamTaskEntity pending = task(1L, TeamTaskStatus.PENDING, null);
        TeamTaskEntity running = task(2L, TeamTaskStatus.IN_PROGRESS, "worker-conversation");
        TeamTaskEntity completed = task(3L, TeamTaskStatus.COMPLETED, "old-conversation");
        TeamRunView view = mock(TeamRunView.class);
        when(runService.cancelRunWithResult(RUN_ID, WORKSPACE_ID, "stop"))
                .thenReturn(new TeamRunService.CancelResult(cancelled, true));
        when(taskService.listTasksByRun(RUN_ID)).thenReturn(List.of(pending, running, completed));
        when(runService.buildView(cancelled)).thenReturn(view);

        TeamRunView result = service.cancelRun(RUN_ID, WORKSPACE_ID, "stop");

        assertSame(view, result);
        verify(taskService).cancelTask(1L, "stop");
        verify(taskService).cancelTask(2L, "stop");
        verify(taskService, never()).cancelTask(3L, "stop");
        ArgumentCaptor<TeamRunCancelCommittedIntent> intent =
                ArgumentCaptor.forClass(TeamRunCancelCommittedIntent.class);
        verify(events).publishEvent(intent.capture());
        assertSame(view, intent.getValue().run());
        assertEquals(List.of(new TeamRunCancelCommittedIntent.WorkerTask(
                2L, null, "worker-conversation")), intent.getValue().workers());
    }

    @Test
    void repeatedCancellationHasNoTaskOrEventSideEffects() {
        TeamRunEntity cancelled = run(TeamRunStatus.CANCELLED);
        TeamRunView view = mock(TeamRunView.class);
        when(runService.cancelRunWithResult(RUN_ID, WORKSPACE_ID, null))
                .thenReturn(new TeamRunService.CancelResult(cancelled, false));
        when(runService.buildView(cancelled)).thenReturn(view);

        assertSame(view, service.cancelRun(RUN_ID, WORKSPACE_ID, null));

        verify(taskService, never()).listTasksByRun(RUN_ID);
        verifyNoInteractions(events);
    }

    private static TeamRunEntity run(String status) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setStatus(status);
        return run;
    }

    private static TeamTaskEntity task(Long id, String status, String conversationId) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(id);
        task.setRunId(RUN_ID);
        task.setStatus(status);
        task.setConversationId(conversationId);
        return task;
    }
}
