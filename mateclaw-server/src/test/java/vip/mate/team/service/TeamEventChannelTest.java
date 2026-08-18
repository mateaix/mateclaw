package vip.mate.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TeamEventChannelTest {

    private static final Long RUN_ID = 9007199254740993L;
    private static final Long TEAM_ID = 9007199254740995L;
    private static final String LEAD_CONVERSATION_ID = "lead-conversation";

    private ChatStreamTracker streamTracker;
    private TeamEventChannel channel;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        channel = new TeamEventChannel(streamTracker);
    }

    @Test
    void taskEventIncludesStringRunIdWhenPresent() {
        TeamTaskEntity task = task();
        task.setRunId(RUN_ID);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);

        channel.publishTaskEvent(task, "team_task_created", Map.of("status", "pending"));

        verify(streamTracker).broadcastObject(
                eq("team-events-" + TEAM_ID), eq("team_task_created"), payload.capture());
        Map<?, ?> data = (Map<?, ?>) payload.getValue();
        assertEquals(String.valueOf(RUN_ID), data.get("runId"));
        assertEquals(String.valueOf(TEAM_ID), data.get("teamId"));
    }

    @Test
    void legacyTaskEventOmitsRunId() {
        TeamTaskEntity task = task();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);

        channel.publishTaskEvent(task, "team_task_created", Map.of());

        verify(streamTracker).broadcastObject(
                eq("team-events-" + TEAM_ID), eq("team_task_created"), payload.capture());
        assertFalse(((Map<?, ?>) payload.getValue()).containsKey("runId"));
    }

    @Test
    void runEventPublishesStringIdsAndProgressToTeamAndLeadConversation() {
        TeamRunView run = run();
        ArgumentCaptor<Object> teamPayload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> leadPayload = ArgumentCaptor.forClass(Object.class);

        channel.publishRunEvent(run, "team_run_cancelled", Map.of(
                "reason", "stop",
                "taskId", 9007199254740997L,
                "blockedBy", List.of(9007199254740999L)));

        verify(streamTracker).register("team-events-" + TEAM_ID);
        verify(streamTracker).broadcastObject(
                eq("team-events-" + TEAM_ID), eq("team_run_cancelled"), teamPayload.capture());
        verify(streamTracker).broadcastObject(
                eq(LEAD_CONVERSATION_ID), eq("team_run_cancelled"), leadPayload.capture());
        Map<?, ?> data = (Map<?, ?>) teamPayload.getValue();
        assertEquals(data, leadPayload.getValue());
        assertEquals(String.valueOf(RUN_ID), data.get("runId"));
        assertEquals(String.valueOf(TEAM_ID), data.get("teamId"));
        assertEquals(LEAD_CONVERSATION_ID, data.get("leadConversationId"));
        assertEquals(TeamRunStatus.CANCELLED, data.get("status"));
        assertEquals(run.progress(), data.get("progress"));
        assertEquals("stop", data.get("reason"));
        assertEquals("9007199254740997", data.get("taskId"));
        assertEquals(List.of("9007199254740999"), data.get("blockedBy"));
    }

    private static TeamTaskEntity task() {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(101L);
        task.setTeamId(TEAM_ID);
        task.setTaskNumber(1);
        task.setSubject("Task");
        task.setAssigneeAgentId(2L);
        task.setLeadConversationId(LEAD_CONVERSATION_ID);
        return task;
    }

    private static TeamRunView run() {
        return new TeamRunView(RUN_ID, TEAM_ID, 30L, 1L, LEAD_CONVERSATION_ID,
                null, "Run", "Objective", TeamRunStatus.CANCELLED, null, "stop", null,
                null, null, null, null,
                new TeamRunView.Progress(2, 0, 0, 0, 0), List.of());
    }
}
