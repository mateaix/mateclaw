package vip.mate.skill.reflection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.service.SkillService;
import vip.mate.tool.builtin.SkillManageTool;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the deterministic gating and action-routing logic of
 * {@link SkillReflectionService} — cadence, tool-call floor, cooldown, the
 * maxActionsPerRun cap, and the "never delete" rule.
 */
class SkillReflectionServiceTest {

    private ConversationService conversationService;
    private SkillService skillService;
    private SkillManageTool skillManageTool;
    private ModelConfigService modelConfigService;
    private AgentGraphBuilder agentGraphBuilder;
    private SkillReflectionProperties properties;
    private SkillReflectionService service;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        skillService = mock(SkillService.class);
        skillManageTool = mock(SkillManageTool.class);
        modelConfigService = mock(ModelConfigService.class);
        agentGraphBuilder = mock(AgentGraphBuilder.class);
        properties = new SkillReflectionProperties();
        service = new SkillReflectionService(conversationService, skillService, skillManageTool,
                modelConfigService, agentGraphBuilder, properties, new ObjectMapper());

        when(skillService.listEnabledSkills()).thenReturn(List.of());
    }

    private void stubLlm(String json) {
        ChatModel chatModel = (ChatModel) (Prompt p) ->
                new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        when(agentGraphBuilder.buildRuntimeChatModel(any())).thenReturn(chatModel);
        when(modelConfigService.getDefaultModel()).thenReturn(null);
    }

    /** Build {@code turns} substantive user/assistant pairs. */
    private List<MessageEntity> transcriptWithTurns(int turns) {
        List<MessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            MessageEntity user = new MessageEntity();
            user.setRole("user");
            user.setContent("step " + i + ": how do I scaffold a spring boot module?");
            messages.add(user);
            MessageEntity assistant = new MessageEntity();
            assistant.setRole("assistant");
            assistant.setContent("step " + i + ": run mvn archetype, then add the starter, then ...");
            messages.add(assistant);
        }
        return messages;
    }

    @Test
    @DisplayName("disabled → no LLM call, no skill write")
    void disabledShortCircuits() {
        properties.setEnabled(false);
        service.maybeReflect(1L, "conv-1", 8);
        verify(conversationService, never()).listMessages(any());
        verify(skillManageTool, never()).skill_manage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("cadence gate: messageCount not on interval → skip")
    void cadenceGateSkips() {
        properties.setReviewTurnInterval(8);
        service.maybeReflect(1L, "conv-1", 7);
        verify(conversationService, never()).listMessages(any());
    }

    @Test
    @DisplayName("assistant-turn floor not met → no LLM call")
    void assistantTurnFloorSkips() {
        properties.setReviewTurnInterval(8);
        properties.setMinAssistantTurns(2);
        when(conversationService.listMessages("conv-1")).thenReturn(transcriptWithTurns(1));
        service.maybeReflect(1L, "conv-1", 8);
        verify(agentGraphBuilder, never()).buildRuntimeChatModel(any());
        verify(skillManageTool, never()).skill_manage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("happy path: a create action routes through skill_manage")
    void appliesCreateAction() {
        properties.setReviewTurnInterval(8);
        properties.setMinAssistantTurns(2);
        when(conversationService.listMessages("conv-1")).thenReturn(transcriptWithTurns(3));
        stubLlm("[{\"action\":\"create\",\"name\":\"spring-scaffold\",\"reason\":\"reusable\","
                + "\"content\":\"---\\nname: spring-scaffold\\n---\\n# X\"}]");
        when(skillManageTool.skill_manage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("Skill 'spring-scaffold' created successfully (security scan: PASSED).");

        service.maybeReflect(1L, "conv-1", 8);

        verify(skillManageTool, times(1))
                .skill_manage(eq("create"), eq("spring-scaffold"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("delete actions are ignored — reflection only creates/improves")
    void ignoresDelete() {
        properties.setReviewTurnInterval(8);
        properties.setMinAssistantTurns(2);
        when(conversationService.listMessages("conv-1")).thenReturn(transcriptWithTurns(3));
        stubLlm("[{\"action\":\"delete\",\"name\":\"old-skill\",\"reason\":\"stale\"}]");

        service.maybeReflect(1L, "conv-1", 8);

        verify(skillManageTool, never()).skill_manage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("maxActionsPerRun caps how many actions are applied")
    void capsActions() {
        properties.setReviewTurnInterval(8);
        properties.setMinAssistantTurns(2);
        properties.setMaxActionsPerRun(2);
        when(conversationService.listMessages("conv-1")).thenReturn(transcriptWithTurns(3));
        String body = "\"content\":\"---\\nname: s\\n---\\n# X\"";
        stubLlm("[{\"action\":\"create\",\"name\":\"s1\"," + body + "},"
                + "{\"action\":\"create\",\"name\":\"s2\"," + body + "},"
                + "{\"action\":\"create\",\"name\":\"s3\"," + body + "}]");
        when(skillManageTool.skill_manage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("created successfully");

        service.maybeReflect(1L, "conv-1", 8);

        verify(skillManageTool, times(2)).skill_manage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("cooldown blocks a second review for the same conversation")
    void cooldownBlocksSecondRun() {
        properties.setReviewTurnInterval(8);
        properties.setMinAssistantTurns(2);
        when(conversationService.listMessages("conv-1")).thenReturn(transcriptWithTurns(3));
        stubLlm("[]");

        service.maybeReflect(1L, "conv-1", 8);
        service.maybeReflect(1L, "conv-1", 16);

        // listMessages is only reached on the first (non-cooled-down) run.
        verify(conversationService, times(1)).listMessages("conv-1");
    }
}
