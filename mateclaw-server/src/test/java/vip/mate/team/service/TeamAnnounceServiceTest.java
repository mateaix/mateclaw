package vip.mate.team.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.agent.runtime.RunningConversationRegistry;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Pins the announce contract: settled results are batched per lead conversation
 * and run and delivered as ONE merged wake-up message; a busy lead defers
 * delivery instead of risking an in-turn drop; the merged text carries the
 * synthesis / retry instructions the lead acts on.
 */
class TeamAnnounceServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long LEAD_ID = 1L;
    private static final Long RUN_ID = 90L;
    private static final String LEAD_CONV = "lead-conv";

    private TeamService teamService;
    private TeamTaskService taskService;
    private AgentService agentService;
    private AgentMapper agentMapper;
    private RunningConversationRegistry runningConversations;
    private ChatStreamTracker streamTracker;
    private ConversationService conversationService;
    private TeamAnnounceService service;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        taskService = mock(TeamTaskService.class);
        agentService = mock(AgentService.class);
        agentMapper = mock(AgentMapper.class);
        runningConversations = mock(RunningConversationRegistry.class);
        streamTracker = mock(ChatStreamTracker.class);
        conversationService = mock(ConversationService.class);
        service = new TeamAnnounceService(teamService, taskService, agentService, agentMapper,
                runningConversations, streamTracker, conversationService);

        AgentTeamEntity team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setLeadAgentId(LEAD_ID);
        when(teamService.getTeam(TEAM_ID)).thenReturn(team);

        AgentEntity member = new AgentEntity();
        member.setName("写手");
        when(agentMapper.selectById(any())).thenReturn(member);
    }

    private TeamTaskEntity settled(Long id, String status, String detail) {
        TeamTaskEntity t = new TeamTaskEntity();
        t.setId(id);
        t.setTeamId(TEAM_ID);
        t.setRunId(RUN_ID);
        t.setTaskNumber(id.intValue());
        t.setSubject("task " + id);
        t.setStatus(status);
        t.setAssigneeAgentId(2L);
        t.setLeadConversationId(LEAD_CONV);
        if (TeamTaskStatus.FAILED.equals(status)) {
            t.setReason(detail);
        } else {
            t.setResult(detail);
        }
        return t;
    }

    @Test
    @DisplayName("results settling together wake the lead ONCE with a merged message")
    void batchedResultsSingleWakeUp() {
        when(runningConversations.isActive(LEAD_CONV)).thenReturn(false);
        service.announceTaskSettled(settled(1L, TeamTaskStatus.COMPLETED, "report done"));
        service.announceTaskSettled(settled(2L, TeamTaskStatus.FAILED, "blocked: no docs"));

        service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, RUN_ID));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(agentService, timeout(3000)).chatWithUsage(eq(LEAD_ID), captor.capture(), eq(LEAD_CONV));
        String message = captor.getValue();
        assertTrue(message.contains("2 delegated team tasks have settled (1 failed)"));
        assertTrue(message.contains("Task #1"));
        assertTrue(message.contains("report done"));
        assertTrue(message.contains("Task #2"));
        assertTrue(message.contains("blocked: no docs"));
        // Drained means a later timer fire must not wake the lead again.
        service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, RUN_ID));
        verify(agentService, after(300).times(1)).chatWithUsage(any(), anyString(), anyString());

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(conversationService, timeout(3000))
                .saveMessage(eq(LEAD_CONV), eq("user"), anyString(), isNull(), eq("completed"),
                        eq(0), eq(0), isNull(), isNull(), metadata.capture());
        JSONObject json = JSONUtil.parseObj(metadata.getValue());
        assertEquals(String.valueOf(RUN_ID), json.getStr("runId"));
        assertFalse(json.containsKey("taskId"));
        assertEquals(List.of("1", "2"), json.getJSONArray("taskIds").toList(String.class));
    }

    @Test
    @DisplayName("a busy lead defers delivery — no concurrent turn is started")
    void busyLeadDefers() {
        when(runningConversations.isActive(LEAD_CONV)).thenReturn(true);
        service.announceTaskSettled(settled(1L, TeamTaskStatus.COMPLETED, "done"));

        service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, RUN_ID));

        verify(agentService, after(500).never()).chatWithUsage(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a task without a lead conversation is silently skipped")
    void noLeadConversationNoop() {
        TeamTaskEntity orphan = settled(1L, TeamTaskStatus.COMPLETED, "done");
        orphan.setLeadConversationId(null);

        service.announceTaskSettled(orphan);
        service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, RUN_ID));

        verify(agentService, after(300).never()).chatWithUsage(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("the wake-up run emits start and reply SSE events")
    void wakeUpEmitsSseEvents() {
        when(runningConversations.isActive(LEAD_CONV)).thenReturn(false);
        when(agentService.chatWithUsage(eq(LEAD_ID), anyString(), eq(LEAD_CONV)))
                .thenReturn(AgentService.ChatResult.contentOnly("综合汇报"));

        service.announceTaskSettled(settled(1L, TeamTaskStatus.COMPLETED, "done"));
        service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, RUN_ID));

        verify(streamTracker, timeout(3000))
                .broadcastObject(eq(LEAD_CONV), eq("team_announce_start"), any());
        verify(streamTracker, timeout(3000))
                .broadcastObject(eq(LEAD_CONV), eq("team_announce_reply"), any());
        // The announce turn persists, so the lead's reply survives a reload and
        // stays in the lead's conversation window for later turns. Both rows
        // carry an internal-note metadata type so the chat UI renders them as
        // a collapsed system strip instead of a user bubble.
        ArgumentCaptor<String> userMetadata = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> replyMetadata = ArgumentCaptor.forClass(String.class);
        verify(conversationService, timeout(3000))
                .saveMessage(eq(LEAD_CONV), eq("user"), anyString(), isNull(), eq("completed"),
                        eq(0), eq(0), isNull(), isNull(), userMetadata.capture());
        verify(conversationService, timeout(3000))
                .saveMessage(eq(LEAD_CONV), eq("assistant"), eq("综合汇报"), isNull(), eq("completed"),
                        eq(0), eq(0), isNull(), isNull(), replyMetadata.capture());

        assertAnnounceMetadata(userMetadata.getValue(), "team_announce", "1");
        assertAnnounceMetadata(replyMetadata.getValue(), "team_announce_reply", "1");
    }

    @Test
    @DisplayName("legacy null-run announcements omit runId but retain taskId")
    void legacyAnnouncementOmitsNullRunId() {
        when(runningConversations.isActive(LEAD_CONV)).thenReturn(false);
        TeamTaskEntity task = settled(7L, TeamTaskStatus.COMPLETED, "done");
        task.setRunId(null);
        service.announceTaskSettled(task);
        service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, null));

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(conversationService, timeout(3000))
                .saveMessage(eq(LEAD_CONV), eq("user"), anyString(), isNull(), eq("completed"),
                        eq(0), eq(0), isNull(), isNull(), metadata.capture());
        JSONObject json = JSONUtil.parseObj(metadata.getValue());
        assertEquals("team_announce", json.getStr("type"));
        assertEquals("7", json.getStr("taskId"));
        assertFalse(json.containsKey("runId"));
    }

    @Test
    @DisplayName("concurrent run drains serialize lead wake turns and preserve metadata")
    void concurrentRunDrainsSerializeLeadWakeTurns() throws Exception {
        when(runningConversations.isActive(LEAD_CONV)).thenReturn(false);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger wakeCount = new AtomicInteger();
        when(agentService.chatWithUsage(eq(LEAD_ID), anyString(), eq(LEAD_CONV)))
                .thenAnswer(invocation -> {
                    if (wakeCount.incrementAndGet() == 1) {
                        firstStarted.countDown();
                        assertTrue(releaseFirst.await(3, TimeUnit.SECONDS));
                    } else {
                        secondStarted.countDown();
                    }
                    return AgentService.ChatResult.contentOnly("reply");
                });
        TeamTaskEntity first = settled(11L, TeamTaskStatus.COMPLETED, "first");
        first.setRunId(101L);
        TeamTaskEntity second = settled(22L, TeamTaskStatus.COMPLETED, "second");
        second.setRunId(202L);
        service.announceTaskSettled(first);
        service.announceTaskSettled(second);

        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, 101L))),
                CompletableFuture.runAsync(() -> service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, 202L)))
        ).join();

        assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
        assertFalse(secondStarted.await(300, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();
        assertTrue(secondStarted.await(3, TimeUnit.SECONDS));

        ArgumentCaptor<String> userMetadata = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> replyMetadata = ArgumentCaptor.forClass(String.class);
        verify(conversationService, timeout(3000).times(2))
                .saveMessage(eq(LEAD_CONV), eq("user"), anyString(), isNull(), eq("completed"),
                        eq(0), eq(0), isNull(), isNull(), userMetadata.capture());
        verify(conversationService, timeout(3000).times(2))
                .saveMessage(eq(LEAD_CONV), eq("assistant"), eq("reply"), isNull(), eq("completed"),
                        eq(0), eq(0), isNull(), isNull(), replyMetadata.capture());

        Map<String, String> expected = Map.of("101", "11", "202", "22");
        assertRunTaskMetadata(userMetadata.getAllValues(), expected);
        assertRunTaskMetadata(replyMetadata.getAllValues(), expected);
    }

    @Test
    @DisplayName("busy retry releases ownership and later serializes pending runs")
    void busyRetrySerializesPendingRuns() throws Exception {
        AtomicBoolean busy = new AtomicBoolean(true);
        when(runningConversations.isActive(LEAD_CONV)).thenAnswer(invocation -> busy.get());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger wakeCount = new AtomicInteger();
        when(agentService.chatWithUsage(eq(LEAD_ID), anyString(), eq(LEAD_CONV)))
                .thenAnswer(invocation -> {
                    if (wakeCount.incrementAndGet() == 1) {
                        firstStarted.countDown();
                        assertTrue(releaseFirst.await(3, TimeUnit.SECONDS));
                    } else {
                        secondStarted.countDown();
                    }
                    return AgentService.ChatResult.contentOnly("reply");
                });
        TeamTaskEntity first = settled(31L, TeamTaskStatus.COMPLETED, "first");
        first.setRunId(301L);
        TeamTaskEntity second = settled(32L, TeamTaskStatus.COMPLETED, "second");
        second.setRunId(302L);
        service.announceTaskSettled(first);
        service.announceTaskSettled(second);

        service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, 301L));
        service.drain(new TeamAnnounceService.BatchKey(LEAD_CONV, 302L));
        verify(agentService, after(300).never()).chatWithUsage(any(), anyString(), anyString());
        busy.set(false);

        assertTrue(firstStarted.await(4, TimeUnit.SECONDS));
        assertFalse(secondStarted.await(300, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();
        assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
        verify(agentService, timeout(3000).times(2)).chatWithUsage(eq(LEAD_ID), anyString(), eq(LEAD_CONV));
    }

    @Test
    @DisplayName("announcement text: single result keeps the singular form and the playbook")
    void announcementText() {
        String single = TeamAnnounceService.buildAnnouncement(List.of(
                new TeamAnnounceService.AnnounceItem(1L, TEAM_ID, 1, "collect", TeamTaskStatus.COMPLETED,
                        "写手", "all collected")));
        assertTrue(single.contains("A delegated team task has settled"));
        assertTrue(single.contains("member: 写手"));
        assertTrue(single.contains("ONE synthesized answer"));
        assertTrue(single.contains("action=\"retry\""));
    }

    private void assertAnnounceMetadata(String metadata, String type, String taskId) {
        JSONObject json = JSONUtil.parseObj(metadata);
        assertEquals(type, json.getStr("type"));
        assertEquals(String.valueOf(RUN_ID), json.getStr("runId"));
        assertEquals(taskId, json.getStr("taskId"));
    }

    private void assertRunTaskMetadata(List<String> metadata, Map<String, String> expected) {
        assertEquals(expected, metadata.stream()
                .map(JSONUtil::parseObj)
                .collect(java.util.stream.Collectors.toMap(
                        json -> json.getStr("runId"),
                        json -> json.getStr("taskId"))));
    }
}
