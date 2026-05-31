package vip.mate.memory.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.memory.service.StructuredMemoryService;
import vip.mate.memory.spi.MemoryProvider;

import java.util.List;

/**
 * Structured memory provider — contributes typed memory entries
 * (user/feedback/project/reference) to the system prompt.
 * <p>
 * Tool beans (StructuredMemoryTool) are auto-discovered by ToolRegistry's
 * component scan, so getToolBeans() returns empty.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructuredMemoryProvider implements MemoryProvider {

    private final StructuredMemoryService structuredMemoryService;

    @Override
    public String id() {
        return "structured";
    }

    @Override
    public int order() {
        return 10; // after builtin (0)
    }

    /**
     * Returns the stable, low-volume typed entries (user profile, feedback)
     * for unconditional system prompt injection.
     */
    @Override
    public String systemPromptBlock(Long agentId) {
        try {
            return structuredMemoryService.buildMemoryBlock(agentId);
        } catch (Exception e) {
            log.warn("[StructuredMemory] Failed to build memory block for agent={}: {}",
                    agentId, e.getMessage());
            return "";
        }
    }

    /**
     * Returns growing/specific typed entries (project facts, reference notes)
     * relevant to the current question. Surfacing these per-turn rather than
     * always-on keeps them salient when asked about and avoids the model
     * confusing a stored fact with similarly-shaped background knowledge.
     * The returned block is fenced centrally by the memory manager.
     */
    @Override
    public String prefetch(Long agentId, String userQuery) {
        try {
            return structuredMemoryService.buildPrefetchBlock(agentId, userQuery);
        } catch (Exception e) {
            log.warn("[StructuredMemory] Failed to build prefetch block for agent={}: {}",
                    agentId, e.getMessage());
            return "";
        }
    }

    /**
     * Tools are auto-discovered by ToolRegistry component scan.
     */
    @Override
    public List<Object> getToolBeans() {
        return List.of();
    }
}
