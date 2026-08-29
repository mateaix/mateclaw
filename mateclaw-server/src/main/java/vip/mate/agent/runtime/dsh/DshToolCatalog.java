package vip.mate.agent.runtime.dsh;

import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;

public final class DshToolCatalog {
    private DshToolCatalog() {}

    public static List<DshToolDescriptor> fromCallbacks(List<ToolCallback> callbacks) {
        LinkedHashMap<String, DshToolDescriptor> descriptors = new LinkedHashMap<>();
        if (callbacks == null) return List.of();
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) continue;
            var definition = callback.getToolDefinition();
            descriptors.putIfAbsent(definition.name(), new DshToolDescriptor(
                    definition.name(), definition.description(), definition.inputSchema()));
        }
        return List.copyOf(descriptors.values());
    }
}
