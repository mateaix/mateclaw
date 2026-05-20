package vip.mate.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.context.ChannelTarget;
import vip.mate.agent.context.ChatOrigin;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-063r §2.12: Memento round-trip for cross-restart approval replay.
 *
 * <p>Exercises {@link ApprovalWorkflowService#restoreChatOrigin(String)}
 * directly — independent of the DB layer — to pin the serialization
 * contract: full round-trip preserves every field, and a corrupt or null
 * payload falls back to {@link ChatOrigin#EMPTY} rather than throwing.
 */
class ApprovalReplayContinuityTest {

    private ApprovalWorkflowService workflow;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Don't run the @PostConstruct GC scheduler — only need restoreChatOrigin.
        workflow = new ApprovalWorkflowService(null, null, objectMapper, null);
        // Inject objectMapper via reflection so the helper does not NPE.
        ReflectionTestUtils.setField(workflow, "objectMapper", objectMapper);
    }

    @Test
    void chatOrigin_persistedAndRestored_preservesAllFields() throws Exception {
        ChatOrigin original = new ChatOrigin(
                /* agentId */ 7L,
                /* conversationId */ "wechat:chat-42",
                /* requesterId */ "u-123",
                /* workspaceId */ 5L,
                /* workspaceBasePath */ "/data/ws/5",
                /* channelId */ 9L,
                /* channelTarget */ new ChannelTarget("group-a", "thread-1", "bot-001"),
                /* cronOrigin */ false,
                /* senderName */ "Alice",
                /* channelType */ "wecom",
                /* chatId */ "group-a");

        String json = objectMapper.writeValueAsString(original);
        ChatOrigin restored = workflow.restoreChatOrigin(json);

        assertEquals(original, restored,
                "Memento round-trip must preserve every field — RFC-063r §2.12");
    }

    @Test
    void chatOrigin_corruptJson_fallsBackToEmpty() {
        String corrupt = "{\"this is not\":valid JSON";
        ChatOrigin restored = workflow.restoreChatOrigin(corrupt);
        assertSame(ChatOrigin.EMPTY, restored,
                "Corrupt payload must fall back to EMPTY without throwing");
    }

    @Test
    void chatOrigin_nullPayload_returnsEmpty() {
        assertSame(ChatOrigin.EMPTY, workflow.restoreChatOrigin(null));
    }

    @Test
    void chatOrigin_blankPayload_returnsEmpty() {
        assertSame(ChatOrigin.EMPTY, workflow.restoreChatOrigin("   "));
    }

    @Test
    void chatOrigin_unknownFieldsInJson_areTolerated() throws Exception {
        // Forward-compat: a payload written by a future build with extra
        // fields must still restore the known fields.
        String json = """
                {
                  "agentId": 7,
                  "conversationId": "wechat:chat-42",
                  "requesterId": "u-123",
                  "workspaceId": 5,
                  "workspaceBasePath": "/data/ws/5",
                  "channelId": 9,
                  "channelTarget": {"targetId":"group-a","threadId":null,"accountId":null,"newField":"x"},
                  "futureTopLevelField": "y"
                }
                """;
        ChatOrigin restored = workflow.restoreChatOrigin(json);
        assertEquals(7L, restored.agentId());
        assertEquals("wechat:chat-42", restored.conversationId());
        assertEquals("group-a", restored.channelTarget().targetId());
    }
}
