package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #303 follow-up: a vision-capable model replays history as text only, so a
 * follow-up question about an earlier image was answered blind. The current turn
 * must re-attach the most recent image so the model actually re-sees it.
 */
class BaseAgentCarryRecentImageTest {

    @Test
    @DisplayName("Vision model + follow-up with no image → most recent image is carried into the turn")
    void followUp_carriesRecentImage() throws Exception {
        Path img = Files.createTempFile("carry-test", ".jpg");
        Files.write(img, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        try {
            TestAgent agent = visionAgent();

            MessageEntity imgTurn = userMsg("看看这张图");
            MessageContentPart imagePart = imagePart(img.toAbsolutePath().toString());
            MessageEntity asst = assistantMsg("图里是手写的字");
            MessageEntity followUp = userMsg("左上角有没有小字");

            List<MessageEntity> history = List.of(imgTurn, asst, followUp);
            when(agent.conversationService.listMessages("c1")).thenReturn(history);
            when(agent.conversationService.renderMessageContent(followUp)).thenReturn("左上角有没有小字");
            when(agent.conversationService.parseMessageParts(imgTurn)).thenReturn(List.of(imagePart));
            when(agent.conversationService.parseMessageParts(followUp)).thenReturn(List.of());
            when(agent.conversationService.parseMessageParts(asst)).thenReturn(List.of());

            UserMessage result = agent.callBuildCurrent("c1", "左上角有没有小字");

            assertTrue(result.getMedia() != null && result.getMedia().size() == 1,
                    "the recent image must be re-attached to the follow-up turn");
            assertTrue(result.getText().contains("较早发送的"),
                    "a note must explain the carried image to the model");
        } finally {
            Files.deleteIfExists(img);
        }
    }

    @Test
    @DisplayName("Text-only model → no image carried (relies on persisted caption instead)")
    void textOnlyModel_doesNotCarry() throws Exception {
        Path img = Files.createTempFile("carry-test", ".jpg");
        Files.write(img, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        try {
            TestAgent agent = newAgent(EnumSet.of(ModelCapabilityService.Modality.TEXT));
            MessageEntity imgTurn = userMsg("看看这张图");
            MessageEntity followUp = userMsg("左上角有没有小字");
            List<MessageEntity> history = List.of(imgTurn, followUp);
            when(agent.conversationService.listMessages("c1")).thenReturn(history);
            when(agent.conversationService.renderMessageContent(followUp)).thenReturn("左上角有没有小字");
            when(agent.conversationService.parseMessageParts(any())).thenReturn(List.of());

            UserMessage result = agent.callBuildCurrent("c1", "左上角有没有小字");

            assertTrue(result.getMedia() == null || result.getMedia().isEmpty(),
                    "text-only model must not get raw image bytes carried over");
        } finally {
            Files.deleteIfExists(img);
        }
    }

    @Test
    @DisplayName("Current turn already has an image → nothing extra carried")
    void currentTurnHasImage_noCarry() throws Exception {
        Path older = Files.createTempFile("carry-old", ".jpg");
        Path now = Files.createTempFile("carry-now", ".jpg");
        Files.write(older, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        Files.write(now, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        try {
            TestAgent agent = visionAgent();
            MessageEntity oldTurn = userMsg("第一张");
            MessageEntity curTurn = userMsg("第二张");
            List<MessageEntity> history = List.of(oldTurn, curTurn);
            when(agent.conversationService.listMessages("c1")).thenReturn(history);
            when(agent.conversationService.renderMessageContent(curTurn)).thenReturn("第二张");
            when(agent.conversationService.parseMessageParts(oldTurn))
                    .thenReturn(List.of(imagePart(older.toAbsolutePath().toString())));
            when(agent.conversationService.parseMessageParts(curTurn))
                    .thenReturn(List.of(imagePart(now.toAbsolutePath().toString())));

            UserMessage result = agent.callBuildCurrent("c1", "第二张");

            assertTrue(result.getMedia() != null && result.getMedia().size() == 1,
                    "only the current turn's own image should be present — no extra carry");
            assertFalse(result.getText().contains("较早发送的"),
                    "no carry note when the current turn already has an image");
        } finally {
            Files.deleteIfExists(older);
            Files.deleteIfExists(now);
        }
    }

    // ---------- scaffold ----------

    private static MessageContentPart imagePart(String path) {
        MessageContentPart p = new MessageContentPart();
        p.setType("image");
        p.setContentType("image/jpeg");
        p.setFileName("image.jpg");
        p.setPath(path);
        return p;
    }

    private static MessageEntity userMsg(String content) {
        MessageEntity m = new MessageEntity();
        m.setRole("user");
        m.setContent(content);
        return m;
    }

    private static MessageEntity assistantMsg(String content) {
        MessageEntity m = new MessageEntity();
        m.setRole("assistant");
        m.setContent(content);
        return m;
    }

    private static TestAgent visionAgent() {
        return newAgent(EnumSet.of(ModelCapabilityService.Modality.VISION, ModelCapabilityService.Modality.TEXT));
    }

    private static TestAgent newAgent(EnumSet<ModelCapabilityService.Modality> caps) {
        ConversationService conv = mock(ConversationService.class);
        TestAgent agent = new TestAgent(conv);
        agent.modelCapabilities = caps;
        agent.modelName = "test-model";
        agent.agentName = "test-agent";
        return agent;
    }

    static class TestAgent extends BaseAgent {
        TestAgent(ConversationService conv) {
            super(null, conv);
        }

        UserMessage callBuildCurrent(String conversationId, String text) {
            return buildCurrentUserMessageWithRouting(conversationId, text).userMessage();
        }

        @Override
        public String chat(String userMessage, String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public reactor.core.publisher.Flux<String> chatStream(String userMessage, String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String execute(String goal, String conversationId) {
            throw new UnsupportedOperationException();
        }
    }
}
