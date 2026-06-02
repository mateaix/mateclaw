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
    /**
     * Build-time injection is limited to SHARED (TEAM / GLOBAL) structured
     * memory — agent-creator presets and team-wide facts. Conversation-derived
     * PERSONAL structured memory is owner-specific and the agent instance is
     * cached across users, so it is injected per-turn in
     * {@link #prefetch(Long, String, String)} for the current owner only.
     */
    @Override
    public String systemPromptBlock(Long agentId) {
        try {
            // ownerKey=null → buildMemoryBlock reads shared (TEAM/GLOBAL) rows only.
            return structuredMemoryService.buildMemoryBlock(agentId, null);
        } catch (Exception e) {
            log.warn("[StructuredMemory] Failed to build shared memory block for agent={}: {}",
                    agentId, e.getMessage());
            return "";
        }
    }

    @Override
    public String prefetch(Long agentId, String userQuery) {
        return prefetch(agentId, userQuery, null);
    }

    /**
     * Owner-scoped per-turn injection: the stable user/feedback entries plus the
     * query-relevant project/reference entries — all restricted to the current
     * owner's structured memory. Returns empty when there is no isolatable owner
     * so a shared agent never injects another user's structured memory. The
     * returned block is fenced centrally by the memory manager.
     */
    @Override
    public String prefetch(Long agentId, String userQuery, String ownerKey) {
        try {
            String stable = structuredMemoryService.buildMemoryBlock(agentId, ownerKey);
            String relevant = structuredMemoryService.buildPrefetchBlock(agentId, userQuery, ownerKey);
            boolean hasStable = stable != null && !stable.isBlank();
            boolean hasRelevant = relevant != null && !relevant.isBlank();
            if (!hasStable && !hasRelevant) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (hasStable) {
                sb.append(stable);
            }
            if (hasRelevant) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(relevant);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[StructuredMemory] Failed to build prefetch block for agent={}, owner={}: {}",
                    agentId, ownerKey, e.getMessage());
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
