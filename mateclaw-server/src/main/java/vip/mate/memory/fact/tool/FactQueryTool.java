package vip.mate.memory.fact.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.model.FactContradictionEntity;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.fact.query.FactQueryService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent tools for querying the fact projection.
 * Read-only — no fact_add / fact_remove / fact_update tools (core invariant D1).
 *
 * @author MateClaw Team
 */
@Component
@RequiredArgsConstructor
public class FactQueryTool {

    private final FactQueryService queryService;
    private final MemoryProperties properties;

    @Tool(description = "Probe facts about an entity. Returns relevant facts where the entity appears as subject or object.")
    public String fact_probe(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Entity name to search for") String entity) {
        if (!properties.getFact().isProjectionEnabled()) {
            return "Fact projection is disabled.";
        }
        List<FactEntity> facts = queryService.probe(agentId, entity);
        if (facts.isEmpty()) return "No facts found for entity: " + entity;

        // Bump use count
        queryService.bumpUseCount(facts.stream().map(FactEntity::getId).toList());

        return facts.stream()
                .map(f -> String.format("- %s %s %s (trust=%.2f)", f.getSubject(), f.getPredicate(), f.getObjectValue(), f.getTrust()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "List unresolved fact contradictions detected during Dream consolidation.")
    public String fact_list_contradictions(
            @ToolParam(description = "Agent ID") Long agentId) {
        if (!properties.getFact().isProjectionEnabled()) {
            return "Fact projection is disabled.";
        }
        List<FactContradictionEntity> contradictions = queryService.listContradictions(agentId);
        if (contradictions.isEmpty()) return "No unresolved contradictions.";

        return contradictions.stream()
                .map(c -> String.format("- Contradiction #%d: factA=%d vs factB=%d — %s",
                        c.getId(), c.getFactAId(), c.getFactBId(),
                        c.getDescription() != null ? c.getDescription() : ""))
                .collect(Collectors.joining("\n"));
    }
}
