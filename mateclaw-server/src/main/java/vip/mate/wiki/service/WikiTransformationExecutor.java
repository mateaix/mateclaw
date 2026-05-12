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

    /** Optional. When wired, every persisted synthesis page is embedded so
     *  the semantic retriever can surface it on terms that exist only in the
     *  transformation output (not in any source raw's chunks). */
    @Autowired(required = false)
    private WikiEmbeddingService embeddingService;

    /** Optional. When wired, the executor parses references like
     *  {@code 第 5 题 / 第 14 页 / page 14} out of the output and writes
     *  chunk-level citations binding the synthesis page back to the source
     *  chunks the LLM said it relied on. */
    @Autowired(required = false)
    private WikiTransformationCitationExtractor citationExtractor;

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

    public CompletableFuture<WikiTransformationRunEntity> runOnPageAsync(
            WikiTransformationEntity transformation, Long pageId, String triggeredBy) {
        return CompletableFuture.supplyAsync(
                () -> runOnPageSync(transformation, pageId, triggeredBy), WORKER);
    }

    /**
     * Run the transformation against an existing wiki page (e.g. a previous
     * synthesis page or a manually-authored page). Mirrors
     * {@link #runOnRawSync} but uses page content as the source. Output is
     * not auto-saved as a wiki page even when the template has
     * {@code outputTarget=page} — overwriting the input page would be
     * surprising; users can still save manually from the run history.
     */
    public WikiTransformationRunEntity runOnPageSync(
            WikiTransformationEntity transformation, Long pageId, String triggeredBy) {
        if (transformation == null) {
            throw new IllegalArgumentException("transformation is required");
        }
        if (pageId == null) {
            throw new IllegalArgumentException("pageId is required");
        }
        if (pageService == null) {
            throw new IllegalStateException("Page service unavailable");
        }
        WikiPageEntity page = pageService.getById(pageId);
        if (page == null) {
            throw new IllegalArgumentException("Page not found: " + pageId);
        }
        if (Boolean.FALSE.equals(transformation.getEnabled())) {
            log.debug("[WikiTransformation] skipping disabled template id={} name={}",
                    transformation.getId(), transformation.getName());
            return null;
        }

        long startNanos = System.nanoTime();
        WikiTransformationRunEntity run = new WikiTransformationRunEntity();
        run.setTransformationId(transformation.getId());
        run.setKbId(page.getKbId());
        run.setWorkspaceId(transformation.getWorkspaceId());
        run.setInputKind("page");
        run.setPageId(pageId);
        run.setStatus("running");
        run.setTriggeredBy(triggeredBy == null ? "manual" : triggeredBy);
        run.setStartedAt(LocalDateTime.now());
        transformationService.insertRun(run);

        try {
            String inputText = page.getContent();
            if (inputText == null || inputText.isBlank()) {
                throw new IllegalStateException("Page has no content");
            }
            String output = renderAndCallLlm(transformation, page.getKbId(),
                    page.getTitle() == null ? ("page#" + pageId) : page.getTitle(),
                    inputText, run);

            WikiTransformationRunEntity current = transformationService.getRun(run.getId());
            if (current != null && "cancelled".equalsIgnoreCase(current.getStatus())) {
                log.info("[WikiTransformation] run={} was cancelled mid-flight; discarding {} chars of LLM output",
                        run.getId(), output.length());
                return current;
            }

            run.setOutput(output);
            run.setStatus("completed");
            run.setCompletedAt(LocalDateTime.now());
            run.setDurationMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            // For page input we never auto-save back to a page — the call site
            // can use the manual save-as-page endpoint with a derived slug.
            transformationService.updateRun(run);

            metrics.recordCompileStage("transformation_run", page.getKbId(),
                    Duration.ofNanos(System.nanoTime() - startNanos));
            log.info("[WikiTransformation] ok run={} transformation={} pageId={} kbId={} ({} ms)",
                    run.getId(), transformation.getName(), pageId, page.getKbId(),
                    run.getDurationMs());
        } catch (Exception e) {
            WikiTransformationRunEntity current = transformationService.getRun(run.getId());
            if (current != null && "cancelled".equalsIgnoreCase(current.getStatus())) {
                log.info("[WikiTransformation] run={} was cancelled before failure could be recorded ({})",
                        run.getId(), e.getMessage());
                return current;
            }
            run.setStatus("failed");
            String msg = e.getMessage();
            run.setError(msg == null ? e.getClass().getSimpleName() : msg);
            run.setCompletedAt(LocalDateTime.now());
            run.setDurationMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            transformationService.updateRun(run);
            log.warn("[WikiTransformation] failed run={} transformation={} pageId={}: {}",
                    run.getId(), transformation.getName(), pageId, msg);
        }
        return run;
    }

    /**
     * Shared prompt-render + LLM-call + output-cleanup stage used by both
     * raw-input and page-input entry points. Sets {@code run.modelId} as a
     * side-effect so the run row reflects which model produced the output.
     * <p>
     * When the template declares {@code outputFormat=json}, the response is
     * parsed as JSON; on parse failure the LLM is asked once more with a
     * stricter "return only JSON" reminder before the run is failed.
     */
    private String renderAndCallLlm(WikiTransformationEntity transformation, Long kbId,
                                     String sourceTitle, String sourceText,
                                     WikiTransformationRunEntity run) {
        String trimmedInput = sourceText.length() > MAX_INPUT_CHARS
                ? sourceText.substring(0, MAX_INPUT_CHARS) + "\n…(truncated)"
                : sourceText;

        boolean wantJson = "json".equalsIgnoreCase(transformation.getOutputFormat());
        String schema = transformation.getOutputSchema();
        boolean hasSchema = wantJson && schema != null && !schema.isBlank();

        String systemPrompt = PromptLoader.loadPrompt(
                wantJson ? "wiki/transformation-system-json" : "wiki/transformation-system");
        String instruction = (transformation.getPromptTemplate() == null ? "" : transformation.getPromptTemplate())
                .replace("{input_text}", trimmedInput)
                .replace("{title}", sourceTitle);
        if (hasSchema) {
            instruction = instruction
                    + "\n\n---\n\n输出必须严格符合下面这个 JSON Schema：\n```json\n"
                    + schema + "\n```";
        }
        String userPrompt = PromptLoader.loadPrompt("wiki/transformation-user")
                .replace("{instruction}", instruction)
                .replace("{source_title}", sourceTitle)
                .replace("{source_text}", trimmedInput);

        Long resolvedModelId = resolveModelId(transformation, kbId);
        ChatModel chatModel = buildChatModel(resolvedModelId);
        run.setModelId(resolvedModelId);

        CallResult first = callOnce(chatModel, systemPrompt, userPrompt);
        accumulateUsage(run, first);
        if (wantJson) {
            String coerced = coerceToJson(first.text());
            String validationError = coerced != null ? validateAgainstSchema(coerced, schema) : "not valid JSON";
            if (coerced != null && validationError == null) {
                // Wrap in a fenced block so UI rendering and save-as-page
                // keep the existing markdown contract. The raw JSON is the
                // first thing inside the block, so downstream tools can grep.
                return "```json\n" + coerced + "\n```";
            }
            // One retry with an explicit nudge about what failed.
            log.info("[WikiTransformation] JSON validation failed for template={} ({}); retrying with stricter reminder",
                    transformation.getName(), validationError);
            String reminder = "上一次回复无效：" + validationError + "。请只返回一个合法 JSON 文档，"
                    + "前后不要有任何文字或代码块标记"
                    + (hasSchema ? "，并严格匹配上面给出的 JSON Schema。" : "。");
            String retryUserPrompt = userPrompt + "\n\n---\n\n" + reminder;
            CallResult retry = callOnce(chatModel, systemPrompt, retryUserPrompt);
            accumulateUsage(run, retry);
            String coercedRetry = coerceToJson(retry.text());
            String retryError = coercedRetry != null ? validateAgainstSchema(coercedRetry, schema) : "not valid JSON";
            if (coercedRetry != null && retryError == null) {
                return "```json\n" + coercedRetry + "\n```";
            }
            throw new IllegalStateException("LLM output failed JSON validation after one retry: " + retryError);
        }
        return first.text();
    }

    /**
     * Lightweight JSON Schema check — verifies the parsed value is the
     * declared top-level type and contains every entry in the
     * {@code required} array. Deep validation (per-field types, enums,
     * patterns) is out of scope; the prompt-time schema injection does
     * most of the work and this check just guards the obvious failures.
     *
     * @return {@code null} when valid, otherwise a short failure description
     */
    private static String validateAgainstSchema(String jsonText, String schemaText) {
        if (schemaText == null || schemaText.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode value = JSON_MAPPER.readTree(jsonText);
            com.fasterxml.jackson.databind.JsonNode schema = JSON_MAPPER.readTree(schemaText);

            String type = schema.path("type").asText("");
            if ("object".equals(type) && !value.isObject()) {
                return "expected object at top level, got " + value.getNodeType().name().toLowerCase();
            }
            if ("array".equals(type) && !value.isArray()) {
                return "expected array at top level, got " + value.getNodeType().name().toLowerCase();
            }

            com.fasterxml.jackson.databind.JsonNode required = schema.get("required");
            if (required != null && required.isArray() && value.isObject()) {
                List<String> missing = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode req : required) {
                    String field = req.asText();
                    if (!field.isBlank() && !value.has(field)) missing.add(field);
                }
                if (!missing.isEmpty()) {
                    return "missing required field(s): " + String.join(", ", missing);
                }
            }
            return null;
        } catch (Exception e) {
            return "schema check error: " + e.getMessage();
        }
    }

    /** Tuple returned from a single LLM call: cleaned text + usage (null when provider didn't surface usage). */
    private record CallResult(String text, Long inputTokens, Long outputTokens, Long totalTokens) {}

    /** One LLM call, returns the cleaned output + provider usage. Throws when the call yields blank. */
    private CallResult callOnce(ChatModel chatModel, String systemPrompt, String userPrompt) {
        ChatResponse resp = chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt), new UserMessage(userPrompt))));
        String rawOutput = (resp == null || resp.getResult() == null
                || resp.getResult().getOutput() == null)
                ? null : resp.getResult().getOutput().getText();
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new IllegalStateException("LLM returned empty output");
        }
        String output = cleanLlmOutput(rawOutput);
        if (output.isBlank()) {
            throw new IllegalStateException("LLM output was empty after cleanup");
        }
        Long in = null, out = null, total = null;
        try {
            if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                var u = resp.getMetadata().getUsage();
                in = u.getPromptTokens() == null ? null : u.getPromptTokens().longValue();
                out = u.getCompletionTokens() == null ? null : u.getCompletionTokens().longValue();
                total = u.getTotalTokens() == null ? null : u.getTotalTokens().longValue();
            }
        } catch (Exception ignored) {
            // Usage extraction is best-effort — different providers expose it differently.
        }
        return new CallResult(output, in, out, total);
    }

    /** Add provider-reported usage onto the run row (accumulates across retries). */
    private static void accumulateUsage(WikiTransformationRunEntity run, CallResult call) {
        if (call.inputTokens() != null) {
            run.setInputTokens((run.getInputTokens() == null ? 0L : run.getInputTokens()) + call.inputTokens());
        }
        if (call.outputTokens() != null) {
            run.setOutputTokens((run.getOutputTokens() == null ? 0L : run.getOutputTokens()) + call.outputTokens());
        }
        if (call.totalTokens() != null) {
            run.setTotalTokens((run.getTotalTokens() == null ? 0L : run.getTotalTokens()) + call.totalTokens());
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Try to parse the output as JSON. If the LLM wrapped it in a fenced
     * block or sprinkled prose around it, fall back to finding the outer
     * '{' / '[' brackets and try again. Returns the normalized JSON string
     * on success, {@code null} on failure.
     */
    private static String coerceToJson(String text) {
        if (text == null || text.isBlank()) return null;
        String candidate = text.trim();
        try {
            JSON_MAPPER.readTree(candidate);
            return candidate;
        } catch (Exception ignored) {
            // fall through to bracket-trim attempt
        }
        int objStart = candidate.indexOf('{');
        int arrStart = candidate.indexOf('[');
        int start;
        char open;
        if (objStart < 0) { start = arrStart; open = '['; }
        else if (arrStart < 0) { start = objStart; open = '{'; }
        else { start = Math.min(objStart, arrStart); open = candidate.charAt(start); }
        if (start < 0) return null;
        char close = open == '{' ? '}' : ']';
        int end = candidate.lastIndexOf(close);
        if (end <= start) return null;
        String trimmed = candidate.substring(start, end + 1);
        try {
            JSON_MAPPER.readTree(trimmed);
            return trimmed;
        } catch (Exception e) {
            return null;
        }
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
            String output = renderAndCallLlm(transformation, raw.getKbId(),
                    safeTitle(raw), inputText, run);
            // Honour a mid-flight cancel: the cancel endpoint flipped the run
            // row to 'cancelled' while the LLM was still working. Drop the
            // output and stop here rather than overwrite the cancelled state.
            WikiTransformationRunEntity current = transformationService.getRun(run.getId());
            if (current != null && "cancelled".equalsIgnoreCase(current.getStatus())) {
                log.info("[WikiTransformation] run={} was cancelled mid-flight; discarding {} chars of LLM output",
                        run.getId(), output.length());
                return current;
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
            WikiTransformationRunEntity current = transformationService.getRun(run.getId());
            if (current != null && "cancelled".equalsIgnoreCase(current.getStatus())) {
                log.info("[WikiTransformation] run={} was cancelled before failure could be recorded ({})",
                        run.getId(), e.getMessage());
                return current;
            }
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

    /**
     * Mark a still-active run as cancelled. The blocked LLM call (if any)
     * continues server-side but its eventual output is dropped by the
     * post-call check in {@link #runOnRawSync}.
     */
    public boolean cancelRun(Long runId) {
        if (runId == null) return false;
        WikiTransformationRunEntity run = transformationService.getRun(runId);
        if (run == null) return false;
        String status = run.getStatus();
        if (!"pending".equalsIgnoreCase(status) && !"running".equalsIgnoreCase(status)) {
            return false;
        }
        run.setStatus("cancelled");
        run.setCompletedAt(LocalDateTime.now());
        if (run.getError() == null) run.setError("Cancelled by user");
        transformationService.updateRun(run);
        log.info("[WikiTransformation] run={} cancelled by user", runId);
        return true;
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

    /** Recognises the conversational openers LLMs sometimes prepend even when
     *  the system prompt told them not to. Lines matching this pattern at the
     *  very start of the output are dropped. */
    private static final java.util.regex.Pattern PREAMBLE_PATTERN =
            java.util.regex.Pattern.compile(
                    "^\\s*(以下是|下面是|这是|根据您的要求|Sure(?:!|,)?|Of course[!,]?|Here(?:'s| is| are)|Certainly[!,]?|Got it[!,]?)[^\\n]*[:：][^\\n]*\\n+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Normalise raw LLM output before persisting:
     * <ul>
     *   <li>strip an outer markdown / language code fence (```markdown ... ``` or ``` ... ```)</li>
     *   <li>drop a conversational opener line ending with a colon</li>
     *   <li>trim whitespace</li>
     * </ul>
     * Conservative — only trims when the heuristic match is unambiguous,
     * because over-trimming on a structured response would corrupt content.
     */
    static String cleanLlmOutput(String text) {
        if (text == null) return "";
        String result = text.trim();

        // Outer code fence ```lang? ... ```
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            if (firstNewline > 0 && result.endsWith("```")) {
                String header = result.substring(3, firstNewline).trim();
                // Only strip when the header is empty or looks like a language tag
                // (markdown / md / json / yaml / text / plaintext) — never strip
                // when the LLM used ``` as actual fenced content inside.
                if (header.isEmpty() || header.matches("(?i)markdown|md|text|plaintext|json|yaml|yml|html?")) {
                    result = result.substring(firstNewline + 1, result.length() - 3).trim();
                }
            }
        }

        // Conversational opener line ending with a colon, followed by content.
        java.util.regex.Matcher m = PREAMBLE_PATTERN.matcher(result);
        if (m.find()) {
            result = result.substring(m.end()).trim();
        }

        return result;
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

        // Fire-and-forget page-level embedding so semantic search can match
        // vocabulary the LLM authored which isn't present in the source raw's
        // chunks (e.g. "AM-GM", "柯西不等式" derived from a garbled OCR PDF).
        if (embeddingService != null) {
            final Long pid = persisted.getId();
            WORKER.submit(() -> {
                try { embeddingService.embedPage(pid); }
                catch (Exception ee) {
                    log.warn("[WikiTransformation] post-save embedPage failed pageId={}: {}",
                            pid, ee.getMessage());
                }
            });
        }

        // Fire-and-forget reverse-citation extraction. If the LLM cited
        // specific page numbers / problem numbers, write precise chunk
        // citations binding the synthesis page back to those source chunks.
        if (citationExtractor != null) {
            final Long pid = persisted.getId();
            final Long kid = kbId;
            final Long rid = raw.getId();
            final String out = output;
            WORKER.submit(() -> {
                try { citationExtractor.extractAndApply(pid, kid, rid, out); }
                catch (Exception ee) {
                    log.warn("[WikiTransformation] post-save citation extract failed pageId={}: {}",
                            pid, ee.getMessage());
                }
            });
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
