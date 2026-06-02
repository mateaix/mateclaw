package vip.mate.memory.fact.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.fact.projection.FactProjectionBuilder;
import vip.mate.memory.fact.query.FactQueryService;
import vip.mate.memory.fact.tool.FactQueryTool;
import vip.mate.memory.spi.MemoryProvider;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only SPI provider that surfaces fact projection in the memory lifecycle.
 * <p>
 * - prefetch: returns relevant facts for the user query (injected into prompt)
 * - syncTurn: bumps use_count for recalled facts
 * - onMemoryWrite: triggers incremental projection rebuild
 * - getToolBeans: exposes FactQueryTool to agents
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactMemoryProvider implements MemoryProvider {

    private final FactQueryService queryService;
    private final FactProjectionBuilder projectionBuilder;
    private final FactQueryTool factQueryTool;
    private final MemoryProperties properties;

    @Override
    public String id() { return "fact_store"; }

    @Override
    public int order() { return 200; }

    @Override
    public boolean isAvailable() {
        return properties.getFact().isProjectionEnabled();
    }

    @Override
    public String prefetch(Long agentId, String userQuery) {
        return prefetch(agentId, userQuery, null);
    }

    @Override
    public String prefetch(Long agentId, String userQuery, String ownerKey) {
        if (!properties.getFact().isProjectionEnabled()) return "";
        List<FactEntity> facts = queryService.recallRelevant(agentId, userQuery, ownerKey);
        if (facts.isEmpty()) return "";

        // Bump usage
        queryService.bumpUseCount(facts.stream().map(FactEntity::getId).toList());

        String block = facts.stream()
                .map(f -> String.format("- %s %s %s", f.getSubject(), f.getPredicate(), f.getObjectValue()))
                .collect(Collectors.joining("\n"));
        return "<facts>\n" + block + "\n</facts>";
    }

    @Override
    public void syncTurn(Long agentId, String conversationId, String userMessage, String assistantReply) {
        // No-op — bumpUseCount already called in prefetch
    }

    @Override
    public void onMemoryWrite(Long agentId, String target, String action, String content) {
        if (!properties.getFact().isProjectionEnabled()) return;
        // Incremental rebuild for the changed file
        projectionBuilder.rebuildOne(agentId, target, content);
    }

    @Override
    public List<Object> getToolBeans() {
        if (!properties.getFact().isProjectionEnabled()) return Collections.emptyList();
        return List.of(factQueryTool);
    }
}
