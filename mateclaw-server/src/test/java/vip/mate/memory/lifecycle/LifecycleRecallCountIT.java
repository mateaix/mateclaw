package vip.mate.memory.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.AgentService;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A.10 — F4 regression test: recall_count / daily_count must remain
 * identical whether lifecycleMediatorEnabled is on or off.
 *
 * <p>Verifies that MemoryLifecycleMediator never calls trackRecalls,
 * and AgentService calls trackRecalls exactly once per chat entry
 * regardless of the flag state.
 */
@ExtendWith(MockitoExtension.class)
class LifecycleRecallCountIT {

    @Mock private AgentMapper agentMapper;
    @Mock private AgentGraphBuilder agentGraphBuilder;
    @Mock private MemoryRecallTracker memoryRecallTracker;
    @Mock private MemoryManager memoryManager;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BaseAgent mockAgent;
    @Mock private ConversationMapper conversationMapper;

    private MemoryProperties props;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
        MemoryLifecycleMediator mediator = new MemoryLifecycleMediator(memoryManager, eventPublisher);
        agentService = new AgentService(agentMapper, agentGraphBuilder,
                memoryRecallTracker, mediator, props, conversationMapper);

        // Stub agent resolution (lenient for structural-only tests)
        AgentEntity entity = new AgentEntity();
        entity.setId(1L);
        entity.setEnabled(true);
        lenient().when(agentMapper.selectById(1L)).thenReturn(entity);
        lenient().when(agentGraphBuilder.build(any(AgentEntity.class), any(), any())).thenReturn(mockAgent);
        lenient().when(mockAgent.chat(any(), any())).thenReturn("reply");
    }

    @Test
    @DisplayName("F4 regression: flag OFF — trackRecalls called once per chat, mediator is silent")
    void flagOff_trackRecallsOncePerChat() {
        props.setLifecycleMediatorEnabled(false);

        for (int i = 0; i < 10; i++) {
            agentService.chat(1L, "msg-" + i, "conv-1");
        }

        // trackRecalls: exactly 10 times (once per chat call)
        verify(memoryRecallTracker, times(10)).trackRecalls(eq(1L), any());

        // Mediator is not invoked when flag is off
        verify(memoryManager, never()).prefetchAll(any(), any());
        verify(memoryManager, never()).syncAll(any(), any(), any(), any());
    }

    @Test
    @DisplayName("F4 regression: flag ON — trackRecalls still called exactly once per chat (not doubled)")
    void flagOn_trackRecallsStillOncePerChat() {
        props.setLifecycleMediatorEnabled(true);
        when(memoryManager.prefetchAll(any(), any())).thenReturn("");

        for (int i = 0; i < 10; i++) {
            agentService.chat(1L, "msg-" + i, "conv-1");
        }

        // trackRecalls: still exactly 10 times — NOT 20 (D4: mediator does not call trackRecalls)
        verify(memoryRecallTracker, times(10)).trackRecalls(eq(1L), any());

        // Mediator IS invoked
        verify(memoryManager, times(10)).prefetchAll(eq(1L), any());
        verify(memoryManager, times(10)).syncAll(eq(1L), eq("conv-1"), any(), any());
    }

    @Test
    @DisplayName("F4 regression: flag toggle does not change trackRecalls count")
    void flagToggle_sameTrackRecallsCount() {
        // 5 rounds with flag OFF
        props.setLifecycleMediatorEnabled(false);
        for (int i = 0; i < 5; i++) {
            agentService.chat(1L, "off-" + i, "conv-1");
        }

        // 5 rounds with flag ON
        props.setLifecycleMediatorEnabled(true);
        when(memoryManager.prefetchAll(any(), any())).thenReturn("");
        for (int i = 0; i < 5; i++) {
            agentService.chat(1L, "on-" + i, "conv-1");
        }

        // Total: 10 trackRecalls calls regardless of flag state
        verify(memoryRecallTracker, times(10)).trackRecalls(eq(1L), any());

        // Mediator only called for the ON rounds
        verify(memoryManager, times(5)).prefetchAll(eq(1L), any());
    }

    @Test
    @DisplayName("Mediator source code does not reference trackRecalls (structural guard)")
    void mediator_noTrackRecallsReference() throws Exception {
        // Structural assertion: MemoryLifecycleMediator has no field or method
        // that references MemoryRecallTracker
        var mediatorClass = MemoryLifecycleMediator.class;
        for (var field : mediatorClass.getDeclaredFields()) {
            if (field.getType().getSimpleName().contains("RecallTracker")) {
                throw new AssertionError("Mediator must not depend on MemoryRecallTracker (D4)");
            }
        }
        // Also verify via declared constructor params
        var ctorParams = mediatorClass.getDeclaredConstructors()[0].getParameterTypes();
        for (var param : ctorParams) {
            if (param.getSimpleName().contains("RecallTracker")) {
                throw new AssertionError("Mediator constructor must not accept MemoryRecallTracker (D4)");
            }
        }
    }
}
