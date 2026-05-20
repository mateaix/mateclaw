package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.task.AsyncTaskService;
import vip.mate.task.model.AsyncTaskEntity;
import vip.mate.workspace.conversation.ConversationService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioral contract for the two async-delegation tools —
 * {@code delegateAsync} (spawn returns task_id immediately) and
 * {@code taskOutput} (status / result retrieval).
 * <p>
 * AsyncTaskService is mocked, so the Callable submitted by delegateAsync is
 * never invoked here: the inner execution path is covered by
 * {@code AsyncTaskServiceOneShotTest}. What this suite locks down is
 * the synchronous shell — argument validation, depth / spawn-pause guards,
 * cap-overflow degradation, JSON shape, and the SSE spawn-event side effect.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DelegateAsyncToolTest {

    @Mock private AgentService agentService;
    @Mock private AgentMapper agentMapper;
    @Mock private ChatStreamTracker streamTracker;
    @Mock private ConversationService conversationService;
    @Mock private SubagentRegistry subagentRegistry;
    @Mock private AuditEventService auditEventService;
    @Mock private AsyncTaskService asyncTaskService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DelegateAgentTool tool;

    @BeforeEach
    void setUp() {
        tool = new DelegateAgentTool(
                agentService, agentMapper, streamTracker, conversationService,
                objectMapper, subagentRegistry, auditEventService, asyncTaskService);
        // resolveParentConversationId reads from ToolExecutionContext first;
        // seed it so the async delegation has a parent to attach the task to.
        ToolExecutionContext.set("parent-conv-1", "user-1");
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
    }

    // ---------- delegateAsync ----------

    @Test
    @DisplayName("delegateAsync returns task_id, child_conversation_id, status=running synchronously")
    @SuppressWarnings("unchecked")
    void delegateAsyncReturnsTaskIdImmediately() throws Exception {
        AgentEntity target = makeAgent(10L, "Researcher");
        when(agentMapper.selectOne(any())).thenReturn(target);
        when(subagentRegistry.isSpawnPaused("parent-conv-1")).thenReturn(false);
        when(subagentRegistry.register(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn("sa-1");

        AsyncTaskEntity entity = new AsyncTaskEntity();
        entity.setTaskId("tid-123");
        when(asyncTaskService.submitOneShot(
                eq("agent_delegate"), eq("parent-conv-1"), any(), anyString(), eq("user-1"), any()))
                .thenReturn(entity);
        when(streamTracker.isRunning("parent-conv-1")).thenReturn(true);

        String result = tool.delegateAsync("Researcher", "Go research things", "label-x", makeCtx("user-1", "parent-conv-1"));

        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("task_id", "tid-123")
                .containsEntry("status", "running")
                .containsEntry("agent_name", "Researcher")
                .containsEntry("label", "label-x");
        assertThat((String) parsed.get("child_conversation_id")).startsWith("child-");
        assertThat((String) parsed.get("hint")).contains("task_output");

        // The spawn SSE event reaches the parent's stream.
        verify(streamTracker).broadcastObject(eq("parent-conv-1"),
                eq("delegation_async_spawned"), any(Map.class));
    }

    @Test
    @DisplayName("delegateAsync passes a request_json payload carrying parent + child + agentId + label")
    void delegateAsyncRequestJsonShape() throws Exception {
        AgentEntity target = makeAgent(10L, "Researcher");
        when(agentMapper.selectOne(any())).thenReturn(target);
        when(subagentRegistry.register(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn("sa-2");
        AsyncTaskEntity entity = new AsyncTaskEntity();
        entity.setTaskId("tid-200");
        when(asyncTaskService.submitOneShot(anyString(), anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(entity);

        tool.delegateAsync("Researcher", "task body", "myLabel", makeCtx("user-1", "parent-conv-1"));

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(asyncTaskService).submitOneShot(
                eq("agent_delegate"), eq("parent-conv-1"), any(),
                jsonCaptor.capture(), eq("user-1"), any());
        Map<String, Object> payload = objectMapper.readValue(jsonCaptor.getValue(), new TypeReference<>() {});
        assertThat(payload).containsEntry("parentConversationId", "parent-conv-1")
                .containsEntry("label", "myLabel")
                .containsEntry("task", "task body");
        assertThat(payload.get("childConversationId")).asString().startsWith("child-");
        assertThat(((Number) payload.get("childAgentId")).longValue()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Concurrency-cap (IllegalStateException) → error JSON + registry unregistered")
    void delegateAsyncConcurrencyCap() throws Exception {
        AgentEntity target = makeAgent(10L, "Researcher");
        when(agentMapper.selectOne(any())).thenReturn(target);
        when(subagentRegistry.register(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn("sa-cap");
        when(asyncTaskService.submitOneShot(anyString(), anyString(), any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("已达到最大并行任务数（3），请等待现有任务完成"));

        String result = tool.delegateAsync("Researcher", "task", null, makeCtx("user-1", "parent-conv-1"));

        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("最大并行任务数");

        // Registry entry MUST be released so it doesn't dangle through the cap.
        verify(subagentRegistry).unregister("sa-cap");
        // No spawn event broadcast for a failed spawn.
        verify(streamTracker, never()).broadcastObject(anyString(),
                eq("delegation_async_spawned"), any());
    }

    @Test
    @DisplayName("Missing agentName / task → error JSON without touching downstream services")
    void delegateAsyncMissingArgs() throws Exception {
        String r1 = tool.delegateAsync("", "task", null, makeCtx("user-1", "parent-conv-1"));
        String r2 = tool.delegateAsync("X", " ", null, makeCtx("user-1", "parent-conv-1"));
        for (String r : new String[]{r1, r2}) {
            Map<String, Object> parsed = objectMapper.readValue(r, new TypeReference<>() {});
            assertThat(parsed).containsEntry("error", true);
        }
        verify(asyncTaskService, never()).submitOneShot(any(), any(), any(), any(), any(), any());
        verify(subagentRegistry, never()).register(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Agent not found → error JSON")
    void delegateAsyncAgentNotFound() throws Exception {
        when(agentMapper.selectOne(any())).thenReturn(null);
        String result = tool.delegateAsync("Ghost", "task", null, makeCtx("user-1", "parent-conv-1"));
        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("Ghost");
        verify(asyncTaskService, never()).submitOneShot(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Spawn-pause active → error JSON, no task submitted, no registry entry")
    void delegateAsyncSpawnPause() throws Exception {
        AgentEntity target = makeAgent(10L, "Researcher");
        when(agentMapper.selectOne(any())).thenReturn(target);
        when(subagentRegistry.isSpawnPaused("parent-conv-1")).thenReturn(true);

        String result = tool.delegateAsync("Researcher", "task", null, makeCtx("user-1", "parent-conv-1"));
        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("paused");
        verify(asyncTaskService, never()).submitOneShot(any(), any(), any(), any(), any(), any());
        verify(subagentRegistry, never()).register(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Depth limit reached → error JSON")
    void delegateAsyncDepthLimit() throws Exception {
        // Push depth to MAX (3) so currentDepth >= MAX_DELEGATION_DEPTH.
        for (int i = 0; i < 3; i++) {
            DelegationContext.enter("parent-conv-1", java.util.Set.of());
        }
        String result = tool.delegateAsync("Researcher", "task", null, makeCtx("user-1", "parent-conv-1"));
        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("depth");
        verify(asyncTaskService, never()).submitOneShot(any(), any(), any(), any(), any(), any());
    }

    // ---------- taskOutput ----------

    @Test
    @DisplayName("taskOutput on running task with block=false returns status=running")
    void taskOutputRunning() throws Exception {
        AsyncTaskEntity entity = makeAsyncTask("tid-run", "running", "parent-conv-1", "user-1", null);
        when(asyncTaskService.findEntityByTaskId("tid-run")).thenReturn(entity);

        String result = tool.taskOutput("tid-run", false, null, makeCtx("user-1", "parent-conv-1"));
        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("status", "running")
                .containsEntry("task_id", "tid-run");
        assertThat((String) parsed.get("hint")).contains("Try again");
    }

    @Test
    @DisplayName("taskOutput on succeeded task returns result + duration_ms")
    void taskOutputSucceeded() throws Exception {
        AsyncTaskEntity entity = makeAsyncTask("tid-ok", "succeeded", "parent-conv-1", "user-1", "child final answer");
        when(asyncTaskService.findEntityByTaskId("tid-ok")).thenReturn(entity);

        String result = tool.taskOutput("tid-ok", null, null, makeCtx("user-1", "parent-conv-1"));
        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("status", "succeeded")
                .containsEntry("result", "child final answer");
        assertThat(((Number) parsed.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("taskOutput on failed task returns error message")
    void taskOutputFailed() throws Exception {
        AsyncTaskEntity entity = makeAsyncTask("tid-fail", "failed", "parent-conv-1", "user-1", null);
        entity.setErrorMessage("agent boom");
        when(asyncTaskService.findEntityByTaskId("tid-fail")).thenReturn(entity);

        String result = tool.taskOutput("tid-fail", false, null, makeCtx("user-1", "parent-conv-1"));
        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("status", "failed")
                .containsEntry("error", "agent boom");
    }

    @Test
    @DisplayName("Unknown taskId → error JSON without touching parent SSE")
    void taskOutputNotFound() throws Exception {
        when(asyncTaskService.findEntityByTaskId("tid-missing")).thenReturn(null);
        String result = tool.taskOutput("tid-missing", false, null, makeCtx("user-1", "parent-conv-1"));
        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("Task not found");
        verify(streamTracker, never()).broadcastObject(any(),
                eq("delegation_async_polled"), any());
    }

    @Test
    @DisplayName("Non-agent_delegate taskType (e.g. video_generation) → error JSON")
    void taskOutputWrongTaskType() throws Exception {
        AsyncTaskEntity entity = new AsyncTaskEntity();
        entity.setTaskId("tid-vid");
        entity.setTaskType("video_generation");
        entity.setStatus("running");
        when(asyncTaskService.findEntityByTaskId("tid-vid")).thenReturn(entity);

        String result = tool.taskOutput("tid-vid", false, null, makeCtx("user-1", "parent-conv-1"));
        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("not a delegate task");
    }

    @Test
    @DisplayName("block=true on a task that stays running for the full timeout returns status=running")
    void taskOutputBlockTimeout() throws Exception {
        AsyncTaskEntity entity = makeAsyncTask("tid-block", "running", "parent-conv-1", "user-1", null);
        when(asyncTaskService.findEntityByTaskId("tid-block")).thenReturn(entity);

        long start = System.currentTimeMillis();
        // 1s budget → poll loop runs ~2 iterations of 500ms before deadline.
        String result = tool.taskOutput("tid-block", true, 1, makeCtx("user-1", "parent-conv-1"));
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("status", "running");
        // Real-time guard: ≥ ~900 ms (loop ran) but well under the 120 s cap.
        assertThat(elapsed).isBetween(900L, 5_000L);
        // Initial read + at least one poll iteration.
        verify(asyncTaskService, atLeast(2)).findEntityByTaskId("tid-block");
    }

    // ---------- helpers ----------

    private static AgentEntity makeAgent(Long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        a.setEnabled(true);
        a.setWorkspaceId(1L);
        return a;
    }

    private AsyncTaskEntity makeAsyncTask(String taskId, String status, String parentConv,
                                          String createdBy, String resultJson) throws Exception {
        AsyncTaskEntity e = new AsyncTaskEntity();
        e.setTaskId(taskId);
        e.setTaskType("agent_delegate");
        e.setStatus(status);
        e.setCreatedBy(createdBy);
        e.setResultJson(resultJson);
        e.setProgress("running".equals(status) ? 50 : ("succeeded".equals(status) ? 100 : 0));
        e.setCreateTime(LocalDateTime.now().minusSeconds(5));
        e.setUpdateTime(LocalDateTime.now());
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("parentConversationId", parentConv);
        req.put("childConversationId", "child-x");
        req.put("childAgentId", 10L);
        req.put("task", "task");
        req.put("label", "");
        e.setRequestJson(objectMapper.writeValueAsString(req));
        return e;
    }

    private ToolContext makeCtx(String requester, String conversationId) {
        ChatOrigin origin = new ChatOrigin(
                1L, conversationId, requester, null, null, null, null, false, null, null, null);
        Map<String, Object> map = new HashMap<>();
        map.put(ChatOrigin.CTX_KEY, origin);
        return new ToolContext(map);
    }
}
