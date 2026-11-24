package vip.mate.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.service.SkillService;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentGenerationServiceTest {
    private AvailableToolService tools;
    private ChatModel model;
    private AgentGenerationService service;

    @BeforeEach
    void setUp() {
        var configs = mock(ModelConfigService.class);
        var builder = mock(AgentGraphBuilder.class);
        tools = mock(AvailableToolService.class);
        var skills = mock(SkillService.class);
        var kbs = mock(WikiKnowledgeBaseService.class);
        var config = new ModelConfigEntity();
        model = mock(ChatModel.class);
        when(configs.getDefaultModel()).thenReturn(config);
        when(builder.buildRuntimeChatModel(config)).thenReturn(model);
        when(tools.listAvailable(1L)).thenReturn(List.of());
        when(skills.listEnabledSkills(1L)).thenReturn(List.of());
        when(kbs.listByWorkspace(1L)).thenReturn(List.of());
        service = new AgentGenerationService(configs, builder, new ObjectMapper(), tools, skills, kbs);
    }

    private void response(String text) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(text)))));
    }

    @org.junit.jupiter.api.Test
    void generationUsesOnlyWorkspaceVisibleToolCatalog() {
        var local = vip.mate.tool.model.AvailableToolDTO.builder()
                .name("local_tool").description("local_description").available(true).build();
        var foreign = vip.mate.tool.model.AvailableToolDTO.builder()
                .name("foreign_tool").description("foreign_secret").available(true).build();
        when(tools.listAvailable(42L)).thenReturn(List.of(local));
        when(tools.listAvailable()).thenReturn(List.of(local, foreign));
        response("{\"name\":\"helper\"}");

        service.generateDraft("helper", 42L);

        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertTrue(prompt.getValue().getContents().contains("local_description"));
        assertFalse(prompt.getValue().getContents().contains("foreign_secret"));
        verify(tools).listAvailable(42L);
        verify(tools, never()).listAvailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"周报助手\"}",
            "```json\n{\"name\":\"周报助手\"}\n```",
            "<think>先考虑配置</think>\n{\"name\":\"周报助手\"}",
            "<think>{\"name\":\"思考中的示例\"}</think>\n```json\n{\"name\":\"周报助手\"}\n```",
            " \n<think>第一步</think>\n<think>第二步</think>\n{\"name\":\"周报助手\"}"
    })
    void acceptsFinalJsonAfterOptionalThinking(String text) {
        response(text);
        assertEquals("周报助手", service.generateDraft("跨部门周报汇总", 1L).getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"助手\",\"systemPrompt\":\"保留 <think>示例</think> 原文\"}",
            "<think>规划</think>\n{\"name\":\"助手\",\"systemPrompt\":\"保留 <think>示例</think> 原文\"}"
    })
    void preservesLiteralTagsInsideJsonStrings(String text) {
        response(text);
        assertEquals("保留 <think>示例</think> 原文", service.generateDraft("助手", 1L).getSystemPrompt());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "<think>{\"name\":\"未完成思考\"}",
            "<think>{\"name\":\"只有思考\"}</think>",
            "<think>规划</think>\n{\"name\":", "[]",
            "说明文字 {\"name\":\"不应猜测提取\"}"
    })
    void rejectsMissingOrMalformedFinalObject(String text) {
        response(text);
        assertThrows(MateClawException.class, () -> service.generateDraft("助手", 1L));
    }
}
