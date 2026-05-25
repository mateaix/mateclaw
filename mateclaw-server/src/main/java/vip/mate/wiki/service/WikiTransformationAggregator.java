package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.WikiModelRoutingService;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.model.WikiTransformationRunEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Map-reduce a single transformation template across all completed runs in
 * a KB: produces one synthesis wiki page that unifies the per-source
 * outputs. The map step is already done by the executor — each run carries
 * its per-source output. This service is the reduce step: load the runs,
 * stack them with source labels, ask an LLM to merge + dedupe, and persist
 * the merged document as a synthesis page slugged
 * {@code <template-name>-aggregate}.
 *
 * <p>Idempotent: re-running upserts the same slug, so the aggregate page
 * stays current as new runs land. Page-level embedding + reverse-citation
 * extraction run the same way they do for single-source synthesis pages.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiTransformationAggregator {

    /** Hard cap on combined input chars fed to the LLM merge call. */
    private static final int MAX_AGG_INPUT_CHARS = 80_000;

    /** Per-source output is truncated to keep the merge prompt within the cap when many sources exist. */
    private static final int PER_SOURCE_SOFT_CAP = 12_000;

    private final WikiTransformationService transformationService;
    private final WikiRawMaterialService rawService;
    private final WikiPageService pageService;

    @Autowired(required = false)
    private WikiModelRoutingService modelRoutingService;

    @Autowired(required = false)
    private WikiEmbeddingService embeddingService;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public record Result(Long pageId, String slug, String title,
                          int sourcesUsed, int charsFed, boolean created) {
        public static Result empty(String reason) {
            return new Result(null, null, reason, 0, 0, false);
        }
    }

    public Result aggregate(WikiTransformationEntity template, Long kbId, String triggeredBy) {
        if (template == null) throw new IllegalArgumentException("template is required");
        if (kbId == null) throw new IllegalArgumentException("kbId is required");
        if (modelRoutingService == null) throw new IllegalStateException("ModelRoutingService unavailable");

        // Load every completed run for this template against this KB. Cap at
        // 100 sources so a degenerate KB doesn't push past the input window.
        List<WikiTransformationRunEntity> runs = transformationService
                .listRunsByTransformation(template.getId(), 200)
                .stream()
                .filter(r -> "completed".equalsIgnoreCase(r.getStatus())
                        && kbId.equals(r.getKbId())
                        && r.getOutput() != null && !r.getOutput().isBlank()
                        && r.getRawId() != null)
                .limit(100)
                .toList();
        if (runs.isEmpty()) {
            return Result.empty("no completed runs to aggregate");
        }

        // Deduplicate by rawId — only the most recent completed run per raw
        // contributes, so a template that's been re-run several times against
        // the same source doesn't get its old outputs included.
        java.util.Map<Long, WikiTransformationRunEntity> latestByRaw = new java.util.LinkedHashMap<>();
        for (WikiTransformationRunEntity r : runs) {
            latestByRaw.putIfAbsent(r.getRawId(), r); // listRunsByTransformation orders DESC by createTime
        }
        List<WikiTransformationRunEntity> distinct = new ArrayList<>(latestByRaw.values());

        // Build the per-source block, truncating each section to keep the
        // merged prompt within the model's context window.
        StringBuilder outputs = new StringBuilder();
        Set<Long> sourceRawIds = new LinkedHashSet<>();
        int totalChars = 0;
        int sourcesIncluded = 0;
        for (WikiTransformationRunEntity run : distinct) {
            WikiRawMaterialEntity raw = rawService.getById(run.getRawId());
            String sourceTitle = raw != null && raw.getTitle() != null && !raw.getTitle().isBlank()
                    ? raw.getTitle() : ("raw#" + run.getRawId());
            String body = run.getOutput().length() > PER_SOURCE_SOFT_CAP
                    ? run.getOutput().substring(0, PER_SOURCE_SOFT_CAP) + "\n…(truncated for merge)"
                    : run.getOutput();
            String block = "### From: " + sourceTitle + "\n\n" + body + "\n\n---\n\n";
            if (totalChars + block.length() > MAX_AGG_INPUT_CHARS) {
                log.warn("[WikiAggregator] template={} kb={} stopping merge at {} sources to stay under {} chars",
                        template.getName(), kbId, sourcesIncluded, MAX_AGG_INPUT_CHARS);
                break;
            }
            outputs.append(block);
            sourceRawIds.add(run.getRawId());
            totalChars += block.length();
            sourcesIncluded++;
        }
        if (sourcesIncluded == 0) return Result.empty("all source outputs were empty after dedup");

        // LLM call
        String systemPrompt = PromptLoader.loadPrompt("wiki/transformation-aggregate-system");
        String userPrompt = PromptLoader.loadPrompt("wiki/transformation-aggregate-user")
                .replace("{template_title}", template.getTitle() == null ? template.getName() : template.getTitle())
                .replace("{template_description}", template.getDescription() == null ? "" : template.getDescription())
                .replace("{outputs}", outputs.toString());

        Long modelId = template.getModelId() != null
                ? template.getModelId()
                : modelRoutingService.selectModelId(kbId, "heavy_ingest", WikiJobStep.CREATE_PAGE);
        ChatModel chatModel = modelRoutingService.buildChatModel(modelId);

        ChatResponse resp = chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt), new UserMessage(userPrompt))));
        String mergedOutput = (resp == null || resp.getResult() == null
                || resp.getResult().getOutput() == null) ? null : resp.getResult().getOutput().getText();
        if (mergedOutput == null || mergedOutput.isBlank()) {
            throw new IllegalStateException("Aggregator LLM returned empty output");
        }
        mergedOutput = WikiTransformationExecutor.cleanLlmOutput(mergedOutput);

        // Upsert the aggregate page on a deterministic slug so re-aggregation
        // refreshes it in place instead of spawning duplicates.
        String slug = template.getName() + "-aggregate";
        String title = (template.getTitle() == null ? template.getName() : template.getTitle())
                + "（KB 聚合）";
        String summary = sourcesIncluded + " 个原始材料合并 · "
                + (triggeredBy == null ? "manual" : triggeredBy);
        String sourceRawIdsJson = toJsonArray(new ArrayList<>(sourceRawIds));

        WikiPageEntity existing = pageService.getBySlug(kbId, slug);
        WikiPageEntity persisted;
        boolean created;
        if (existing == null) {
            persisted = pageService.createPage(kbId, slug, title, mergedOutput, summary,
                    sourceRawIdsJson, "synthesis");
            created = true;
        } else {
            persisted = pageService.updatePageByAi(kbId, slug, mergedOutput, summary,
                    sourceRawIds.iterator().next());
            if (persisted == null) persisted = existing;
            created = false;
        }
        log.info("[WikiAggregator] {} aggregate page slug={} for template={} kb={} ({} sources, {} chars in)",
                created ? "created" : "updated", slug, template.getName(), kbId,
                sourcesIncluded, totalChars);

        // Fire-and-forget page embed so the aggregate joins semantic search.
        if (embeddingService != null) {
            final Long pid = persisted.getId();
            Thread.startVirtualThread(() -> {
                try { embeddingService.embedPage(pid); }
                catch (Exception ee) {
                    log.warn("[WikiAggregator] post-aggregate embed failed pageId={}: {}",
                            pid, ee.getMessage());
                }
            });
        }

        return new Result(persisted.getId(), slug, title, sourcesIncluded, totalChars, created);
    }

    private String toJsonArray(List<Long> ids) {
        try { return objectMapper.writeValueAsString(ids); }
        catch (Exception e) {
            return ids.toString().replace(" ", "");
        }
    }
}
