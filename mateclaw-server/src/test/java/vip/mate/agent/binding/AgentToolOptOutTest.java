package vip.mate.agent.binding;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.builtin.EnableExtensionTool;
import vip.mate.tool.builtin.ProgressiveToolBridgeTool;
import vip.mate.tool.disclosure.ToolDisclosureService;
import vip.mate.tool.guard.service.ToolGuardConfigService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolOptOutTest {
    private static final List<String> BUSINESS_TOOLS = List.of(
            "execute_code", "execute_shell_command", "read_file", "append_file", "write_file", "edit_file",
            "web_search", "browser_use", "send_file", "renderDocx", "image_generate", "music_generate",
            "video_generate", "render_html_image", "delegateToAgent", "delegateParallel", "delegateAsync",
            "wiki_create_page", "wiki_read_page", "mcp_test_tool");

    private AgentBindingService service(boolean skillsDisabled) {
        AgentEntity entity = new AgentEntity();
        entity.setId(655L);
        entity.setToolsDisabled(true);
        entity.setSkillsDisabled(skillsDisabled);
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectById(655L)).thenReturn(entity);
        AgentBindingService service = mock(AgentBindingService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "agentMapper", mapper);
        if (!skillsDisabled) doReturn(Set.of(10L)).when(service).getBoundSkillIds(655L);
        return service;
    }

    private AgentToolSet catalog() {
        var names = new java.util.ArrayList<>(BUSINESS_TOOLS);
        names.addAll(List.of("record_lesson", "getCurrentTime", "load_skill", "runSkillScript"));
        List<ToolCallback> callbacks = names.stream().map(name -> {
            ToolCallback callback = mock(ToolCallback.class);
            when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                    .name(name).description(name).inputSchema("{}").build());
            return callback;
        }).toList();
        return AgentToolSet.fromCallbacks(List.of(), callbacks);
    }

    @Test
    void bothDisabledRemovesBusinessAndSkillToolsFromRuntimeCallbacks() {
        AgentBindingService service = service(true);
        Set<String> allowed = service.getEffectiveToolNames(655L);
        for (String name : BUSINESS_TOOLS) assertFalse(allowed.contains(name), name);
        var filtered = catalog().withDeniedToolsFiltered(service.getSkillDiscoveryDeniedTools(655L))
                .withAllowedToolsOnly(allowed);
        assertEquals(Set.of("record_lesson", "getCurrentTime"), filtered.callbackByName().keySet());
        assertTrue(allowed.containsAll(Set.of("getManagedGoalJsonSlots", "publishManagedGoalJson", "checkManagedGoalJson")));
    }

    @Test
    void toolsOptOutDoesNotDisableSkillDiscoveryButDoesNotRestoreBusinessDefaults() {
        AgentBindingService service = service(false);
        Set<String> allowed = service.getEffectiveToolNames(655L);
        assertTrue(allowed.containsAll(Set.of("load_skill", "readSkillFile", "runSkillScript")));
        for (String name : BUSINESS_TOOLS) assertFalse(allowed.contains(name), name);
    }

    @Test
    void enabledSkillContributesOnlyItsDeclaredToolsAfterOptOut() {
        AgentBindingService service = service(false);
        var runtime = mock(vip.mate.skill.runtime.SkillRuntimeService.class);
        var skill = mock(vip.mate.skill.runtime.model.ResolvedSkill.class);
        when(skill.getId()).thenReturn(10L);
        when(skill.getWorkspaceId()).thenReturn(null);
        when(skill.isEnabled()).thenReturn(true);
        when(skill.isRuntimeAvailable()).thenReturn(true);
        when(skill.isDependencyReady()).thenReturn(true);
        when(skill.getEffectiveAllowedTools()).thenReturn(Set.of("read_file"));
        when(runtime.resolveAllSkillsStatus()).thenReturn(List.of(skill));
        ReflectionTestUtils.setField(service, "skillRuntimeService", runtime);
        Set<String> allowed = service.getEffectiveToolNames(655L);
        assertTrue(allowed.contains("read_file"));
        assertFalse(allowed.contains("execute_code"));
        assertFalse(allowed.contains("append_file"));
        when(skill.isEnabled()).thenReturn(false);
        assertFalse(service.getEffectiveToolNames(655L).contains("read_file"));
    }

    @Test
    void dynamicBridgesCannotDiscoverOrEnableDisabledTools() {
        AgentBindingService service = service(true);
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentToolSet globalCatalog = catalog();
        when(registry.getEnabledToolSet()).thenReturn(globalCatalog);
        ToolGuardConfigService guard = mock(ToolGuardConfigService.class);
        when(guard.getDeniedTools()).thenReturn(Set.of());
        ToolContext context = vip.mate.agent.context.ChatOrigin.EMPTY.withAgent(655L).toToolContext();
        var bridge = new ProgressiveToolBridgeTool(registry, service, guard);
        String search = bridge.search(null, 20, context);
        for (String name : BUSINESS_TOOLS) assertFalse(search.contains(name), name);
        var enable = new EnableExtensionTool(registry, mock(ToolDisclosureService.class), service);
        for (String name : BUSINESS_TOOLS) {
            assertTrue(enable.enableTool(name, context).startsWith("Error:"), name);
        }
    }
}
