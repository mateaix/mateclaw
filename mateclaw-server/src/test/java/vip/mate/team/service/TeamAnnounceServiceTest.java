package vip.mate.team.service;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the announce contract: settled results are batched per lead
 * conversation and delivered as ONE merged wake-up message; a busy lead defers
 * delivery instead of risking an in-turn drop; the merged text carries the
 * synthesis / retry instructions the lead acts on.
 */
class TeamAnnounceServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long LEAD_ID = 1L;
    private static final String LEAD_CONV = "lead-conv";

    private TeamService teamService;
    private AgentService agentService;
    private AgentMapper agentMapper;
    private RunningConversationRegistry runningConversations;
    private ChatStreamTracker streamTracker;
    private TeamAnnounceService service;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        agentService = mock(AgentService.class);
        agentMapper = mock(AgentMapper.class);
        runningConversations = mock(RunningConversationRegistry.class);
        streamTracker = mock(ChatStreamTracker.class);
        service = new TeamAnnounceService(teamService, agentService, agentMapper,
                runningConversations, streamTracker);

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

        service.drain(LEAD_CONV);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(agentService, timeout(3000)).chatWithUsage(eq(LEAD_ID), captor.capture(), eq(LEAD_CONV));
        String message = captor.getValue();
        assertTrue(message.contains("2 delegated team tasks have settled (1 failed)"));
        assertTrue(message.contains("Task #1"));
        assertTrue(message.contains("report done"));
        assertTrue(message.contains("Task #2"));
        assertTrue(message.contains("blocked: no docs"));
        // Drained means a later timer fire must not wake the lead again.
        service.drain(LEAD_CONV);
        verify(agentService, after(300).times(1)).chatWithUsage(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a busy lead defers delivery — no concurrent turn is started")
    void busyLeadDefers() {
        when(runningConversations.isActive(LEAD_CONV)).thenReturn(true);
        service.announceTaskSettled(settled(1L, TeamTaskStatus.COMPLETED, "done"));

        service.drain(LEAD_CONV);

        verify(agentService, after(500).never()).chatWithUsage(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a task without a lead conversation is silently skipped")
    void noLeadConversationNoop() {
        TeamTaskEntity orphan = settled(1L, TeamTaskStatus.COMPLETED, "done");
        orphan.setLeadConversationId(null);

        service.announceTaskSettled(orphan);
        service.drain(LEAD_CONV);

        verify(agentService, after(300).never()).chatWithUsage(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("the wake-up run emits start and reply SSE events")
    void wakeUpEmitsSseEvents() {
        when(runningConversations.isActive(LEAD_CONV)).thenReturn(false);
        when(agentService.chatWithUsage(eq(LEAD_ID), anyString(), eq(LEAD_CONV)))
                .thenReturn(AgentService.ChatResult.contentOnly("综合汇报"));

        service.announceTaskSettled(settled(1L, TeamTaskStatus.COMPLETED, "done"));
        service.drain(LEAD_CONV);

        verify(streamTracker, timeout(3000))
                .broadcastObject(eq(LEAD_CONV), eq("team_announce_start"), any());
        verify(streamTracker, timeout(3000))
                .broadcastObject(eq(LEAD_CONV), eq("team_announce_reply"), any());
    }

    @Test
    @DisplayName("announcement text: single result keeps the singular form and the playbook")
    void announcementText() {
        String single = TeamAnnounceService.buildAnnouncement(List.of(
                new TeamAnnounceService.AnnounceItem(TEAM_ID, 1, "collect", TeamTaskStatus.COMPLETED,
                        "写手", "all collected")));
        assertTrue(single.contains("A delegated team task has settled"));
        assertTrue(single.contains("member: 写手"));
        assertTrue(single.contains("ONE synthesized answer"));
        assertTrue(single.contains("action=\"retry\""));
    }
}
