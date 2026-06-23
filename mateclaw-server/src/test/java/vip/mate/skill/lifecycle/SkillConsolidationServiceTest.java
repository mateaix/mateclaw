package vip.mate.skill.lifecycle;

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
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;
import vip.mate.tool.builtin.SkillManageTool;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the deterministic behaviour of {@link SkillConsolidationService}:
 * the opt-in gate, the minimum-candidate floor, dry-run vs applied, the
 * "only archive in-scope skills" guard, and the real-merge count rule.
 */
class SkillConsolidationServiceTest {

    private SkillService skillService;
    private SkillManageTool skillManageTool;
    private SkillLifecycleService lifecycleService;
    private ModelConfigService modelConfigService;
    private AgentGraphBuilder agentGraphBuilder;
    private SkillLifecycleProperties properties;
    private SkillConsolidationService service;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        skillManageTool = mock(SkillManageTool.class);
        lifecycleService = mock(SkillLifecycleService.class);
        modelConfigService = mock(ModelConfigService.class);
        agentGraphBuilder = mock(AgentGraphBuilder.class);
        properties = new SkillLifecycleProperties();
        properties.setConsolidate(true);
        service = new SkillConsolidationService(skillService, skillManageTool, lifecycleService,
                modelConfigService, agentGraphBuilder, properties, new ObjectMapper());
    }

    private void stubLlm(String json) {
        ChatModel chatModel = (ChatModel) (Prompt p) ->
                new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        when(agentGraphBuilder.buildRuntimeChatModel(any())).thenReturn(chatModel);
        when(modelConfigService.getDefaultModel()).thenReturn(null);
    }

    private SkillEntity skill(String name) {
        SkillEntity s = new SkillEntity();
        s.setName(name);
        s.setDescription("desc of " + name);
        s.setSkillContent("---\nname: " + name + "\n---\n# " + name + "\nbody");
        s.setSourceConversationId("conv-" + name);
        return s;
    }

    private List<SkillEntity> candidates(int n) {
        List<SkillEntity> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(skill("spring-rest-" + i));
        }
        return list;
    }

    @Test
    @DisplayName("disabled → no reviewer call")
    void disabledNoop() {
        properties.setConsolidate(false);
        service.consolidate(candidates(6), LocalDateTime.now(), false, SkillCuratorReport.builder());
        verify(agentGraphBuilder, never()).buildRuntimeChatModel(any());
    }

    @Test
    @DisplayName("below min-skills floor → no reviewer call")
    void belowFloorNoop() {
        properties.setConsolidateMinSkills(4);
        service.consolidate(candidates(3), LocalDateTime.now(), false, SkillCuratorReport.builder());
        verify(agentGraphBuilder, never()).buildRuntimeChatModel(any());
    }

    @Test
    @DisplayName("applied merge: new umbrella created, absorbed skills archived")
    void appliesMerge() {
        stubLlm("[{\"umbrella_name\":\"spring-rest\",\"umbrella_content\":\"---\\nname: spring-rest\\n---\\n# X\","
                + "\"absorb\":[\"spring-rest-1\",\"spring-rest-2\"],\"reason\":\"dupes\"}]");
        when(skillService.findByName("spring-rest")).thenReturn(null);
        when(skillManageTool.skill_manage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("Skill 'spring-rest' created successfully (security scan: PASSED).");

        SkillCuratorReport.Builder report = SkillCuratorReport.builder();
        service.consolidate(candidates(4), LocalDateTime.now(), false, report);

        verify(skillManageTool, times(1))
                .skill_manage(eq("create"), eq("spring-rest"), any(), any(), any(), any(), any());
        verify(lifecycleService, times(1))
                .applyManual(argSkill("spring-rest-1"), eq(LifecycleTransition.TO_ARCHIVED), any(), any());
        verify(lifecycleService, times(1))
                .applyManual(argSkill("spring-rest-2"), eq(LifecycleTransition.TO_ARCHIVED), any(), any());

        List<SkillCuratorReport.ConsolidationRow> rows = report.build().getConsolidations();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).applied());
        assertTrue(rows.get(0).umbrellaCreated());
    }

    @Test
    @DisplayName("dry-run: records the plan but writes nothing")
    void dryRunPreviewOnly() {
        stubLlm("[{\"umbrella_name\":\"spring-rest\",\"umbrella_content\":\"---\\nname: spring-rest\\n---\\n# X\","
                + "\"absorb\":[\"spring-rest-1\",\"spring-rest-2\"],\"reason\":\"dupes\"}]");

        SkillCuratorReport.Builder report = SkillCuratorReport.builder();
        service.consolidate(candidates(4), LocalDateTime.now(), true, report);

        verify(skillManageTool, never()).skill_manage(any(), any(), any(), any(), any(), any(), any());
        verify(lifecycleService, never()).applyManual(any(), any(), any(), any());
        List<SkillCuratorReport.ConsolidationRow> rows = report.build().getConsolidations();
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).applied());
    }

    @Test
    @DisplayName("guard: out-of-scope absorb names are ignored; lone valid name is not a real merge for a new umbrella")
    void ignoresOutOfScopeNames() {
        stubLlm("[{\"umbrella_name\":\"brand-new\",\"umbrella_content\":\"---\\nname: brand-new\\n---\\n# X\","
                + "\"absorb\":[\"spring-rest-1\",\"not-a-candidate\"],\"reason\":\"x\"}]");
        when(skillService.findByName("brand-new")).thenReturn(null);

        SkillCuratorReport.Builder report = SkillCuratorReport.builder();
        service.consolidate(candidates(4), LocalDateTime.now(), false, report);

        // Only spring-rest-1 is in scope → 1 absorbed for a NEW umbrella → not a real merge → skipped.
        verify(skillManageTool, never()).skill_manage(any(), any(), any(), any(), any(), any(), any());
        verify(lifecycleService, never()).applyManual(any(), any(), any(), any());
    }

    @Test
    @DisplayName("maxGroupsPerRun caps how many groups apply")
    void capsGroups() {
        properties.setConsolidateMaxGroupsPerRun(1);
        String g1 = "{\"umbrella_name\":\"u1\",\"umbrella_content\":\"---\\nname: u1\\n---\\n#\","
                + "\"absorb\":[\"spring-rest-1\",\"spring-rest-2\"],\"reason\":\"a\"}";
        String g2 = "{\"umbrella_name\":\"u2\",\"umbrella_content\":\"---\\nname: u2\\n---\\n#\","
                + "\"absorb\":[\"spring-rest-3\",\"spring-rest-4\"],\"reason\":\"b\"}";
        stubLlm("[" + g1 + "," + g2 + "]");
        when(skillManageTool.skill_manage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("created successfully");

        SkillCuratorReport.Builder report = SkillCuratorReport.builder();
        service.consolidate(candidates(4), LocalDateTime.now(), false, report);

        verify(skillManageTool, times(1)).skill_manage(any(), any(), any(), any(), any(), any(), any());
    }

    /** Mockito arg matcher for a SkillEntity with the given name. */
    private static SkillEntity argSkill(String name) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && name.equals(s.getName()));
    }
}
