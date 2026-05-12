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
import vip.mate.wiki.metrics.WikiMetrics;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.model.WikiTransformationRunEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a single {@link WikiTransformationEntity} against a source raw
 * material: substitutes placeholders into the user-defined prompt, calls
 * the configured chat model, and persists the run row with the output.
 *
 * <p>Sync entry point: {@link #runOnRawSync}. Async fire-and-forget
 * helpers (used by the ingest-pipeline hook and the controller's "apply"
 * endpoint when the caller doesn't want to block) wrap that on a virtual
 * thread executor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiTransformationExecutor {

    /** Virtual-thread pool — matches the WIKI_EXECUTOR pattern used elsewhere in the module. */
    private static final ExecutorService WORKER = Executors.newVirtualThreadPerTaskExecutor();

    /** Hard cap on input text fed into the prompt (defensive against multi-MB extracted PDFs). */
    private static final int MAX_INPUT_CHARS = 60_000;

    private final WikiTransformationService transformationService;
    private final WikiRawMaterialService rawService;
    private final WikiMetrics metrics;

    @Autowired(required = false)
    private WikiModelRoutingService modelRoutingService;

    /** Optional. When wired, completed runs whose template has
     *  {@code outputTarget=page} are persisted as a synthesis wiki page. */
    @Autowired(required = false)
    private WikiPageService pageService;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public CompletableFuture<Void> runDefaultsAsync(Long kbId, Long workspaceId, Long rawId, String triggeredBy) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<WikiTransformationEntity> defaults =
                        transformationService.listApplyDefaultsForKb(kbId, workspaceId);
                for (WikiTransformationEntity t : defaults) {
                    try {
                        runOnRawSync(t, rawId, triggeredBy);
                    } catch (Exception e) {
                        log.warn("[WikiTransformation] default run failed transformation={} rawId={}: {}",
                                t.getName(), rawId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[WikiTransformation] default sweep failed kbId={} rawId={}: {}",
                        kbId, rawId, e.getMessage());
            }
        }, WORKER);
    }

    public CompletableFuture<WikiTransformationRunEntity> runOnRawAsync(
            WikiTransformationEntity transformation, Long rawId, String triggeredBy) {
        return CompletableFuture.supplyAsync(
                () -> runOnRawSync(transformation, rawId, triggeredBy), WORKER);
    }

    /**
     * Run the transformation against the given raw material and persist
     * the outcome. The returned entity is the persisted run row, regardless
     * of success or failure (failure leaves {@code status=failed} and
     * {@code error} populated).
     */
    public WikiTransformationRunEntity runOnRawSync(
            WikiTransformationEntity transformation, Long rawId, String triggeredBy) {
        if (transformation == null) {
            throw new IllegalArgumentException("transformation is required");
        }
        if (rawId == null) {
            throw new IllegalArgumentException("rawId is required");
        }
        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null) {
            throw new IllegalArgumentException("Raw material not found: " + rawId);
        }
        if (Boolean.FALSE.equals(transformation.getEnabled())) {
            log.debug("[WikiTransformation] skipping disabled template id={} name={}",
                    transformation.getId(), transformation.getName());
            return null;
        }

        long startNanos = System.nanoTime();
        WikiTransformationRunEntity run = new WikiTransformationRunEntity();
        run.setTransformationId(transformation.getId());
        run.setKbId(raw.getKbId());
        run.setWorkspaceId(transformation.getWorkspaceId());
        run.setInputKind("raw");
        run.setRawId(rawId);
        run.setStatus("running");
        run.setTriggeredBy(triggeredBy == null ? "manual" : triggeredBy);
        run.setStartedAt(LocalDateTime.now());
        transformationService.insertRun(run);

        try {
            String inputText = rawService.getTextContent(raw);
            if (inputText == null || inputText.isBlank()) {
                throw new IllegalStateException("Raw material has no extractable text yet");
            }
            String trimmedInput = inputText.length() > MAX_INPUT_CHARS
                    ? inputText.substring(0, MAX_INPUT_CHARS) + "\n…(truncated)"
                    : inputText;

            String systemPrompt = PromptLoader.loadPrompt("wiki/transformation-system");
            String userPrompt = PromptLoader.loadPrompt("wiki/transformation-user")
                    .replace("{instruction}", renderTemplate(transformation.getPromptTemplate(), raw, trimmedInput))
                    .replace("{source_title}", safeTitle(raw))
                    .replace("{source_text}", trimmedInput);

            Long resolvedModelId = resolveModelId(transformation, raw.getKbId());
            ChatModel chatModel = buildChatModel(resolvedModelId);
            run.setModelId(resolvedModelId);

            ChatResponse resp = chatModel.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt), new UserMessage(userPrompt))));
            String output = (resp == null || resp.getResult() == null
                    || resp.getResult().getOutput() == null)
                    ? null : resp.getResult().getOutput().getText();
            if (output == null || output.isBlank()) {
                throw new IllegalStateException("LLM returned empty output");
            }
            run.setOutput(output);
            run.setStatus("completed");
            run.setCompletedAt(LocalDateTime.now());
            run.setDurationMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());

            // Persist as a synthesis wiki page when the template asks for it.
            // Failures here are logged but do not flip the run back to failed:
            // the LLM output is already valid, the page-write is best-effort.
            if ("page".equalsIgnoreCase(transformation.getOutputTarget())) {
                try {
                    WikiPageEntity page = saveRunAsPage(run, transformation, raw, output);
                    if (page != null) run.setOutputPageId(page.getId());
                } catch (Exception pe) {
                    log.warn("[WikiTransformation] auto-save as page failed run={}: {}",
                            run.getId(), pe.getMessage());
                }
            }
            transformationService.updateRun(run);

            metrics.recordCompileStage("transformation_run", raw.getKbId(),
                    Duration.ofNanos(System.nanoTime() - startNanos));
            log.info("[WikiTransformation] ok run={} transformation={} rawId={} kbId={} ({} ms, pageId={})",
                    run.getId(), transformation.getName(), rawId, raw.getKbId(),
                    run.getDurationMs(), run.getOutputPageId());
        } catch (Exception e) {
            run.setStatus("failed");
            String msg = e.getMessage();
            run.setError(msg == null ? e.getClass().getSimpleName() : msg);
            run.setCompletedAt(LocalDateTime.now());
            run.setDurationMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            transformationService.updateRun(run);
            log.warn("[WikiTransformation] failed run={} transformation={} rawId={}: {}",
                    run.getId(), transformation.getName(), rawId, msg);
        }
        return run;
    }

    private String renderTemplate(String template, WikiRawMaterialEntity raw, String inputText) {
        if (template == null) return "";
        String result = template;
        result = result.replace("{input_text}", inputText);
        result = result.replace("{title}", safeTitle(raw));
        return result;
    }

    private Long resolveModelId(WikiTransformationEntity transformation, Long kbId) {
        if (transformation.getModelId() != null) return transformation.getModelId();
        if (modelRoutingService == null) {
            throw new IllegalStateException("No model bound on transformation and ModelRoutingService unavailable");
        }
        return modelRoutingService.selectModelId(kbId, "heavy_ingest", WikiJobStep.CREATE_PAGE);
    }

    private ChatModel buildChatModel(Long modelId) {
        if (modelRoutingService == null) {
            throw new IllegalStateException("ModelRoutingService unavailable; cannot run transformation");
        }
        return modelRoutingService.buildChatModel(modelId);
    }

    private static String safeTitle(WikiRawMaterialEntity raw) {
        String t = raw.getTitle();
        return (t == null || t.isBlank()) ? ("raw#" + raw.getId()) : t;
    }

    // ==================== Save-as-page ====================

    /**
     * Manual entry point used by the {@code POST /runs/{runId}/save-as-page}
     * endpoint. Loads the run + its template + its source raw material,
     * delegates to {@link #saveRunAsPage}, and updates the run row with the
     * resulting page id so the UI can render a "saved as: …" affordance.
     *
     * @return the persisted page; never {@code null} on success
     * @throws IllegalArgumentException when the run / raw / template is missing
     * @throws IllegalStateException    when the run is not completed or no output
     */
    public WikiPageEntity manualSaveRunAsPage(Long runId) {
        if (runId == null) throw new IllegalArgumentException("runId is required");
        WikiTransformationRunEntity run = transformationService.getRun(runId);
        if (run == null) throw new IllegalArgumentException("Run not found: " + runId);
        if (!"completed".equalsIgnoreCase(run.getStatus())) {
            throw new IllegalStateException("Run is not completed (status=" + run.getStatus() + ")");
        }
        if (run.getOutput() == null || run.getOutput().isBlank()) {
            throw new IllegalStateException("Run has no output to save");
        }
        if (run.getRawId() == null) {
            throw new IllegalStateException("Run is not bound to a raw material");
        }
        WikiTransformationEntity template = transformationService.getById(run.getTransformationId());
        if (template == null) {
            throw new IllegalStateException("Transformation template no longer exists");
        }
        WikiRawMaterialEntity raw = rawService.getById(run.getRawId());
        if (raw == null) {
            throw new IllegalStateException("Source raw material no longer exists");
        }
        WikiPageEntity page = saveRunAsPage(run, template, raw, run.getOutput());
        if (page != null) {
            run.setOutputPageId(page.getId());
            transformationService.updateRun(run);
        }
        return page;
    }

    /**
     * Upsert the transformation output as a synthesis wiki page on the same
     * KB. Slug is deterministic — {@code <template.name>-<raw.title-slug-or-id>} —
     * so re-running an apply_default template against the same raw material
     * updates the existing page in place rather than spawning duplicates.
     */
    private WikiPageEntity saveRunAsPage(WikiTransformationRunEntity run,
                                          WikiTransformationEntity template,
                                          WikiRawMaterialEntity raw,
                                          String output) {
        if (pageService == null) {
            log.warn("[WikiTransformation] save-as-page requested but WikiPageService not available");
            return null;
        }
        Long kbId = raw.getKbId();
        String slug = buildSlug(template, raw);
        String title = template.getTitle() + " · " + safeTitle(raw);
        String summary = deriveSummary(output);
        String sourceRawIdsJson = toJsonArray(raw.getId());

        WikiPageEntity existing = pageService.getBySlug(kbId, slug);
        WikiPageEntity persisted;
        if (existing == null) {
            persisted = pageService.createPage(kbId, slug, title, output, summary,
                    sourceRawIdsJson, "synthesis");
            log.info("[WikiTransformation] saved run={} as new page slug={} pageId={}",
                    run.getId(), slug, persisted.getId());
        } else {
            persisted = pageService.updatePageByAi(kbId, slug, output, summary, raw.getId());
            if (persisted == null) persisted = existing;
            log.info("[WikiTransformation] updated existing synthesis page slug={} pageId={} from run={}",
                    slug, persisted.getId(), run.getId());
        }
        return persisted;
    }

    /**
     * Common document / image extensions that we don't want leaking into the
     * slug. The pattern matches a trailing dotted extension and is case-
     * insensitive so both {@code foo.PDF} and {@code foo.pdf} are stripped.
     */
    private static final java.util.regex.Pattern FILE_EXT_PATTERN =
            java.util.regex.Pattern.compile(
                    "\\.(pdf|docx?|pptx?|xlsx?|csv|tsv|txt|md|markdown|rtf|odt|epub|html?|json|xml|yaml|yml|jpe?g|png|gif|bmp|tiff?|webp|svg|mp3|wav|mp4|mov|webm)$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static String buildSlug(WikiTransformationEntity template, WikiRawMaterialEntity raw) {
        String trimmedTitle = stripFileExtension(raw.getTitle());
        String rawPart = WikiPageService.toSlug(trimmedTitle);
        if (rawPart == null || rawPart.isBlank()) rawPart = "r" + raw.getId();
        return template.getName() + "-" + rawPart;
    }

    private static String stripFileExtension(String title) {
        if (title == null) return null;
        return FILE_EXT_PATTERN.matcher(title.trim()).replaceFirst("");
    }

    /** First non-empty line of the output, capped to ~280 chars, used as page summary. */
    private static String deriveSummary(String output) {
        if (output == null) return "";
        for (String line : output.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#")) {
                trimmed = trimmed.replaceAll("^#+\\s*", "");
                if (trimmed.isEmpty()) continue;
            }
            return trimmed.length() > 280 ? trimmed.substring(0, 280) + "…" : trimmed;
        }
        return "";
    }

    private String toJsonArray(Long rawId) {
        try {
            return objectMapper.writeValueAsString(java.util.List.of(rawId));
        } catch (Exception e) {
            return "[" + rawId + "]";
        }
    }
}
