package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Spy;
import vip.mate.agent.AgentService;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Minimal E2E-style test verifying the delegation event sequence:
 * delegation_start → delegation_progress → delegation_end.
 * <p>
 * To cover delegation_progress, the test captures the relay listener registered via
 * {@code addEventRelay} and simulates child events during {@code agentService.chat()},
 * triggering the relay path that broadcasts progress to the parent conversation.
 */
@ExtendWith(MockitoExtension.class)
class DelegateEventSequenceTest {

    @Mock AgentService agentService;
    @Mock AgentMapper agentMapper;
    @Mock ChatStreamTracker streamTracker;
    @Mock ConversationService conversationService;
    @Mock AuditEventService auditEventService;
    @Spy SubagentRegistry subagentRegistry = new SubagentRegistry();

    @InjectMocks DelegateAgentTool delegateAgentTool;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
                AgentEntity.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        var field = DelegateAgentTool.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(delegateAgentTool, objectMapper);
    }

    @AfterEach
    void cleanup() {
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
        ToolExecutionContext.clear();
    }

    private AgentEntity makeAgent(Long id, String name) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setName(name);
        agent.setEnabled(true);
        agent.setWorkspaceId(1L);
        agent.setAgentType("react");
        return agent;
    }

    // ===== Full sequence: delegation_start → delegation_progress → delegation_end =====

    @Test
    @DisplayName("Single delegation produces start → progress → end event sequence")
    void singleDelegationFullEventSequence() {
        AgentEntity target = makeAgent(100L, "HelperAgent");
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(target);

        String parentConvId = "parent-conv-123";
        ToolExecutionContext.set(parentConvId, "admin");
        when(streamTracker.isRunning(parentConvId)).thenReturn(true);

        // Capture the relay listener so we can simulate child events
        AtomicReference<BiConsumer<String, String>> relayRef = new AtomicReference<>();
        // Single + parallel delegation now route the relay through the batched API.
        when(streamTracker.addBatchedEventRelay(anyString(), anyString(), anyInt(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    relayRef.set(invocation.getArgument(4));
                    return (Runnable) () -> {};
                });

        // During chat(), simulate the child broadcasting a tool_call_started event
        when(agentService.chat(eq(100L), eq("summarize the report"), anyString(), any()))
                .thenAnswer(invocation -> {
                    // The relay listener should have been registered by now — fire it
                    BiConsumer<String, String> relay = relayRef.get();
                    assertNotNull(relay, "Relay should be registered before child chat starts");
                    relay.accept("tool_call_started", "{\"name\":\"searchWeb\"}");
                    relay.accept("tool_call_completed", "{\"name\":\"searchWeb\",\"success\":true}");
                    return "The report shows growth of 15% YoY.";
                });

        // Act
        String result = delegateAgentTool.delegateToAgent("HelperAgent", "summarize the report", null, null);

        // Assert: result is successful
        assertTrue(result.contains("15%"), "Should contain the child's response");

        // Capture all broadcastObject calls
        ArgumentCaptor<String> convIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(streamTracker, atLeast(3)).broadcastObject(
                convIdCaptor.capture(), eventCaptor.capture(), any());

        List<String> eventNames = eventCaptor.getAllValues();

        // Verify full sequence: start → progress(es) → end
        assertTrue(eventNames.size() >= 3,
                "Should have at least 3 events (start + progress + end), got: " + eventNames);
        assertEquals("delegation_start", eventNames.get(0),
                "First event should be delegation_start");

        // There should be at least one delegation_progress between start and end
        List<String> middle = eventNames.subList(1, eventNames.size() - 1);
        assertTrue(middle.contains("delegation_progress"),
                "Should have delegation_progress between start and end, got: " + eventNames);

        assertEquals("delegation_end", eventNames.get(eventNames.size() - 1),
                "Last event should be delegation_end");

        // All events target the parent conversation
        for (String convId : convIdCaptor.getAllValues()) {
            assertEquals(parentConvId, convId, "Events should target parent conversation");
        }
    }

    // ===== Parallel delegation event sequence =====

    @Test
    @DisplayName("Parallel delegation broadcasts delegation_start and delegation_end with parallel=true")
    void parallelDelegationEventSequence() {
        AgentEntity agentA = makeAgent(101L, "AgentA");
        AgentEntity agentB = makeAgent(102L, "AgentB");

        when(agentMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(agentA)
                .thenReturn(agentB);

        String parentConvId = "parent-parallel-456";
        ToolExecutionContext.set(parentConvId, "admin");
        when(streamTracker.isRunning(parentConvId)).thenReturn(true);
        when(streamTracker.addBatchedEventRelay(anyString(), anyString(), anyInt(), anyLong(), any()))
                .thenReturn(() -> {});

        when(agentService.chat(eq(101L), anyString(), anyString(), any())).thenReturn("Result A");
        when(agentService.chat(eq(102L), anyString(), anyString(), any())).thenReturn("Result B");

        String json = "[{\"agentName\":\"AgentA\",\"task\":\"task A\"},{\"agentName\":\"AgentB\",\"task\":\"task B\"}]";

        // Act
        String result = delegateAgentTool.delegateParallel(json, null);

        assertTrue(result.contains("AgentA"), "Should mention AgentA");
        assertTrue(result.contains("AgentB"), "Should mention AgentB");

        // Capture events
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(streamTracker, atLeast(2)).broadcastObject(
                eq(parentConvId), eventCaptor.capture(), any());

        List<String> eventNames = eventCaptor.getAllValues();
        assertEquals("delegation_start", eventNames.get(0), "First event should be delegation_start");
        assertEquals("delegation_end", eventNames.get(eventNames.size() - 1),
                "Last event should be delegation_end");
    }

    // ===== No events when parent inactive =====

    @Test
    @DisplayName("No events are broadcast when parent conversation is not active")
    void noEventsWhenParentInactive() {
        AgentEntity target = makeAgent(200L, "QuietAgent");
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(target);

        ToolExecutionContext.set("inactive-parent", "admin");
        when(streamTracker.isRunning("inactive-parent")).thenReturn(false);

        when(agentService.chat(eq(200L), anyString(), anyString(), any())).thenReturn("done");

        // Act
        delegateAgentTool.delegateToAgent("QuietAgent", "quiet task", null, null);

        // Assert: no events broadcast, no relay registered
        verify(streamTracker, never()).broadcastObject(anyString(), anyString(), any());
        verify(streamTracker, never()).addEventRelay(anyString(), any());
        verify(streamTracker, never())
                .addBatchedEventRelay(anyString(), anyString(), anyInt(), anyLong(), any());
    }

    // ===== Relay only forwards recognized event types =====

    @Test
    @DisplayName("Relay ignores unrecognized event types, only forwards tool_call_started/completed/phase")
    void relayFiltersEventTypes() {
        AgentEntity target = makeAgent(300L, "FilterAgent");
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(target);

        String parentConvId = "parent-filter-789";
        ToolExecutionContext.set(parentConvId, "admin");
        when(streamTracker.isRunning(parentConvId)).thenReturn(true);

        AtomicReference<BiConsumer<String, String>> relayRef = new AtomicReference<>();
        // Single + parallel delegation now route the relay through the batched API.
        when(streamTracker.addBatchedEventRelay(anyString(), anyString(), anyInt(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    relayRef.set(invocation.getArgument(4));
                    return (Runnable) () -> {};
                });

        when(agentService.chat(eq(300L), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    BiConsumer<String, String> relay = relayRef.get();
                    // These should produce delegation_progress:
                    relay.accept("tool_call_started", "{\"name\":\"search\"}");
                    relay.accept("phase", "{\"phase\":\"reasoning\"}");
                    // These should be ignored by the relay filter:
                    relay.accept("heartbeat", "{}");
                    relay.accept("token", "{\"text\":\"hello\"}");
                    return "filtered result";
                });

        delegateAgentTool.delegateToAgent("FilterAgent", "filter task", null, null);

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(streamTracker, atLeast(1)).broadcastObject(
                eq(parentConvId), eventCaptor.capture(), any());

        List<String> events = eventCaptor.getAllValues();
        long progressCount = events.stream().filter("delegation_progress"::equals).count();
        // 2 recognized events → 2 progress broadcasts (heartbeat and token are filtered out)
        assertEquals(2, progressCount,
                "Should have exactly 2 delegation_progress events (tool_call_started + phase), got: " + events);
    }

    // ===== Nested delegation: grandchild events route to root with tree identity =====

    @Test
    @DisplayName("A child delegating a grandchild broadcasts to root with parentSubagentId + depth=2")
    @SuppressWarnings("unchecked")
    void nestedDelegationRoutesGrandchildToRootWithIdentity() {
        AgentEntity child = makeAgent(100L, "Child");
        AgentEntity grandchild = makeAgent(200L, "Grandchild");
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(child)        // root delegates Child
                .thenReturn(grandchild);  // Child delegates Grandchild

        String rootConv = "root-conv";
        ToolExecutionContext.set(rootConv, "admin");
        when(streamTracker.isRunning(rootConv)).thenReturn(true);
        when(streamTracker.addBatchedEventRelay(anyString(), anyString(), anyInt(), anyLong(), any()))
                .thenReturn(() -> {});

        // Capture each created child conversation + its immediate parent so we can
        // assert the grandchild's immediate parent is the Child's conversation,
        // not the root — the createChildConversation(childConvId, ..., parent) call.
        List<String> createdConvs = new java.util.ArrayList<>();
        List<String> createdParents = new java.util.ArrayList<>();
        doAnswer(inv -> {
            createdConvs.add(inv.getArgument(0));
            createdParents.add(inv.getArgument(4));
            return null;
        }).when(conversationService).createChildConversation(
                anyString(), anyLong(), anyString(), anyLong(), anyString());

        // When the Child runs, the real ToolExecutionExecutor would switch the
        // ToolExecutionContext to the Child's own conversation. Reproduce that so
        // the grandchild's immediate parent resolves to childConv, while its
        // events must still target rootConv (carried via DelegationContext).
        when(agentService.chat(eq(100L), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    String childConv = inv.getArgument(2);
                    ToolExecutionContext.set(childConv, "admin");
                    try {
                        return delegateAgentTool.delegateToAgent("Grandchild", "gtask", null, null);
                    } finally {
                        ToolExecutionContext.set(rootConv, "admin");
                    }
                });
        when(agentService.chat(eq(200L), anyString(), anyString(), any()))
                .thenReturn("grandchild done");

        delegateAgentTool.delegateToAgent("Child", "ctask", null, null);

        ArgumentCaptor<String> convCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> evCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(streamTracker, atLeast(4)).broadcastObject(convCap.capture(), evCap.capture(), payloadCap.capture());

        Map<String, Object> childStart = null;
        Map<String, Object> grandStart = null;
        for (int i = 0; i < evCap.getAllValues().size(); i++) {
            if (!"delegation_start".equals(evCap.getAllValues().get(i))) continue;
            Map<String, Object> p = (Map<String, Object>) payloadCap.getAllValues().get(i);
            // Every delegation_start — at any depth — targets the root conversation.
            assertEquals(rootConv, convCap.getAllValues().get(i),
                    "delegation_start must target the root conversation");
            String name = String.valueOf(p.get("childAgentName"));
            if ("Child".equals(name)) childStart = p;
            else if ("Grandchild".equals(name)) grandStart = p;
        }
        assertNotNull(childStart, "child delegation_start present");
        assertNotNull(grandStart, "grandchild delegation_start present");

        // depth-1 child: depth=1, no parentSubagentId.
        assertEquals(1, ((Number) childStart.get("depth")).intValue());
        assertNull(childStart.get("parentSubagentId"), "depth-1 child carries no parentSubagentId");

        // depth-2 grandchild: depth=2, parented to the child's subagentId.
        assertEquals(2, ((Number) grandStart.get("depth")).intValue());
        assertNotNull(grandStart.get("parentSubagentId"), "grandchild must carry parentSubagentId");
        assertEquals(childStart.get("subagentId"), grandStart.get("parentSubagentId"),
                "grandchild's parentSubagentId must equal the child's subagentId");

        // Two child conversations were created: [0] = Child (parent=root),
        // [1] = Grandchild (parent must be the Child's conversation, not root).
        assertEquals(2, createdConvs.size(), "Child + Grandchild conversations created");
        assertEquals(rootConv, createdParents.get(0), "Child's immediate parent is the root conversation");
        assertEquals(createdConvs.get(0), createdParents.get(1),
                "Grandchild's immediate parent must be the Child's conversation");
    }
}
