package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.tts.TtsService;
import vip.mate.workspace.conversation.ConversationService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelMagicCommandTest {

    // ==================== parse matrix ====================

    @Test
    @DisplayName("clear aliases are recognized as channel magic commands")
    void clearAliasesAreRecognized() {
        assertParsed("clear", ChannelMagicCommand.Type.CLEAR);
        assertParsed("/clear", ChannelMagicCommand.Type.CLEAR);
        assertParsed("  清空上下文  ", ChannelMagicCommand.Type.CLEAR);
        assertParsed("清理上下文", ChannelMagicCommand.Type.CLEAR);
        assertParsed("/reset", ChannelMagicCommand.Type.CLEAR);
        assertParsed("CLEAR", ChannelMagicCommand.Type.CLEAR);
    }

    @Test
    @DisplayName("new/help/status/stop aliases are recognized")
    void extendedAliasesAreRecognized() {
        assertParsed("/new", ChannelMagicCommand.Type.NEW);
        assertParsed("新会话", ChannelMagicCommand.Type.NEW);
        assertParsed("/新对话", ChannelMagicCommand.Type.NEW);
        assertParsed("/help", ChannelMagicCommand.Type.HELP);
        assertParsed("帮助", ChannelMagicCommand.Type.HELP);
        assertParsed("/status", ChannelMagicCommand.Type.STATUS);
        assertParsed("状态", ChannelMagicCommand.Type.STATUS);
        assertParsed("/stop", ChannelMagicCommand.Type.STOP);
        assertParsed("停止", ChannelMagicCommand.Type.STOP);
        assertParsed("stop", ChannelMagicCommand.Type.STOP);
    }

    @Test
    @DisplayName("model command is slash-only: bare word stays ordinary prose")
    void modelCommandIsSlashOnly() {
        assertParsed("/model", ChannelMagicCommand.Type.MODEL);
        assertParsed("/模型", ChannelMagicCommand.Type.MODEL);
        Optional<ChannelMagicCommand.Parsed> withArgs = ChannelMagicCommand.parse("/model qwen-max");
        assertTrue(withArgs.isPresent());
        assertEquals(ChannelMagicCommand.Type.MODEL, withArgs.get().type());
        assertEquals("qwen-max", withArgs.get().args());
        // "model"/"模型" are common standalone words — never commands bare.
        assertNotParsed("model");
        assertNotParsed("模型");
        assertNotParsed("模型是什么");
    }

    @Test
    @DisplayName("bare aliases with trailing text are ordinary prompts, not commands")
    void bareAliasesWithRemainderDoNotMatch() {
        assertNotParsed("clear 一下北京天气");
        assertNotParsed("请清理上下文后继续");
        assertNotParsed("stop the server");
        assertNotParsed("status report 写一份");
        assertNotParsed("帮助我写周报");
        assertNotParsed("new year plan");
        assertNotParsed("/approval approve abc123");
        assertNotParsed("");
        assertNotParsed(null);
    }

    @Test
    @DisplayName("slash-prefixed commands may carry trailing arguments")
    void slashCommandsCarryArgs() {
        Optional<ChannelMagicCommand.Parsed> parsed = ChannelMagicCommand.parse("/stop now please");
        assertTrue(parsed.isPresent());
        assertEquals(ChannelMagicCommand.Type.STOP, parsed.get().type());
        assertEquals("now please", parsed.get().args());
    }

    @Test
    @DisplayName("help text lists every registered command")
    void helpTextListsAllCommands() {
        String help = ChannelMagicCommand.helpText();
        for (String name : new String[]{"/clear", "/new", "/stop", "/status", "/model", "/help"}) {
            assertTrue(help.contains(name), "help text missing " + name);
        }
    }

    // ==================== router dispatch behavior ====================

    @Test
    @DisplayName("clear command wipes current channel conversation and does not call agent")
    void clearCommandClearsConversationWithoutCallingAgent() throws Exception {
        Fixture f = new Fixture();

        f.process("/clear");

        verify(f.conversationService).clearMessages("wecom:alice");
        // Confirmation must ride renderAndSend so adapters that pre-post a
        // "thinking..." placeholder (WeCom reply_stream) consume it — a plain
        // sendMessage leaves the placeholder bubble dangling forever.
        verify(f.adapter).renderAndSend(eq("reply-1"), contains("上下文已清理"));
        verify(f.adapter, never()).sendMessage(anyString(), anyString());
        f.verifyAgentNeverCalled();
    }

    @Test
    @DisplayName("new command clears conversation with its own confirmation")
    void newCommandClearsConversation() throws Exception {
        Fixture f = new Fixture();

        f.process("/new");

        verify(f.conversationService).clearMessages("wecom:alice");
        verify(f.adapter).renderAndSend(eq("reply-1"), contains("新会话"));
        f.verifyAgentNeverCalled();
    }

    @Test
    @DisplayName("stop command requests stream stop and reports whether anything was running")
    void stopCommandRequestsStop() throws Exception {
        Fixture f = new Fixture();
        when(f.streamTracker.requestStop("wecom:alice")).thenReturn(true);

        f.process("/stop");

        verify(f.streamTracker).requestStop("wecom:alice");
        verify(f.adapter).renderAndSend(eq("reply-1"), contains("已停止"));
        verify(f.conversationService, never()).clearMessages(anyString());
        f.verifyAgentNeverCalled();
    }

    @Test
    @DisplayName("stop command with no running task replies idle hint")
    void stopCommandNothingRunning() throws Exception {
        Fixture f = new Fixture();
        when(f.streamTracker.requestStop("wecom:alice")).thenReturn(false);

        f.process("/stop");

        verify(f.adapter).renderAndSend(eq("reply-1"), contains("没有进行中的任务"));
    }

    @Test
    @DisplayName("status command reports agent, message count, and running state")
    void statusCommandReportsState() throws Exception {
        Fixture f = new Fixture();
        AgentEntity agent = new AgentEntity();
        agent.setName("会议助理");
        agent.setModelName("qwen-max");
        when(f.agentService.getAgent(100L)).thenReturn(agent);
        when(f.conversationService.countMessages("wecom:alice")).thenReturn(12L);
        when(f.streamTracker.isRunning("wecom:alice")).thenReturn(true);

        f.process("/status");

        verify(f.adapter).renderAndSend(eq("reply-1"), argThat(text ->
                text.contains("会议助理") && text.contains("qwen-max")
                        && text.contains("12") && text.contains("进行中")));
        f.verifyAgentNeverCalled();
    }

    @Test
    @DisplayName("status command degrades gracefully when channel has no agent")
    void statusCommandWithoutAgent() throws Exception {
        Fixture f = new Fixture();
        f.channel.setAgentId(null);

        f.process("/status");

        verify(f.adapter).renderAndSend(eq("reply-1"), contains("未绑定"));
    }

    @Test
    @DisplayName("help command replies command list without touching conversation")
    void helpCommandRepliesList() throws Exception {
        Fixture f = new Fixture();

        f.process("/help");

        verify(f.adapter).renderAndSend(eq("reply-1"), contains("/clear"));
        verify(f.conversationService, never()).clearMessages(anyString());
        f.verifyAgentNeverCalled();
    }

    @Test
    @DisplayName("model command lists enabled models with pin/reset usage")
    void modelCommandListsModels() throws Exception {
        Fixture f = new Fixture();
        when(f.modelConfigService.listEnabledModels()).thenReturn(List.of(
                model("dashscope", "qwen-max"), model("anthropic", "claude-sonnet-5")));

        f.process("/model");

        verify(f.adapter).renderAndSend(eq("reply-1"), argThat(text ->
                text.contains("qwen-max") && text.contains("claude-sonnet-5")
                        && text.contains("/model reset")));
        f.verifyAgentNeverCalled();
    }

    @Test
    @DisplayName("model switch pins the matched model on the conversation (creating it first)")
    void modelSwitchPinsConversationModel() throws Exception {
        Fixture f = new Fixture();
        when(f.modelConfigService.listEnabledModels()).thenReturn(List.of(
                model("dashscope", "qwen-max"), model("anthropic", "claude-sonnet-5")));

        f.process("/model qwen-max");

        // Magic commands run before processMessage's get-or-create, so the
        // switch must ensure the row exists before pinning — otherwise a
        // /model sent as the very first message is silently lost.
        verify(f.conversationService).getOrCreateSharedConversation("wecom:alice", 100L, null);
        verify(f.conversationService).updateConversationModel("wecom:alice", "dashscope", "qwen-max");
        verify(f.adapter).renderAndSend(eq("reply-1"), contains("已切换"));
        f.verifyAgentNeverCalled();
    }

    @Test
    @DisplayName("ambiguous bare model name asks for a provider prefix instead of guessing")
    void modelSwitchAmbiguousAsksForPrefix() throws Exception {
        Fixture f = new Fixture();
        when(f.modelConfigService.listEnabledModels()).thenReturn(List.of(
                model("dashscope", "qwen-max"), model("mirror", "qwen-max")));

        f.process("/model qwen-max");

        verify(f.adapter).renderAndSend(eq("reply-1"), contains("多个 provider"));
        verify(f.conversationService, never()).updateConversationModel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("model list caps rows and points to keyword search")
    void modelListCapsRows() throws Exception {
        Fixture f = new Fixture();
        List<ModelConfigEntity> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(model("p" + i, "m" + i));
        }
        when(f.modelConfigService.listEnabledModels()).thenReturn(many);

        f.process("/model");

        // A 180+-row catalog would otherwise segment into several IM bubbles.
        verify(f.adapter).renderAndSend(eq("reply-1"), argThat(text ->
                text.contains("共 25 个") && !text.contains("- p24:m24")));
    }

    @Test
    @DisplayName("no exact match but partial hits → fuzzy suggestions, no pin")
    void modelSwitchFuzzySuggests() throws Exception {
        Fixture f = new Fixture();
        when(f.modelConfigService.listEnabledModels()).thenReturn(List.of(
                model("dashscope", "qwen-max"), model("dashscope", "qwen-plus"),
                model("openai", "gpt-4o")));

        f.process("/model qwen");

        verify(f.adapter).renderAndSend(eq("reply-1"), argThat(text ->
                text.contains("相近") && text.contains("qwen-max")
                        && text.contains("qwen-plus") && !text.contains("gpt-4o")));
        verify(f.conversationService, never()).updateConversationModel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("model reset clears the conversation pin")
    void modelResetClearsPin() throws Exception {
        Fixture f = new Fixture();

        f.process("/model reset");

        verify(f.conversationService).clearConversationModel("wecom:alice");
        verify(f.adapter).renderAndSend(eq("reply-1"), contains("恢复默认"));
        verify(f.conversationService, never()).updateConversationModel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("unknown model name replies with a lookup hint, never pins")
    void modelSwitchUnknownName() throws Exception {
        Fixture f = new Fixture();
        when(f.modelConfigService.listEnabledModels()).thenReturn(List.of(
                model("dashscope", "qwen-max")));

        f.process("/model gpt-99");

        verify(f.adapter).renderAndSend(eq("reply-1"), contains("未找到"));
        verify(f.conversationService, never()).updateConversationModel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("model switch without a bound agent replies binding hint")
    void modelSwitchWithoutAgent() throws Exception {
        Fixture f = new Fixture();
        f.channel.setAgentId(null);
        when(f.modelConfigService.listEnabledModels()).thenReturn(List.of(
                model("dashscope", "qwen-max")));

        f.process("/model qwen-max");

        verify(f.adapter).renderAndSend(eq("reply-1"), contains("未绑定"));
        verify(f.conversationService, never()).updateConversationModel(anyString(), anyString(), anyString());
    }

    // ==================== helpers ====================

    private static ModelConfigEntity model(String provider, String name) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setProvider(provider);
        m.setModelName(name);
        return m;
    }

    private static void assertParsed(String text, ChannelMagicCommand.Type expected) {
        Optional<ChannelMagicCommand.Parsed> parsed = ChannelMagicCommand.parse(text);
        assertTrue(parsed.isPresent(), "expected command match for: " + text);
        assertEquals(expected, parsed.get().type(), "wrong type for: " + text);
    }

    private static void assertNotParsed(String text) {
        assertTrue(ChannelMagicCommand.parse(text).isEmpty(),
                "expected no command match for: " + text);
    }

    /** Mocks + router wiring shared by the dispatch tests. */
    private static final class Fixture {
        final AgentService agentService = mock(AgentService.class);
        final ConversationService conversationService = mock(ConversationService.class);
        final ChatStreamTracker streamTracker = mock(ChatStreamTracker.class);
        final ModelConfigService modelConfigService = mock(ModelConfigService.class);
        final ChannelAdapter adapter = mock(ChannelAdapter.class);
        final ChannelEntity channel = new ChannelEntity();
        final ChannelMessageRouter router;

        Fixture() throws Exception {
            ChannelService channelService = mock(ChannelService.class);
            ChannelSessionStore channelSessionStore = mock(ChannelSessionStore.class);
            ApprovalWorkflowService approvalService = mock(ApprovalWorkflowService.class);
            ApprovalNotificationService approvalNotificationService = mock(ApprovalNotificationService.class);
            ConversationCompletionPublisher completionPublisher = mock(ConversationCompletionPublisher.class);
            TtsService ttsService = mock(TtsService.class);
            ChannelChatOriginFactory chatOriginFactory = mock(ChannelChatOriginFactory.class);
            ChannelErrorClassifier errorClassifier = mock(ChannelErrorClassifier.class);
            router = new ChannelMessageRouter(agentService, conversationService,
                    channelService, channelSessionStore, approvalService, approvalNotificationService,
                    completionPublisher, ttsService, new ObjectMapper(), streamTracker,
                    chatOriginFactory, errorClassifier);
            when(adapter.getChannelType()).thenReturn("wecom");
            channel.setAgentId(100L);
            // modelConfigService is field-injected on the real router (optional
            // dep); mirror that wiring here via reflection.
            Field mcs = ChannelMessageRouter.class.getDeclaredField("modelConfigService");
            mcs.setAccessible(true);
            mcs.set(router, modelConfigService);
        }

        void process(String content) throws Exception {
            ChannelMessage message = ChannelMessage.builder()
                    .senderId("alice")
                    .replyToken("reply-1")
                    .content(content)
                    .build();
            Method process = ChannelMessageRouter.class.getDeclaredMethod(
                    "processMessage", ChannelMessage.class, ChannelAdapter.class,
                    ChannelEntity.class, String.class);
            process.setAccessible(true);
            process.invoke(router, message, adapter, channel, "wecom:alice");
        }

        void verifyAgentNeverCalled() {
            verify(conversationService, never()).saveMessage(anyString(), anyString(), anyString(), any(), anyString());
            verify(agentService, never()).chatStructuredStream(
                    anyLong(), anyString(), anyString(), anyString(), any(ChatOrigin.class));
        }
    }
}
