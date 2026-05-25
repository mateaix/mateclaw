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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.AgentService;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.conversation.ConversationService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DelegateAgentTool}.
 * Covers: parallel timeout returns explicit error, partial completion,
 * and agent-not-found returns readable error.
 */
@ExtendWith(MockitoExtension.class)
class DelegateAgentToolTest {

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
        // Initialize MyBatis Plus lambda cache for AgentEntity so LambdaQueryWrapper works in unit tests
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
                AgentEntity.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        // Inject the real ObjectMapper into the tool via reflection
        // (Lombok @RequiredArgsConstructor includes final fields, but ObjectMapper is final)
        var field = DelegateAgentTool.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(delegateAgentTool, objectMapper);

        // Production default is 300 s (configured via @Value) — too long for
        // unit tests that simulate a stuck child via Thread.sleep. Force a
        // short budget so the timeout assertions fire quickly. Picked 3 s as
        // a balance: long enough to mask single-digit-ms scheduling jitter on
        // CI, short enough that a hanging test fails fast.
        var timeoutField = DelegateAgentTool.class.getDeclaredField("parallelTimeoutSeconds");
        timeoutField.setAccessible(true);
        timeoutField.setInt(delegateAgentTool, 3);
    }

    @AfterEach
    void cleanup() {
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
        ToolExecutionContext.clear();
    }

    // ===== delegateToAgent: agent not found =====

    @Test
    @DisplayName("delegateToAgent returns readable error when agent not found")
    void delegateToAgentNotFound() {
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(agentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());

        String result = delegateAgentTool.delegateToAgent("NonExistentAgent", "do something", null, null);

        assertTrue(result.contains("NonExistentAgent"), "Should mention the missing agent name");
        assertTrue(result.contains("[错误]") || result.contains("未找到"), "Should indicate an error");
    }

    @Test
    @DisplayName("delegateToAgent returns error when agentName is blank")
    void delegateToAgentBlankName() {
        when(agentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());

        String result = delegateAgentTool.delegateToAgent("", "do something", null, null);

        assertTrue(result.contains("[错误]"), "Should indicate an error for blank name");
    }

    @Test
    @DisplayName("delegateToAgent returns error when task is blank")
    void delegateToAgentBlankTask() {
        String result = delegateAgentTool.delegateToAgent("SomeAgent", "", null, null);

        assertTrue(result.contains("[错误]"), "Should indicate an error for blank task");
    }

    // ===== delegateToAgent: depth limit =====

    @Test
    @DisplayName("delegateToAgent rejects when delegation depth reaches limit")
    void delegateToAgentDepthLimit() {
        // Push depth to MAX_DELEGATION_DEPTH (3)
        DelegationContext.enter("a", null);
        DelegationContext.enter("b", null);
        DelegationContext.enter("c", null);

        String result = delegateAgentTool.delegateToAgent("SomeAgent", "task", null, null);

        assertTrue(result.contains("上限"), "Should mention the depth limit");
    }

    // ===== delegateParallel: invalid JSON =====

    @Test
    @DisplayName("delegateParallel returns error for malformed JSON input")
    void delegateParallelBadJson() {
        String result = delegateAgentTool.delegateParallel("not valid json", null);

        assertTrue(result.contains("[错误]"), "Should indicate parse error");
        assertTrue(result.contains("JSON"), "Should mention JSON");
    }

    // ===== delegateParallel: empty task list =====

    @Test
    @DisplayName("delegateParallel returns error for empty task list")
    void delegateParallelEmptyList() {
        String result = delegateAgentTool.delegateParallel("[]", null);

        assertTrue(result.contains("[错误]"), "Should indicate empty list error");
    }

    // ===== delegateParallel: all agents not found =====

    @Test
    @DisplayName("delegateParallel returns error when all agents are not found")
    void delegateParallelAllAgentsNotFound() {
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        String json = "[{\"agentName\":\"Missing1\",\"task\":\"task1\"},{\"agentName\":\"Missing2\",\"task\":\"task2\"}]";
        String result = delegateAgentTool.delegateParallel(json, null);

        assertTrue(result.contains("[错误]"), "Should indicate error");
        assertTrue(result.contains("校验失败"), "Should mention validation failure");
    }

    // ===== delegateParallel: timeout returns explicit error =====

    @Test
    @DisplayName("delegateParallel returns timeout error for slow child agents")
    void delegateParallelTimeout() {
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setName("SlowAgent");
        agent.setEnabled(true);
        agent.setWorkspaceId(1L);

        when(agentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(agent);
        when(streamTracker.isRunning(any())).thenReturn(false);

        // Simulate a child agent that takes longer than the test budget (3 s).
        // 10 s is plenty: parent times out at 3 s and abandons the child, then
        // the test thread returns immediately. The orphan keeps sleeping on a
        // virtual thread until JVM teardown — that's the same behavior as
        // production (cancel is best-effort).
        when(agentService.chat(anyLong(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return "should not reach here";
        });

        // Set a conversationId so resolveParentConversationId works
        ToolExecutionContext.set("parent-conv", "admin");

        String json = "[{\"agentName\":\"SlowAgent\",\"task\":\"slow task\"}]";
        String result = delegateAgentTool.delegateParallel(json, null);

        // The result should contain a timeout error, not hang for 300s
        assertTrue(result.contains("超时") || result.contains("timeout") || result.contains("✗"),
                "Should contain timeout indicator in result: " + result);
    }

    // ===== delegateParallel: exceeds max children =====

    @Test
    @DisplayName("delegateParallel rejects when exceeding max parallel children")
    void delegateParallelExceedsMax() {
        // MAX_PARALLEL_CHILDREN is 8 — send 9 to trip the guard.
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= 9; i++) {
            if (i > 1) sb.append(',');
            sb.append("{\"agentName\":\"A").append(i).append("\",\"task\":\"t").append(i).append("\"}");
        }
        sb.append("]");

        String result = delegateAgentTool.delegateParallel(sb.toString(), null);

        assertTrue(result.contains("[错误]"), "Should indicate error for too many tasks");
        assertTrue(result.contains("最多"), "Should mention the limit");
    }

    // ===== delegateParallel: partial completion + partial timeout (mixed case) =====

    @Test
    @DisplayName("delegateParallel returns partial results: one fast success + one timeout")
    void delegateParallelPartialCompletionPartialTimeout() {
        AgentEntity fastAgent = new AgentEntity();
        fastAgent.setId(10L);
        fastAgent.setName("FastAgent");
        fastAgent.setEnabled(true);
        fastAgent.setWorkspaceId(1L);

        AgentEntity slowAgent = new AgentEntity();
        slowAgent.setId(11L);
        slowAgent.setName("SlowAgent");
        slowAgent.setEnabled(true);
        slowAgent.setWorkspaceId(1L);

        // Return correct agent per sequential selectOne calls
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(fastAgent)
                .thenReturn(slowAgent);
        when(streamTracker.isRunning(any())).thenReturn(false);

        // FastAgent completes immediately
        when(agentService.chat(eq(10L), anyString(), anyString(), any()))
                .thenReturn("Fast result completed successfully");

        // SlowAgent blocks longer than the (test-overridden) 3 s budget.
        when(agentService.chat(eq(11L), anyString(), anyString(), any())).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return "should not reach here";
        });

        ToolExecutionContext.set("parent-mixed", "admin");

        String json = "[{\"agentName\":\"FastAgent\",\"task\":\"quick task\"},{\"agentName\":\"SlowAgent\",\"task\":\"slow task\"}]";
        String result = delegateAgentTool.delegateParallel(json, null);

        // FastAgent's result should be preserved
        assertTrue(result.contains("FastAgent"), "Should mention FastAgent");
        assertTrue(result.contains("Fast result completed successfully") || result.contains("✓"),
                "Should contain successful result from FastAgent: " + result);

        // SlowAgent should have a timeout error
        assertTrue(result.contains("SlowAgent"), "Should mention SlowAgent");
        assertTrue(result.contains("超时") || result.contains("✗"),
                "Should contain timeout indicator for SlowAgent: " + result);
    }
}
