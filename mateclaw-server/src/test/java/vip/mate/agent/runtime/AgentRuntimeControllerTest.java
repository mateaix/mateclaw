package vip.mate.agent.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.exception.MateClawException;
import vip.mate.i18n.I18nService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.agent.runtime.dsh.DshRuntimeService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeControllerTest {

    private static final long WORKSPACE_ID = 10L;
    private AgentRuntimeAggregator aggregator;
    private ChatStreamTracker streamTracker;
    private SubagentRegistry subagentRegistry;
    private AgentRuntimeController controller;

    @BeforeEach
    void setUp() {
        aggregator = mock(AgentRuntimeAggregator.class);
        streamTracker = mock(ChatStreamTracker.class);
        subagentRegistry = mock(SubagentRegistry.class);
        controller = new AgentRuntimeController(
                aggregator,
                streamTracker,
                subagentRegistry,
                mock(AuditEventService.class),
                mock(ConversationService.class),
                mock(I18nService.class),
                mock(DshRuntimeService.class));
    }

    @Test
    void snapshotUsesCurrentWorkspace() {
        AgentRuntimeAggregator.RuntimeSnapshot snapshot =
                new AgentRuntimeAggregator.RuntimeSnapshot(
                        new AgentRuntimeAggregator.Summary(0, 0, 0, 0, 0),
                        List.of(), List.of(), 123L);
        when(aggregator.snapshot(WORKSPACE_ID)).thenReturn(snapshot);

        assertEquals(snapshot, controller.snapshot(WORKSPACE_ID, admin()).getData());

        verify(aggregator).snapshot(WORKSPACE_ID);
    }

    @Test
    void snapshotRequiresWorkspace() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.snapshot(null, admin()));

        assertEquals(400, ex.getCode());
    }

    @Test
    void stopRejectsRunOutsideCurrentWorkspace() {
        when(aggregator.runBelongsToWorkspace("conv-b", WORKSPACE_ID)).thenReturn(false);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.stopFriendly("conv-b", WORKSPACE_ID, admin()));

        assertEquals(404, ex.getCode());
        verify(streamTracker, never()).requestStop("conv-b");
    }

    @Test
    void recycleRejectsRunOutsideCurrentWorkspace() {
        when(aggregator.runBelongsToWorkspace("conv-b", WORKSPACE_ID)).thenReturn(false);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.recycle("conv-b", WORKSPACE_ID, admin()));

        assertEquals(404, ex.getCode());
        verify(streamTracker, never()).forceRecycle("conv-b");
    }

    @Test
    void interruptRejectsSubagentOutsideCurrentWorkspace() {
        when(aggregator.subagentBelongsToWorkspace("sa-b", WORKSPACE_ID)).thenReturn(false);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.interruptSubagent("sa-b", WORKSPACE_ID, admin()));

        assertEquals(404, ex.getCode());
        verify(subagentRegistry, never()).interrupt("sa-b");
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
