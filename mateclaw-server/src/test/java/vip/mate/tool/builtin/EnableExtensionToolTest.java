package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.disclosure.DisclosureTier;
import vip.mate.tool.disclosure.ToolDisclosureService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnableExtensionToolTest {

    static class Tools {
        @Tool(description = "text to image")
        public String image_generate() { return ""; }

        @Tool(description = "a plain core tool")
        public String my_core_tool() { return ""; }
    }

    private static AgentToolSet toolSet() {
        return AgentToolSet.fromCallbacks(List.of(new Tools()), List.of(ToolCallbacks.from(new Tools())));
    }

    @Test
    @DisplayName("blank toolName returns a required-arg error")
    void blankRejected() {
        EnableExtensionTool tool = new EnableExtensionTool(mock(ToolRegistry.class),
                mock(ToolDisclosureService.class), mock(AgentBindingService.class));
        String out = tool.enableTool("  ", null);
        assertTrue(out.startsWith("Error:"));
        assertTrue(out.contains("required"));
    }

    @Test
    @DisplayName("tool not in the agent's set returns an availability error")
    void unknownReturnsNotAvailable() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getEnabledToolSet()).thenReturn(toolSet());
        EnableExtensionTool tool = new EnableExtensionTool(registry,
                mock(ToolDisclosureService.class), mock(AgentBindingService.class));
        // ctx=null → no agentId → agent set falls back to the full set; "nope" still absent
        String out = tool.enableTool("nope", null);
        assertTrue(out.startsWith("Error:"));
        assertTrue(out.contains("not available"));
    }

    @Test
    @DisplayName("core-tier tool reports it is already callable")
    void coreToolAlreadyCallable() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getEnabledToolSet()).thenReturn(toolSet());
        ToolDisclosureService disclosure = mock(ToolDisclosureService.class);
        when(disclosure.resolveTier(any())).thenReturn(DisclosureTier.CORE);
        EnableExtensionTool tool = new EnableExtensionTool(registry, disclosure, mock(AgentBindingService.class));
        String out = tool.enableTool("my_core_tool", null);
        assertTrue(out.contains("already directly callable"));
    }

    @Test
    @DisplayName("extension-tier tool is activated")
    void extensionToolActivated() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getEnabledToolSet()).thenReturn(toolSet());
        ToolDisclosureService disclosure = mock(ToolDisclosureService.class);
        when(disclosure.resolveTier(any())).thenReturn(DisclosureTier.EXTENSION);
        EnableExtensionTool tool = new EnableExtensionTool(registry, disclosure, mock(AgentBindingService.class));
        String out = tool.enableTool("image_generate", null);
        assertTrue(out.contains("now active"));
    }
}
