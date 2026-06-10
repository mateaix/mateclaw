package vip.mate.agent.binding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.repository.AgentProviderPreferenceMapper;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.binding.repository.AgentToolBindingMapper;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for {@code setToolBindings}'s validation gate —
 * proves that hand-crafted API requests can't write a tool name the
 * runtime won't be able to resolve.
 */
class AgentBindingServiceValidationTest {

    private AgentToolBindingMapper toolBindingMapper;
    private AvailableToolService availableToolService;
    private AgentBindingService service;

    @BeforeEach
    void setUp() {
        AgentSkillBindingMapper skillBindingMapper = mock(AgentSkillBindingMapper.class);
        toolBindingMapper = mock(AgentToolBindingMapper.class);
        AgentProviderPreferenceMapper providerPreferenceMapper = mock(AgentProviderPreferenceMapper.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        availableToolService = mock(AvailableToolService.class);
        // Tool-binding tests don't exercise the agent/skill workspace lookup,
        // so empty mocks are enough — the wired-in fields just need to be
        // non-null for construction.
        AgentMapper agentMapper = mock(AgentMapper.class);
        SkillMapper skillMapper = mock(SkillMapper.class);
        AcpSkillBridge acpSkillBridge = mock(AcpSkillBridge.class);
        service = new AgentBindingService(
                skillBindingMapper,
                toolBindingMapper,
                providerPreferenceMapper,
                mock(vip.mate.agent.binding.repository.AgentWikiKbBindingMapper.class),
                mock(vip.mate.wiki.repository.WikiKnowledgeBaseMapper.class),
                skillRuntimeService,
                availableToolService,
                agentMapper,
                skillMapper,
                acpSkillBridge);
        // No existing binding by default — each test overrides as needed.
        when(toolBindingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    @Test
    @DisplayName("a known available tool name persists")
    void availableNameIsAccepted() {
        when(availableToolService.listAvailable()).thenReturn(List.of(
                bindable("web_search"),
                bindable("mcp_42_search_aaaaaa")));

        service.setToolBindings(99L, List.of("mcp_42_search_aaaaaa"));

        ArgumentCaptor<AgentToolBinding> captor = ArgumentCaptor.forClass(AgentToolBinding.class);
        verify(toolBindingMapper, times(1)).insert(captor.capture());
        assertEquals("mcp_42_search_aaaaaa", captor.getValue().getToolName());
    }

    @Test
    @DisplayName("an unknown name (typo / legacy unprefixed) is refused")
    void unknownNameIsRejected() {
        when(availableToolService.listAvailable()).thenReturn(List.of(
                bindable("mcp_42_search_aaaaaa")));

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.setToolBindings(99L, List.of("search_typo")));
        assertTrue(ex.getMessage().contains("search_typo"),
                "error should name the rejected tool, got: " + ex.getMessage());
        // Nothing should have been persisted — validation runs before delete.
        verify(toolBindingMapper, never()).delete(any());
        verify(toolBindingMapper, never()).insert(any(AgentToolBinding.class));
    }

    @Test
    @DisplayName("a name marked available=false (e.g. hash collision) is refused")
    void unavailableNameIsRejected() {
        AvailableToolDTO collided = AvailableToolDTO.builder()
                .name("mcp_42_search_aaaaaa")
                .available(false)
                .unavailableReason("HASH_COLLISION:other")
                .build();
        when(availableToolService.listAvailable()).thenReturn(List.of(collided));

        assertThrows(MateClawException.class,
                () -> service.setToolBindings(99L, List.of("mcp_42_search_aaaaaa")));
        verify(toolBindingMapper, never()).delete(any());
    }

    @Test
    @DisplayName("a stale/unavailable name already in the existing binding can be removed (not blocked)")
    void existingUnbindableCanBeRemoved() {
        // Existing binding holds a name that has since become unavailable.
        // The user removes it — passing an empty incoming list. Validation
        // must NOT block this because the new name set introduces nothing
        // new to validate.
        AgentToolBinding existing = new AgentToolBinding();
        existing.setAgentId(99L);
        existing.setToolName("mcp_42_search_aaaaaa");
        existing.setEnabled(true);
        when(toolBindingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));
        when(availableToolService.listAvailable()).thenReturn(List.of()); // tool no longer available

        service.setToolBindings(99L, List.of());

        verify(toolBindingMapper, times(1)).delete(any());
        verify(toolBindingMapper, never()).insert(any(AgentToolBinding.class));
    }

    @Test
    @DisplayName("keeping an existing-but-now-stale binding is allowed; adding a NEW unknown is still refused")
    void mixedKeepAndUnknownAdd() {
        // Existing has one binding; user tries to keep it AND add a typo.
        AgentToolBinding existing = new AgentToolBinding();
        existing.setAgentId(99L);
        existing.setToolName("mcp_42_search_aaaaaa");
        existing.setEnabled(true);
        when(toolBindingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));
        // Only a different name is currently available.
        when(availableToolService.listAvailable()).thenReturn(List.of(
                bindable("web_search")));

        assertThrows(MateClawException.class,
                () -> service.setToolBindings(99L, List.of("mcp_42_search_aaaaaa", "typo")));
        verify(toolBindingMapper, never()).delete(any());
    }

    @Test
    @DisplayName("blank or null entries in incoming list are rejected")
    void blankEntriesAreRejected() {
        when(availableToolService.listAvailable()).thenReturn(List.of(bindable("web_search")));
        assertThrows(MateClawException.class,
                () -> service.setToolBindings(99L, java.util.Arrays.asList("web_search", "")));
        assertThrows(MateClawException.class,
                () -> service.setToolBindings(99L, java.util.Arrays.asList("web_search", (String) null)));
    }

    @Test
    @DisplayName("AvailableToolService failure: validation refuses any new name (conservative)")
    void availableServiceFailureIsConservative() {
        when(availableToolService.listAvailable()).thenThrow(new RuntimeException("picker down"));

        // Existing-only saves still succeed.
        AgentToolBinding existing = new AgentToolBinding();
        existing.setAgentId(99L);
        existing.setToolName("web_search");
        when(toolBindingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));
        service.setToolBindings(99L, List.of("web_search"));
        verify(toolBindingMapper, times(1)).delete(any());

        // Adding a new one fails fast.
        assertThrows(MateClawException.class,
                () -> service.setToolBindings(99L, List.of("web_search", "another")));
    }

    private static AvailableToolDTO bindable(String name) {
        return AvailableToolDTO.builder()
                .name(name)
                .available(true)
                .build();
    }
}
