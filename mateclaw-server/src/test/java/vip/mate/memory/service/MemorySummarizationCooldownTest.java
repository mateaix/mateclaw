package vip.mate.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.MemoryProperties;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.document.WorkspaceFileService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemorySummarizationCooldownTest {

    private ConversationService conversations;
    private AgentGraphBuilder graphBuilder;
    private ChatModel chatModel;
    private MemorySummarizationService service;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationService.class);
        WorkspaceFileService files = mock(WorkspaceFileService.class);
        ModelConfigService models = mock(ModelConfigService.class);
        graphBuilder = mock(AgentGraphBuilder.class);
        chatModel = mock(ChatModel.class);
        MemoryProperties properties = new MemoryProperties();
        properties.setCooldownMinutes(60);
        properties.setMinMessagesForSummarize(4);
        when(models.getDefaultModel()).thenReturn(new ModelConfigEntity());
        when(graphBuilder.buildRuntimeChatModel(any())).thenReturn(chatModel);
        service = new MemorySummarizationService(conversations, files, models, graphBuilder,
                properties, new ObjectMapper(), mock(StructuredMemoryService.class));
    }

    @Test
    void explicitRememberBypassesMessageMinimumAndCooldown() {
        when(conversations.listMessages("explicit")).thenReturn(List.of(
                message("user", "请记住：这个项目默认使用 PostgreSQL"),
                message("assistant", "已记录。")));
        when(chatModel.call(any(Prompt.class))).thenReturn(noUpdate());

        service.analyzeAndUpdateMemory(1L, "explicit");
        service.analyzeAndUpdateMemory(1L, "explicit");

        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void failedAnalysisDoesNotStartCooldown() {
        when(conversations.listMessages("normal")).thenReturn(List.of(
                message("user", "我长期偏好简洁回答"),
                message("assistant", "了解。"),
                message("user", "今后都请保持这个风格"),
                message("assistant", "好的。")));
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("temporary outage"))
                .thenReturn(noUpdate());

        service.analyzeAndUpdateMemory(1L, "normal");
        service.analyzeAndUpdateMemory(1L, "normal");

        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    private static ChatResponse noUpdate() {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"should_update\":false,\"reason\":\"none\"}"))));
    }

    private static MessageEntity message(String role, String content) {
        MessageEntity message = new MessageEntity();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
