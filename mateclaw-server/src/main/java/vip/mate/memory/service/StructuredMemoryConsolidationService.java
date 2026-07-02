package vip.mate.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.MemoryProperties;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Nightly consolidation of always-on structured memory (user / feedback).
 * <p>
 * These typed files are injected into every system prompt and only ever grow:
 * nudge and post-conversation summarization append entries, key-exact dedup lets
 * paraphrased keys through, and no pass ever merges them. Over time the always-on
 * block inflates per-turn context. This service periodically rewrites each
 * always-on type file into a smaller, deduplicated, non-stale set via the LLM, so
 * accumulated memory shrinks at the storage level rather than only being trimmed
 * at injection time.
 * <p>
 * Consolidation runs per bucket: the shared (TEAM/GLOBAL) file plus every personal
 * owner's file, because most growth accumulates in per-owner buckets that the
 * always-on prefetch injects each turn. The number of buckets processed per agent
 * per run is capped to bound LLM cost.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredMemoryConsolidationService {

    private final StructuredMemoryService structuredMemoryService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final MemoryProperties properties;

    /** Typed LLM output for a single consolidation call. */
    public record ConsolidationResult(boolean shouldUpdate, List<Entry> entries, String reason) {
        public record Entry(String key, String content) {}
    }

    /** Aggregate counters for one consolidation run, for observability. */
    public static class ConsolidationStats {
        public int ownersConsolidated;   // buckets that passed the gate and called the LLM
        public int updated;              // buckets actually rewritten
        public int skippedSmall;         // buckets below the min-entries gate
        public int skippedOverCap;       // buckets deferred to a later run by the per-run cap
        public int failed;               // buckets whose LLM/parse/write raised
        public int entriesBefore;
        public int entriesAfter;

        public void add(ConsolidationStats o) {
            ownersConsolidated += o.ownersConsolidated;
            updated += o.updated;
            skippedSmall += o.skippedSmall;
            skippedOverCap += o.skippedOverCap;
            failed += o.failed;
            entriesBefore += o.entriesBefore;
            entriesAfter += o.entriesAfter;
        }
    }

    /** Consolidate every always-on structured bucket (shared + personal) for an agent. */
    public ConsolidationStats consolidateAgent(Long agentId) {
        ConsolidationStats stats = new ConsolidationStats();
        if (!properties.isStructuredConsolidationEnabled()) {
            return stats;
        }
        int cap = properties.getStructuredConsolidationMaxOwnersPerRun();
        int remaining = cap > 0 ? cap : Integer.MAX_VALUE;

        for (String type : structuredMemoryService.alwaysOnTypes()) {
            for (String ownerKey : structuredMemoryService.consolidatableOwnerKeys(agentId, type)) {
                String content = structuredMemoryService.readTypeRaw(agentId, type, ownerKey);
                int count = structuredMemoryService.countEntries(content);
                if (count < properties.getStructuredConsolidationMinEntries()) {
                    stats.skippedSmall++;
                    continue;
                }
                if (remaining <= 0) {
                    stats.skippedOverCap++;
                    continue;
                }
                remaining--;
                stats.ownersConsolidated++;
                stats.entriesBefore += count;
                try {
                    int after = consolidateBucket(agentId, type, ownerKey, content, count);
                    if (after >= 0) {
                        stats.updated++;
                        stats.entriesAfter += after;
                    } else {
                        stats.entriesAfter += count; // no write — bucket unchanged
                    }
                } catch (Exception e) {
                    stats.failed++;
                    stats.entriesAfter += count;
                    log.warn("[StructuredConsolidation] agent={} type={} owner={} failed: {}",
                            agentId, type, ownerKey, e.getMessage());
                }
            }
        }
        return stats;
    }

    /**
     * Consolidate one bucket. Returns the new entry count when the file was
     * rewritten, or {@code -1} when nothing was written (LLM declined, produced
     * unparseable output, or would not have reduced the entry count).
     */
    private int consolidateBucket(Long agentId, String type, String ownerKey, String content, int count) {
        BeanOutputConverter<ConsolidationResult> converter = new BeanOutputConverter<>(ConsolidationResult.class);
        String systemPrompt = PromptLoader.loadPrompt("memory/consolidate-structured-system");
        String userPrompt = PromptLoader.loadPrompt("memory/consolidate-structured-user")
                .replace("{type}", type)
                .replace("{today}", LocalDate.now().toString())
                .replace("{count}", String.valueOf(count))
                .replace("{content}", content);

        ChatModel chatModel = buildChatModel();
        ChatResponse resp = chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt),
                new UserMessage(converter.getFormat()))));
        String text = resp.getResult().getOutput().getText();

        ConsolidationResult result;
        try {
            result = converter.convert(text);
        } catch (Exception e) {
            log.warn("[StructuredConsolidation] Unparseable LLM output agent={} type={} owner={}: {}",
                    agentId, type, ownerKey, e.getMessage());
            return -1;
        }
        if (result == null || !result.shouldUpdate()
                || result.entries() == null || result.entries().isEmpty()) {
            return -1;
        }

        LinkedHashMap<String, String> consolidated = new LinkedHashMap<>();
        for (ConsolidationResult.Entry e : result.entries()) {
            if (e == null) continue;
            String key = e.key() == null ? "" : e.key().trim();
            String value = e.content() == null ? "" : e.content().trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                consolidated.put(key, value);
            }
        }
        if (consolidated.isEmpty()) {
            return -1;
        }

        // Safety invariant: consolidation must never grow the entry count. A model
        // that hallucinates extra entries would otherwise make the bloat worse.
        if (consolidated.size() > count) {
            log.debug("[StructuredConsolidation] agent={} type={} owner={} produced {} > {} entries; skipping write",
                    agentId, type, ownerKey, consolidated.size(), count);
            return -1;
        }

        structuredMemoryService.replaceTypeEntries(agentId, type, ownerKey, consolidated, "consolidation");
        log.info("[StructuredConsolidation] agent={} type={} owner={} consolidated {} -> {} entries",
                agentId, type, ownerKey, count, consolidated.size());
        return consolidated.size();
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        return agentGraphBuilder.buildRuntimeChatModel(defaultModel);
    }
}
