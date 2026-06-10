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
import static org.mockito.Mockito.when;

/**
 * Attribution gate for {@code taskOutput}. The persistent {@code mate_async_task}
 * row carries both {@code created_by} (the requester) and a JSON blob whose
 * {@code parentConversationId} field anchors the task to one conversation —
 * any mismatch against the caller's current {@link ChatOrigin} must short-circuit
 * to {@code Forbidden} before the row's body / result can leak.
 * <p>
 * Three scenarios make up the threat model:
 * <ul>
 *   <li>Cross-user — Alice's taskId is read by Bob in the same conversation.</li>
 *   <li>Cross-conversation — Alice reads her own taskId from a different
 *       conversation than the one that spawned it.</li>
 *   <li>Already-succeeded — same as above, but the row is terminal with a
 *       non-empty {@code result_json}; the failure mode here would leak the
 *       result body itself.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DelegateAsyncTaskOutputAttributionTest {

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
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("(a) Cross-user: task created by other-user → Forbidden, no result leaked")
    void crossUserTaskIdForbidden() throws Exception {
        // Task created by `other-user` in conv-shared.
        AsyncTaskEntity entity = makeAsyncTask("tid-cross-user", "running",
                "conv-shared", "other-user", null);
        when(asyncTaskService.findEntityByTaskId("tid-cross-user")).thenReturn(entity);

        // Caller is user-1, sitting in conv-shared (so parentConv matches —
        // only the user attribution should reject this).
        ToolExecutionContext.set("conv-shared", "user-1");
        String result = tool.taskOutput("tid-cross-user", false, null,
                makeCtx("user-1", "conv-shared"));

        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("current user");
    }

    @Test
    @DisplayName("(b) Same-user different conversation: → Forbidden on conversation gate")
    void sameUserDifferentConversationForbidden() throws Exception {
        AsyncTaskEntity entity = makeAsyncTask("tid-cross-conv", "running",
                "conv-A", "user-1", null);
        when(asyncTaskService.findEntityByTaskId("tid-cross-conv")).thenReturn(entity);

        // user-1 is asking from conv-B; the task belongs to conv-A.
        ToolExecutionContext.set("conv-B", "user-1");
        String result = tool.taskOutput("tid-cross-conv", false, null,
                makeCtx("user-1", "conv-B"));

        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("current conversation");
    }

    @Test
    @DisplayName("(c) Already-succeeded task accessed from wrong parent → Forbidden, no result body leaked")
    void succeededTaskWrongParentForbidden() throws Exception {
        // Succeeded row carries a non-empty result_json — exactly the body we
        // must NOT echo back to a stranger guessing taskIds.
        AsyncTaskEntity entity = makeAsyncTask("tid-done", "succeeded",
                "conv-A", "user-1", "SECRET-ANSWER-PAYLOAD");
        when(asyncTaskService.findEntityByTaskId("tid-done")).thenReturn(entity);

        ToolExecutionContext.set("conv-B", "user-1");
        String result = tool.taskOutput("tid-done", false, null,
                makeCtx("user-1", "conv-B"));

        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("current conversation");
        // Critical: the result body must not appear anywhere in the response.
        assertThat(result).doesNotContain("SECRET-ANSWER-PAYLOAD");
    }

    @Test
    @DisplayName("Legitimate caller (matching user + conversation) is allowed through")
    void legitimateCallerAllowed() throws Exception {
        AsyncTaskEntity entity = makeAsyncTask("tid-ok", "succeeded",
                "conv-mine", "user-1", "valid result");
        when(asyncTaskService.findEntityByTaskId("tid-ok")).thenReturn(entity);

        ToolExecutionContext.set("conv-mine", "user-1");
        String result = tool.taskOutput("tid-ok", false, null,
                makeCtx("user-1", "conv-mine"));

        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("status", "succeeded")
                .containsEntry("result", "valid result");
    }

    @Test
    @DisplayName("Empty parentConversationId in request_json → Forbidden (defensive)")
    void emptyParentInPayloadForbidden() throws Exception {
        AsyncTaskEntity entity = new AsyncTaskEntity();
        entity.setTaskId("tid-empty-parent");
        entity.setTaskType("agent_delegate");
        entity.setStatus("running");
        entity.setCreatedBy("user-1");
        // request_json with empty parentConversationId — should never happen
        // in practice but the gate must still close.
        entity.setRequestJson("{\"parentConversationId\":\"\",\"childConversationId\":\"child-x\"}");
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        when(asyncTaskService.findEntityByTaskId("tid-empty-parent")).thenReturn(entity);

        ToolExecutionContext.set("conv-X", "user-1");
        String result = tool.taskOutput("tid-empty-parent", false, null,
                makeCtx("user-1", "conv-X"));

        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertThat(parsed).containsEntry("error", true);
        assertThat((String) parsed.get("message")).contains("current conversation");
    }

    // ---------- helpers ----------

    private AsyncTaskEntity makeAsyncTask(String taskId, String status, String parentConv,
                                          String createdBy, String resultJson) throws Exception {
        AsyncTaskEntity e = new AsyncTaskEntity();
        e.setTaskId(taskId);
        e.setTaskType("agent_delegate");
        e.setStatus(status);
        e.setCreatedBy(createdBy);
        e.setResultJson(resultJson);
        e.setProgress("succeeded".equals(status) ? 100 : 50);
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
                1L, conversationId, requester, null, null, null, null, false, null, null, null, null);
        Map<String, Object> map = new HashMap<>();
        map.put(ChatOrigin.CTX_KEY, origin);
        return new ToolContext(map);
    }
}
