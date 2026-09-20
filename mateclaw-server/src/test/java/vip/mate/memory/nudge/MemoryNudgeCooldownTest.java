package vip.mate.memory.nudge;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import vip.mate.memory.service.StructuredMemoryService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryNudgeCooldownTest {

    @Test
    void failedParseDoesNotStartCooldown() {
        ConversationService conversations = mock(ConversationService.class);
        StructuredMemoryService structured = mock(StructuredMemoryService.class);
        ModelConfigService models = mock(ModelConfigService.class);
        AgentGraphBuilder graphBuilder = mock(AgentGraphBuilder.class);
        ChatModel chatModel = mock(ChatModel.class);
        MemoryProperties properties = new MemoryProperties();
        properties.setNudgeEnabled(true);
        properties.setNudgeTurnInterval(1);
        properties.setNudgeCooldownMinutes(60);
        when(conversations.listMessages("conversation")).thenReturn(List.of(
                message("user", "one"), message("assistant", "two"),
                message("user", "three"), message("assistant", "four")));
        when(structured.buildMemoryBlock(1L, null)).thenReturn("");
        when(models.getDefaultModel()).thenReturn(new ModelConfigEntity());
        when(graphBuilder.buildRuntimeChatModel(any())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("not-json"))
                .thenReturn(response("[]"));
        MemoryNudgeService service = new MemoryNudgeService(conversations, structured, models,
                graphBuilder, properties, new ObjectMapper());

        service.maybeNudge(1L, "conversation", 4);
        service.maybeNudge(1L, "conversation", 4);

        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    private static ChatResponse response(String body) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(body))));
    }

    private static MessageEntity message(String role, String content) {
        MessageEntity message = new MessageEntity();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
