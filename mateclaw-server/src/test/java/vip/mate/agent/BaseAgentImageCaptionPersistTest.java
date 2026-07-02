package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.routing.MediaCaptionService;
import vip.mate.llm.routing.MultimodalRouter;
import vip.mate.llm.routing.model.MultimodalRoutingDecision;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #303: when a text-only primary model captions an uploaded image via the
 * vision sidecar, the caption must (1) be tailored to the user's actual question
 * and (2) be persisted back onto the message part, so later turns — which replay
 * user messages as text only — retain the image content instead of losing it.
 */
class BaseAgentImageCaptionPersistTest {

    private static final String DESCRIPTION = "图中是一段 NullPointerException 堆栈，发生在 UserService.login 第 42 行。";

    @Test
    @DisplayName("Sidecar caption is persisted onto the image part and folded into the prompt")
    void sidecarCaption_persistedAndInjected() {
        TestHarness h = newHarness();
        MessageContentPart image = imagePart();
        MessageEntity msg = userMessage();
        when(h.agent.conversationService.parseMessageParts(msg))
                .thenReturn(List.of(MessageContentPart.text("图里的报错是什么"), image));

        UserMessage result = h.agent.callBuildCurrentTurn(msg, "图里的报错是什么");

        // (1) caption stored on the part → survives into later turns
        assertEquals(DESCRIPTION, image.getCaption(), "caption must be written onto the image part");
        verify(h.agent.conversationService).updateMessageParts(eq(msg), any());
        // (2) caption folded into the current-turn prompt text
        assertTrue(result.getText().contains(DESCRIPTION), "caption must be injected into the prompt");
        assertTrue(result.getText().contains("[图片附件描述"), "caption must be wrapped in the attachment marker");
    }

    @Test
    @DisplayName("The user's text question is passed to the caption service")
    void userQuestion_passedToCaption() {
        TestHarness h = newHarness();
        MessageEntity msg = userMessage();
        when(h.agent.conversationService.parseMessageParts(msg))
                .thenReturn(List.of(MessageContentPart.text("报错的行号是多少"), imagePart()));

        h.agent.callBuildCurrentTurn(msg, "报错的行号是多少");

        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        verify(h.caption).caption(any(), any(), any(), question.capture());
        assertEquals("报错的行号是多少", question.getValue(),
                "the user's question (text part) must drive a context-aware caption");
    }

    @Test
    @DisplayName("Image-only message (no text part) → caption called with null question")
    void imageOnly_nullQuestion() {
        TestHarness h = newHarness();
        MessageEntity msg = userMessage();
        // WeChat Work image upload: only an image part, content placeholder "[图片]".
        when(h.agent.conversationService.parseMessageParts(msg))
                .thenReturn(List.of(imagePart()));

        h.agent.callBuildCurrentTurn(msg, "[图片]");

        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        verify(h.caption).caption(any(), any(), any(), question.capture());
        assertEquals(null, question.getValue(),
                "no text part → null question → caption falls back to generic description");
    }

    // ---------- scaffold ----------

    private static MessageContentPart imagePart() {
        MessageContentPart p = new MessageContentPart();
        p.setType("image");
        p.setContentType("image/png");
        p.setFileName("err.png");
        p.setMediaId("media-1");
        return p;
    }

    private static MessageEntity userMessage() {
        MessageEntity m = new MessageEntity();
        m.setId(1001L);
        m.setRole("user");
        m.setContent("[图片]");
        return m;
    }

    private TestHarness newHarness() {
        ConversationService conv = mock(ConversationService.class);
        MultimodalRouter router = mock(MultimodalRouter.class);
        MediaCaptionService caption = mock(MediaCaptionService.class);
        ModelConfigEntity sidecar = mock(ModelConfigEntity.class);

        when(router.route(any(), any())).thenReturn(
                MultimodalRoutingDecision.sidecar(sidecar,
                        EnumSet.of(ModelCapabilityService.Modality.VISION),
                        EnumSet.of(ModelCapabilityService.Modality.VISION)));
        when(caption.caption(any(), any(), any(), any()))
                .thenReturn(MediaCaptionService.CaptionResult.success(DESCRIPTION, 12L, false));

        TestAgent agent = new TestAgent(conv);
        agent.multimodalRouter = router;
        agent.mediaCaptionService = caption;
        agent.modelCapabilities = EnumSet.noneOf(ModelCapabilityService.Modality.class);
        agent.modelName = "text-only-model";
        agent.agentName = "test-agent";

        TestHarness h = new TestHarness();
        h.agent = agent;
        h.caption = caption;
        return h;
    }

    static class TestHarness {
        TestAgent agent;
        MediaCaptionService caption;
    }

    static class TestAgent extends BaseAgent {
        TestAgent(ConversationService conv) {
            super(null, conv);
        }

        UserMessage callBuildCurrentTurn(MessageEntity msg, String renderedContent) {
            return buildUserMessageForCurrentTurn(msg, renderedContent).userMessage();
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
