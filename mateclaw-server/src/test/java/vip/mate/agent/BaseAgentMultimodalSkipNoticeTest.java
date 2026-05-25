package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #44 regression: when a video attachment cannot be passed to the model
 * (because the agent's resolved {@link ModelCapabilityService.Modality#VIDEO}
 * capability is absent), the user message must include a system notice listing
 * the skipped attachment and instructing the agent NOT to invent a tool call to
 * read it.
 *
 * <p>The original bug: silent skip ({@code log.debug} only) → agent saw
 * {@code [附件] xxx.mp4} placeholder text in history but no actual media → it
 * picked {@code BrowserUseTool} or similar to "open" the file, which never
 * produced useful results.
 *
 * <p>These tests pin the contract that the skip path mutates the prompt text,
 * not just a log line.
 *
 * <p>Issue #87 update: the previous "禁止调用任何工具" sentence is no longer
 * emitted unconditionally — when the agent has any media-capable tool bound,
 * the LLM is allowed to delegate to it. With no tools (this test scaffold's
 * default), the notice falls back to a "switch models" suggestion only.
 */
class BaseAgentMultimodalSkipNoticeTest {

    @Test
    @DisplayName("Video attachment + model lacks VIDEO capability → skipped, system notice in prompt text")
    void videoSkipped_emitsSystemNotice() {
        TestAgent agent = newAgentWithCaps(EnumSet.noneOf(ModelCapabilityService.Modality.class));
        MessageEntity msg = userMessage("看看这段视频");
        when(agent.conversationService.parseMessageParts(msg))
                .thenReturn(List.of(MessageContentPart.video("media-1", "demo.mp4")));

        UserMessage result = agent.callBuildUserMessage(msg, "看看这段视频");

        assertNotNull(result);
        String text = result.getText();
        assertTrue(text.contains("[系统提示]"),
                "skipped video must surface a system notice in the prompt text — issue #44");
        assertTrue(text.contains("demo.mp4"),
                "notice must name the skipped file so the agent can tell the user");
        assertTrue(text.contains("不支持视频输入"),
                "reason string must name the modality the model cannot consume");
        assertTrue(text.contains("建议切换"),
                "notice must tell the agent to recommend switching models when no media tool is bound");
        assertFalse(text.contains("不要调用任何工具"),
                "issue #87: the hard tool ban must be gone — bound media tools should still be usable");
        assertTrue(result.getMedia() == null || result.getMedia().isEmpty(),
                "video must NOT be injected as Media when capability is absent");
    }

    @Test
    @DisplayName("Image attachment + model lacks VISION capability → skipped, system notice")
    void imageSkipped_emitsSystemNotice() {
        // Regression for the "GLM-5-Turbo + image upload" failure: when a user
        // uploads an image to a text-only model, we used to pass the image through
        // anyway and let the API 400. Now we skip + notify, same as the video gate.
        TestAgent agent = newAgentWithCaps(EnumSet.noneOf(ModelCapabilityService.Modality.class));
        MessageEntity msg = userMessage("看看这张图");
        when(agent.conversationService.parseMessageParts(msg))
                .thenReturn(List.of(MessageContentPart.file("media-1", "poster.png", "image/png")));

        UserMessage result = agent.callBuildUserMessage(msg, "看看这张图");

        String text = result.getText();
        assertTrue(text.contains("poster.png"));
        assertTrue(text.contains("不支持图片输入"),
                "vision-skip notice must use 不支持图片输入 wording");
        assertTrue(result.getMedia() == null || result.getMedia().isEmpty(),
                "image must NOT be injected when model has no VISION capability");
    }

    @Test
    @DisplayName("No attachments → no system notice, prompt text unchanged")
    void noAttachments_noNoticeAdded() {
        TestAgent agent = newAgentWithCaps(EnumSet.noneOf(ModelCapabilityService.Modality.class));
        MessageEntity msg = userMessage("hello");
        when(agent.conversationService.parseMessageParts(msg)).thenReturn(List.of());

        UserMessage result = agent.callBuildUserMessage(msg, "hello");

        assertFalse(result.getText().contains("[系统提示]"),
                "no skipped attachments → no notice; clean prompt for normal text-only turns");
    }

    @Test
    @DisplayName("Capable model + video → no system notice (notice only fires on actual skip)")
    void videoCapable_noNotice_attemptInjection() {
        // VIDEO capability present → no skip-on-capability-grounds. The injection itself
        // may still fail downstream (file path doesn't exist in this test) — when that
        // happens, the file-not-found / load-failure branch surfaces its OWN notice with
        // a different reason string. We assert the capability-skip reason is absent here.
        TestAgent agent = newAgentWithCaps(
                EnumSet.of(ModelCapabilityService.Modality.VIDEO, ModelCapabilityService.Modality.TEXT));
        MessageEntity msg = userMessage("分析视频");
        when(agent.conversationService.parseMessageParts(msg))
                .thenReturn(List.of(MessageContentPart.video("media-1", "ok.mp4")));

        UserMessage result = agent.callBuildUserMessage(msg, "分析视频");

        assertFalse(result.getText().contains("不支持视频输入"),
                "capable model must not be flagged as missing video capability");
    }

    @Test
    @DisplayName("History replay (injectMedia=false) returns text-only — no Media accumulation")
    void historyReplay_dropsMedia() {
        // Regression for Zhipu GLM-5V "code:1210 input videos exceeds limit": each
        // historical user message previously re-injected its video Media on every
        // turn, so a 2-turn conversation hit the per-request 1-video cap. The
        // history path must drop Media even when the model supports video.
        TestAgent agent = newAgentWithCaps(
                EnumSet.of(ModelCapabilityService.Modality.VIDEO, ModelCapabilityService.Modality.TEXT));
        MessageEntity msg = userMessage("上一轮的视频");
        // parseMessageParts is irrelevant when injectMedia=false; verify by NOT stubbing it.

        UserMessage result = agent.callBuildUserMessage(msg, "上一轮的视频", false);

        assertTrue(result.getMedia() == null || result.getMedia().isEmpty(),
                "history replay must NOT carry Media — even capable models cap video count per request");
        assertFalse(result.getText().contains("[系统提示]"),
                "history replay must NOT add the skip notice — the skip notice is a current-turn concern");
    }

    // ---------- Test scaffold ----------

    private static MessageEntity userMessage(String content) {
        MessageEntity m = new MessageEntity();
        m.setRole("user");
        m.setContent(content);
        return m;
    }

    private static TestAgent newAgentWithCaps(EnumSet<ModelCapabilityService.Modality> caps) {
        ConversationService conv = mock(ConversationService.class);
        TestAgent agent = new TestAgent(conv);
        agent.modelCapabilities = caps;
        agent.modelName = "test-model";
        agent.agentName = "test-agent";
        return agent;
    }

    /**
     * Minimal concrete BaseAgent for testing buildUserMessage. The abstract
     * chat / chatStream / execute methods are stubbed because buildUserMessage
     * does not depend on them.
     */
    static class TestAgent extends BaseAgent {
        TestAgent(ConversationService conv) {
            super(null, conv);
        }

        UserMessage callBuildUserMessage(MessageEntity msg, String renderedContent) {
            return buildUserMessage(msg, renderedContent);
        }

        UserMessage callBuildUserMessage(MessageEntity msg, String renderedContent, boolean injectMedia) {
            return buildUserMessage(msg, renderedContent, injectMedia);
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
