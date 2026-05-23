package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.disclosure.DisclosureTier;
import vip.mate.tool.disclosure.ToolDisclosureService;

import java.util.Set;

/**
 * Activates an extension-tier tool for the rest of the conversation.
 * <p>
 * The model calls this after spotting a tool in the {@code ## Extension Tools}
 * catalog. The tool only validates and returns a confirmation message — the
 * activation is recorded into graph state by the action node (tools cannot
 * mutate {@code OverAllState} directly), so the enabled tool's schema becomes
 * visible on the next reasoning turn of the same ReAct loop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnableExtensionTool {

    private final ToolRegistry toolRegistry;
    private final ToolDisclosureService toolDisclosureService;
    private final AgentBindingService agentBindingService;

    @Tool(name = "enable_tool", description = """
        Activate an extension tool for the rest of this conversation.
        Use this when the Extension Tools catalog lists a tool you need to call.

        Parameters:
        - toolName: The tool's function name exactly as shown in the catalog.

        After enabling, issue the real tool call in your NEXT response — it
        becomes callable from then on. Only enable a tool the task actually needs.
        """)
    public String enableTool(
            @ToolParam(description = "Extension tool function name from the catalog")
            String toolName,

            @Nullable ToolContext ctx
    ) {
        if (toolName == null || toolName.isBlank()) {
            return "Error: toolName is required. See the Extension Tools catalog for valid names.";
        }
        // Validate against THIS agent's effective tool set, not the global registry —
        // otherwise a tool that exists globally but isn't bound to the agent would be
        // reported active yet never appear (the reasoning-node split only activates
        // tools in the agent's own set).
        AgentToolSet agentSet = toolRegistry.getEnabledToolSet();
        Long agentId = ChatOrigin.from(ctx).agentId();
        if (agentId != null) {
            Set<String> effective = agentBindingService.getEffectiveToolNames(agentId);
            agentSet = agentSet.withAllowedToolsOnly(effective); // null = no restriction
        }
        ToolCallback callback = agentSet.callbackByName().get(toolName);
        if (callback == null) {
            return "Error: Tool '" + toolName + "' is not available to this agent. "
                    + "Use the exact function name from the Extension Tools catalog.";
        }
        if (toolDisclosureService.resolveTier(callback) != DisclosureTier.EXTENSION) {
            return "Tool '" + toolName + "' is already directly callable — just call it, no need to enable.";
        }
        log.info("enable_tool: activating extension tool '{}' for agent {}", toolName, agentId);
        return "Tool '" + toolName + "' is now active. Issue the call in your next response.";
    }
}
