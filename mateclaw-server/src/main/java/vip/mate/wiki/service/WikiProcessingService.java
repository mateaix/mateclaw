package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.WikiChunkDraft;
import vip.mate.wiki.event.WikiProcessingEvent;
import vip.mate.wiki.job.WikiKbConfig;
import vip.mate.wiki.job.WikiKbConfigParser;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.sse.WikiProgressBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wiki 处理服务
 * <p>
 * 核心管线：将原始材料通过 LLM 消化为结构化 Wiki 页面。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiProcessingService {

    private final WikiKnowledgeBaseService kbService;
    private final WikiRawMaterialService rawService;
    private final WikiPageService pageService;
    private final WikiChunkService chunkService;
    private final WikiEmbeddingService embeddingService;
    private final WikiLinkService linkService;
    private final WikiProperties properties;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final ObjectMapper objectMapper;
    private final WikiProgressBus progressBus;
    private final WikiCitationService citationService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * Read-the-failover-chain handle. Optional so the existing constructors and
     * lazy-mode tests don't have to thread a new dependency. When null, the
     * fallback hop iterates {@code listEnabledModels} in DB order — same
     * behavior as before this PR.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private vip.mate.llm.service.ModelProviderService modelProviderService;

    /**
     * Per-provider failure counter / cooldown bookkeeping. Optional for the
     * same reason. When wired, fatal errors mark the provider down so other
     * code paths (chat agent, fallback chain) skip it during cooldown; on a
     * successful call we clear the failure counter for the provider that
     * actually responded.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private vip.mate.llm.failover.ProviderHealthTracker providerHealthTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private vip.mate.wiki.job.WikiProcessingJobService wikiJobService;

    /**
     * RFC-051 PR-1c: optional preprocessor that fills chunk metadata
     * (page_number / token_count / header_breadcrumb / source_section).
     * Marked optional so unit tests that construct this service directly
     * (without Spring) can opt out without exploding.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DocumentPreprocessService preprocessService;

    /**
     * RFC-051 PR-2: ensures system-page scaffold (overview / log) exists for
     * the KB before each ingest. Optional so the older lazy-only unit tests
     * don't need to wire it.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WikiScaffoldService scaffoldService;

    /**
     * RFC-051 PR-3: optional model routing service. When wired, route /
     * create_page / merge_page LLM calls inside the eager pipeline ask the
     * routing chain (stepModels[step] -&gt; wikiDefaultModelId -&gt; system
     * default) for a model rather than always pulling the system default.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private vip.mate.wiki.job.WikiModelRoutingService modelRoutingService;

    /** RFC-051 PR-2b/2c: optional overview rebuilder + log appender. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WikiOverviewService overviewService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WikiLogService logService;

    /**
     * Optional. When present, every successful ingest triggers an async sweep
     * of the KB's apply-default transformation templates. Missing in the
     * legacy unit tests that wire this service directly.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private WikiTransformationExecutor transformationExecutor;

    /** Parallel chunk / material processing executor (JDK 21 virtual threads) */
    public static final ExecutorService WIKI_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * RFC-012 M2 v2 UI v2：单 raw 的进度计数器，多个并行 chunk 的 {@code processChunkTwoPhase}
     * 共享同一份 atomic 计数，避免 6 个 chunk 各写各的 progress 字段时互相覆盖（导致 UI 永远 preparing）。
     * <p>
     * 生命周期：{@code processRawMaterial} 入口 put，try/finally 出口 remove。
     */
    private static final class ProgressCounter {
        final AtomicInteger total = new AtomicInteger(0);
        final AtomicInteger done = new AtomicInteger(0);
        /** Page-level 失败计数（chunk 内单页 create / merge 抛异常）。
         *  注：DuplicateKeyException 触发的 fallback-to-update 不算 failure，
         *  内容仍合入了同 slug page。仅 LLM 调用爆炸、JSON 解析失败、内容为空等真失败才递增。 */
        final AtomicInteger failed = new AtomicInteger(0);
        final AtomicBoolean phaseBStarted = new AtomicBoolean(false);
        /**
         * 跨 chunk slug 抢占表：canonical slug → 第一个声明该概念的实际 slug。
         * <p>
         * 解决 LLM 在并行 chunk 中给同一概念起不同 slug 拼写（按词分组 vs 按字分隔）的问题。
         * 使用 {@link ConcurrentHashMap#computeIfAbsent} 实现原子抢占：先到的 chunk 把自己的
         * slug 注册为 winner，后到的 chunk 看到 winner 后会把内容写入 winner 对应的 page。
         */
        final ConcurrentHashMap<String, String> slugClaims = new ConcurrentHashMap<>();
        /**
         * Per-run merge dedup set: slugs that have already been successfully merged during
         * this raw material processing run. Prevents the same page from being merged N times
         * (once per chunk) when the document repeatedly references the same concept.
         * <p>
         * Merge is skipped (not just decremented from count) when a slug is already present.
         * Uses ConcurrentHashMap as a concurrent set via putIfAbsent.
         */
        final ConcurrentHashMap<String, Boolean> mergedSlugs = new ConcurrentHashMap<>();
    }

    private final ConcurrentHashMap<Long, ProgressCounter> progressCounters = new ConcurrentHashMap<>();

    /**
     * Process one raw material.
     */
    public void processRawMaterial(Long rawId) {
        processRawMaterial(rawId, false);
    }

    /**
     * Process one raw material, optionally bypassing the content-hash shortcut.
     *
     * @param rawId raw material ID
     * @param force when true, ignore content_hash so model or prompt changes can re-run the full pipeline
     */
    public void processRawMaterial(Long rawId, boolean force) {
        // Consume the partial-resume marker before claimForProcessing changes
        // the row status again. The marker is in-memory only; after a restart
        // the pending row falls back to a full re-run.
        boolean isPartialResume = rawService.consumePartialResumeFlag(rawId);

        // Claim once so concurrent workers do not process the same raw twice.
        if (!rawService.claimForProcessing(rawId)) {
            log.debug("[Wiki] Raw material {} already claimed or not pending, skipping", rawId);
            return;
        }

        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null) {
            log.warn("[Wiki] Raw material not found: {}", rawId);
            return;
        }

        // Skip unchanged content unless the caller requested a forced re-run.
        if (!force
                && raw.getContentHash() != null
                && raw.getContentHash().equals(raw.getLastProcessedHash())) {
            rawService.updateProcessingStatus(rawId, "completed", "Skipped: content unchanged since last processing");
            log.info("[Wiki] Skip reprocessing raw={} (content unchanged, hash={})", rawId, raw.getContentHash());
            return;
        }

        WikiKnowledgeBaseEntity kb = kbService.getById(raw.getKbId());
        if (kb == null) {
            log.warn("[Wiki] Knowledge base not found for raw material: kbId={}", raw.getKbId());
            return;
        }

        kbService.updateStatus(kb.getId(), "processing");

        // Every ingest path opens with a scaffold check so older KBs get their
        // overview / log pages on first use without a manual step.
        if (scaffoldService != null) {
            scaffoldService.ensureScaffold(kb.getId());
        }

        // Lazy ingest skips the heavy generation pipeline: extract, chunk,
        // embed, completed. Zero pages is expected, not a failure.
        if ("lazy".equals(resolveIngestMode(kb))) {
            processLazyIngest(kb, raw);
            return;
        }

        // RFC-030 §9.1: create a processing job record and track its ID for stage transitions
        Long jobId = null;
        if (wikiJobService != null) {
            try {
                var job = wikiJobService.createHeavyIngest(kb.getId(), rawId);
                jobId = job.getId();
                wikiJobService.transition(jobId, vip.mate.wiki.job.WikiJobStage.ROUTING);
            } catch (Exception e) {
                log.warn("[Wiki] Failed to create heavy ingest job record for raw={}: {}", rawId, e.getMessage());
            }
        }

        // RFC-012 M2 v2 UI v2：为本次 raw 处理创建共享进度计数器（多 chunk 共享，避免 race）
        progressCounters.put(rawId, new ProgressCounter());
        rawService.updateProgress(rawId, "route", 0, 0); // UI 立即看到 indeterminate 滑条

        // RFC-012 M3：广播 raw.started（前端切到 indeterminate 进度条）
        progressBus.broadcast(kb.getId(), WikiProgressBus.EVENT_RAW_STARTED,
                java.util.Map.of("rawId", rawId, "phase", "route"));

        try {
            // Phase 1: 获取文本内容
            String textContent = rawService.getTextContent(raw);
            if (textContent == null || textContent.isBlank()) {
                rawService.updateProcessingStatus(rawId, "failed", "No text content available");
                kbService.updateStatus(kb.getId(), "active");
                return;
            }

            // Phase 2: 清除该材料之前生成的旧页面（仅独占+非手工页面）
            // RFC-012 follow-up #3：partial 状态走「续传」路径 —— 保留已生成的 page，让
            // route 阶段通过 existingPagesIndex 把它们归到 update 列表（phase B merge 覆盖
            // 当前 chunk 内容），失败的 slug 在 DB 里不存在，LLM 会放进 create 列表重跑。
            // isPartialResume 标记在 rawService.reprocess() 中写入 in-memory 集合，由
            // rawService.consumePartialResumeFlag() 在 claim 之前消费掉；无法通过
            // raw.getProcessingStatus() 判断是因为 claimForProcessing 已经把它改成 "processing"。
            if (isPartialResume) {
                log.info("[Wiki] Partial resume for raw={}: keeping existing pages, LLM will merge new content into them via existingPagesIndex", rawId);
            } else {
                int cleaned = pageService.deleteExclusiveBySourceRawId(kb.getId(), rawId);
                if (cleaned > 0) {
                    log.info("[Wiki] Cleaned {} exclusive old pages for raw material {} before reprocessing", cleaned, rawId);
                }
            }

            // Phase 3: 构建已有页面索引（一次构建，所有 chunk 共用）
            String existingPagesIndex = buildExistingPagesIndex(kb.getId());

            // Phase 3b: Document-level analysis (optional, RFC-047 follow-up)
            // Single LLM call producing a concept map injected into every chunk's route prompt.
            String documentMap = "";
            if (properties.isUseDocumentAnalysis()) {
                documentMap = analyzeDocument(kb, raw, textContent);
            }

            // Transition job to phase_a (chunk processing begins)
            if (wikiJobService != null && jobId != null) {
                try { wikiJobService.transition(jobId, vip.mate.wiki.job.WikiJobStage.PHASE_A_RUNNING); } catch (Exception ignored) {}
            }

            // Phase 3: LLM 消化
            // result[0] = totalPages, result[1] = failedChunks, result[2] = totalChunks
            int[] result;
            if (textContent.length() > properties.getMaxChunkSize()) {
                result = processInChunks(kb, raw, textContent, existingPagesIndex, documentMap);
            } else {
                // 单 chunk 也持久化（RFC-013：保证所有 chunk 都入库）
                try {
                    chunkService.persistChunks(kb.getId(), rawId,
                            List.of(textContent), List.of(new int[]{0, textContent.length()}));
                } catch (Exception e) {
                    log.warn("[Wiki] Single chunk persistence failed for raw={}: {}", rawId, e.getMessage());
                }
                int pages = processChunk(kb, raw, textContent, existingPagesIndex, documentMap);
                result = new int[]{pages, pages == 0 ? 1 : 0, 1};
            }

            int totalPages = result[0];
            int failedChunks = result[1];
            int totalChunks = result[2];

            // Phase 3: 更新状态和计数
            // 读 page 级失败数（finally 之前读，finally 才 remove counter）
            ProgressCounter pcFinal = progressCounters.get(rawId);
            int failedPages = pcFinal != null ? pcFinal.failed.get() : 0;

            String finalStatus;
            String finalDetail = null;
            // Cancellation takes precedence over the normal terminal-state logic:
            // chunks that observed the cancel flag returned early as "failed", but
            // those aren't real failures — the user asked to stop. Surface that
            // intent explicitly so the UI can show "cancelled" instead of "failed"
            // or "partial".
            if (rawService.isCancelRequested(rawId)) {
                finalDetail = "Cancelled by user (" + totalPages + " page(s) generated, "
                        + (totalChunks - failedChunks) + "/" + totalChunks + " chunks completed before stop).";
                rawService.updateProcessingStatus(rawId, "cancelled", finalDetail);
                finalStatus = "cancelled";
            } else if (totalPages == 0) {
                // RFC-051 follow-up: previously this was an unconditional "failed".
                // But chunks were already persisted (and the materials are searchable
                // via wiki_semantic_search) — the only thing that actually went wrong
                // was the LLM not synthesizing pages. Treat that as partial when chunks
                // landed: search works, the agent can still wiki_compile_page on demand,
                // and the row is rerun-able. Reserve "failed" for the case where nothing
                // got indexed at all.
                if (totalChunks > 0) {
                    finalDetail = "Indexed " + totalChunks
                            + " chunk(s) but no pages were generated. Search and wiki_compile_page still work; reprocess to retry page generation.";
                    rawService.updateProcessingStatus(rawId, "partial", finalDetail);
                    finalStatus = "partial";
                    log.info("[Wiki] Eager produced 0 pages but {} chunks indexed; marking partial for raw={}",
                            totalChunks, rawId);
                } else {
                    rawService.updateProcessingStatus(rawId, "failed", "No pages generated from LLM response");
                    finalStatus = "failed";
                    finalDetail = "No pages generated from LLM response";
                }
            } else if (failedChunks > 0 || failedPages > 0) {
                // 部分成功：chunk 整体失败 或 chunk 内有 page 失败
                // （M2 v2 follow-up：page 级失败原本被计入 completed，现在正确归 partial）
                StringBuilder detail = new StringBuilder();
                if (failedChunks > 0) {
                    detail.append(failedChunks).append(" of ").append(totalChunks).append(" chunks failed");
                }
                if (failedPages > 0) {
                    if (detail.length() > 0) detail.append("; ");
                    detail.append(failedPages).append(" page(s) failed");
                }
                detail.append(", ").append(totalPages).append(" pages generated");
                finalDetail = detail.toString();
                rawService.updateProcessingStatus(rawId, "partial", finalDetail);
                finalStatus = "partial";
                // 【Review Bug 1】partial 不写 lastProcessedHash：partial 的语义就是"还有失败、需要再跑"，
                // 写了会导致下次用户点"重新处理"被 hash 短路直接跳过，永远没机会修失败的 chunk。
            } else {
                rawService.updateProcessingStatus(rawId, "completed", null);
                finalStatus = "completed";
                // Record the successful hash for the next unchanged-content shortcut.
                if (raw.getContentHash() != null) {
                    rawService.setLastProcessedHash(rawId, raw.getContentHash());
                }
            }
            int pageCount = pageService.countByKbId(kb.getId());
            kbService.setPageCount(kb.getId(), pageCount);
            kbService.updateStatus(kb.getId(), "active");

            // RFC-012 M3：广播终态
            if ("failed".equals(finalStatus)) {
                progressBus.broadcast(kb.getId(), WikiProgressBus.EVENT_RAW_FAILED,
                        java.util.Map.of("rawId", rawId, "error", finalDetail == null ? "" : finalDetail));
            } else {
                progressBus.broadcast(kb.getId(), WikiProgressBus.EVENT_RAW_COMPLETED,
                        java.util.Map.of(
                                "rawId", rawId,
                                "status", finalStatus,
                                "totalPages", totalPages,
                                "kbPageCount", pageCount));
            }

            // Transition job to terminal stage
            if (wikiJobService != null && jobId != null) {
                try {
                    var terminalStage = switch (finalStatus) {
                        case "failed" -> vip.mate.wiki.job.WikiJobStage.FAILED;
                        case "partial" -> vip.mate.wiki.job.WikiJobStage.PARTIAL;
                        case "cancelled" -> vip.mate.wiki.job.WikiJobStage.CANCELLED;
                        default -> vip.mate.wiki.job.WikiJobStage.COMPLETED;
                    };
                    wikiJobService.transition(jobId, terminalStage);
                } catch (Exception ignored) {}
            }

            // Skip the post-terminal side effects (log line, overview rebuild,
            // KB-dirty event) for cancelled and failed runs. A cancelled run
            // means the user explicitly stopped — don't burn LLM tokens on
            // overview regeneration over an unstable partial state.
            boolean nonTerminalSideEffects = !"failed".equals(finalStatus) && !"cancelled".equals(finalStatus);
            if (logService != null && nonTerminalSideEffects) {
                String title = (raw.getTitle() == null || raw.getTitle().isBlank())
                        ? ("raw#" + rawId) : raw.getTitle();
                logService.append(kb.getId(), WikiLogService.EventType.INGEST,
                        "eager " + finalStatus + " · " + title
                                + " · " + totalPages + " pages · " + totalChunks + " chunks");
            }
            // Refresh overview stats whenever a raw lands in a terminal state
            // (completed or partial). Failures and cancellations don't shift the stats meaningfully.
            if (overviewService != null && nonTerminalSideEffects) {
                overviewService.rebuild(kb.getId());
            }
            // Tier 2: signal "KB content is dirty" so WikiNarrativeService can
            // schedule (debounced) an LLM-generated overview narrative refresh.
            // Stats rebuild above is sync; narrative regen runs after-commit.
            if (nonTerminalSideEffects) {
                eventPublisher.publishEvent(new vip.mate.wiki.event.WikiKbDirtyEvent(this, kb.getId()));
            }

            // Run apply-default transformation templates against the newly
            // ingested raw material. Fire-and-forget; failures are logged
            // inside the executor and do not affect the ingest outcome.
            if (transformationExecutor != null && nonTerminalSideEffects) {
                Long wsId = kb.getWorkspaceId() == null ? 1L : kb.getWorkspaceId();
                transformationExecutor.runDefaultsAsync(kb.getId(), wsId, rawId, "apply_default");
            }

            log.info("[Wiki] Processing completed for raw={}, kbId={}, generatedPages={}, totalPages={}",
                    rawId, kb.getId(), totalPages, pageCount);

            // RFC-011：异步嵌入新 chunk（不阻塞处理管线）
            // 注意：此方法目前未加 @Transactional，每个 DB 操作短事务独立提交。
            // 如果未来加了事务包裹 processRawMaterial，这里的异步任务需要改用
            // TransactionSynchronizationManager.registerSynchronization(afterCommit)
            // 否则新线程会查不到 chunk（事务未提交）导致 embedding 静默跳过。
            // RFC-051 follow-up: trigger embedding whenever chunks landed, not only when
            // pages were produced. Otherwise the partial-with-no-pages case above ends up
            // with chunks in DB but never embedded, so semantic search silently misses them.
            // Skip the post-ingest embedding sweep when this run was cancelled.
            // The user almost certainly stopped because the embedding provider
            // is failing (out of credits, wrong key, etc.); kicking off another
            // embedding pass on the same provider would just churn through
            // every pending chunk and produce more "all chunks failed" noise.
            if (totalChunks > 0 && !"cancelled".equals(finalStatus)) {
                final Long fKbId = kb.getId();
                WIKI_EXECUTOR.submit(() -> {
                    try {
                        int embedded = embeddingService.embedMissingChunks(fKbId);
                        if (embedded > 0) {
                            log.info("[Wiki] Async embedding completed: kbId={}, embedded={}", fKbId, embedded);
                        }
                    } catch (vip.mate.wiki.job.WikiEmbeddingProviderFailingException ex) {
                        // Circuit-breaker tripped — the provider has consistently failed.
                        // The exception's own log line in WikiEmbeddingService is enough;
                        // emit a calmer notice here instead of a generic failure log.
                        log.warn("[Wiki] Async embedding aborted by circuit-breaker for kbId={}: {}",
                                fKbId, ex.getMessage());
                    } catch (Exception ex) {
                        log.warn("[Wiki] Async embedding failed for kbId={}: {}", fKbId, ex.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            // If the user requested cancellation while this run was in flight,
            // surface the abort as 'cancelled' rather than 'failed' even when
            // the exception bubbled up from somewhere mid-pipeline (e.g. a
            // checkpoint rejected between chunks).
            boolean cancelled = rawService.isCancelRequested(rawId);
            String terminalStatus = cancelled ? "cancelled" : "failed";
            String detail = cancelled
                    ? "Cancelled by user (interrupted: " + (e.getMessage() == null ? "unknown" : e.getMessage()) + ")"
                    : e.getMessage();
            if (cancelled) {
                log.info("[Wiki] Processing cancelled for raw={}: {}", rawId, e.getMessage());
            } else {
                log.error("[Wiki] Processing failed for raw={}: {}", rawId, e.getMessage(), e);
            }
            rawService.updateProcessingStatus(rawId, terminalStatus, detail);
            kbService.updateStatus(kb.getId(), "active");
            if (wikiJobService != null && jobId != null) {
                try {
                    wikiJobService.transition(jobId, cancelled
                            ? vip.mate.wiki.job.WikiJobStage.CANCELLED
                            : vip.mate.wiki.job.WikiJobStage.FAILED);
                } catch (Exception ignored) {}
            }
            // Broadcast: cancelled rows reuse the COMPLETED event with status="cancelled"
            // so subscribers can render the terminal-but-not-error UI; only true failures
            // go through RAW_FAILED (which the UI surfaces as a red banner).
            if (cancelled) {
                progressBus.broadcast(kb.getId(), WikiProgressBus.EVENT_RAW_COMPLETED,
                        java.util.Map.of("rawId", rawId, "status", "cancelled"));
            } else {
                progressBus.broadcast(kb.getId(), WikiProgressBus.EVENT_RAW_FAILED,
                        java.util.Map.of("rawId", rawId, "error", e.getMessage() == null ? "unknown" : e.getMessage()));
            }
        } finally {
            // RFC-012 M2 v2 UI v2：写入最终进度并清理共享计数器
            ProgressCounter pc = progressCounters.remove(rawId);
            if (pc != null) {
                rawService.updateProgress(rawId, "done", pc.done.get(), pc.total.get());
            }
        }
    }

    /**
     * Queue every raw material in a KB for asynchronous processing and
     * return the number of materials queued. Does not block on processing.
     * <p>
     * When {@code force=true}, every raw material is reset to {@code pending}
     * and its {@code last_processed_hash} is cleared so the dedup short-circuit
     * is bypassed; otherwise only currently-{@code pending} materials are
     * republished to the event bus (the same shape as the manual
     * {@code POST /knowledge-bases/{kbId}/process} endpoint).
     *
     * @return the number of raw materials queued
     */
    public int processKB(Long kbId, boolean force) {
        if (kbId == null) {
            throw new IllegalArgumentException("kbId is required");
        }
        if (force) {
            List<WikiRawMaterialEntity> all = rawService.listByKbId(kbId);
            for (WikiRawMaterialEntity raw : all) {
                rawService.setLastProcessedHash(raw.getId(), null);
                rawService.reprocess(raw.getId());
            }
            log.info("[Wiki] processKB queued {} raw material(s) for kbId={} (force=true)", all.size(), kbId);
            return all.size();
        }
        List<WikiRawMaterialEntity> pending = rawService.listPending(kbId);
        for (WikiRawMaterialEntity raw : pending) {
            eventPublisher.publishEvent(new WikiProcessingEvent(this, raw.getId(), kbId));
        }
        log.info("[Wiki] processKB queued {} pending raw material(s) for kbId={}", pending.size(), kbId);
        return pending.size();
    }

    /**
     * 处理知识库中所有待处理的原始材料
     * <p>
     * RFC-012 Change 1：材料级并行，受 {@link WikiProperties#getMaxParallelRawMaterials()} 约束。
     */
    public void processAllPending(Long kbId) {
        List<WikiRawMaterialEntity> pendingList = rawService.listPending(kbId);
        if (pendingList.isEmpty()) {
            log.info("[Wiki] No pending raw materials for kbId={}", kbId);
            return;
        }
        int parallel = Math.max(1, properties.getMaxParallelRawMaterials());
        log.info("[Wiki] Processing {} pending raw materials for kbId={} with parallelism={}",
                pendingList.size(), kbId, parallel);

        Semaphore rawSem = new Semaphore(parallel);
        List<CompletableFuture<Void>> futures = new ArrayList<>(pendingList.size());
        for (WikiRawMaterialEntity raw : pendingList) {
            final Long rawId = raw.getId();
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    rawSem.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    processRawMaterial(rawId);
                } finally {
                    rawSem.release();
                }
            }, WIKI_EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 分块处理大文档（并行执行，Semaphore 控制并发）
     *
     * @return int[3]: [totalPages, failedChunks, totalChunks]
     */
    private int[] processInChunks(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw, String text,
                                      String existingPagesIndex, String documentMap) {
        // Phase 1: 切分文本为 chunks（带偏移，供持久化）
        List<ChunkWithOffset> chunksWithOffset = splitIntoChunksWithOffsets(text);
        List<String> chunks = chunksWithOffset.stream().map(ChunkWithOffset::text).toList();
        int totalChunks = chunks.size();
        log.info("[Wiki] Split into {} chunks for raw={}, kbId={}", totalChunks, raw.getId(), kb.getId());

        // RFC-013：持久化 chunk 到 mate_wiki_chunk（增量对账：hash 不变的保留）
        try {
            List<int[]> offsets = chunksWithOffset.stream()
                    .map(c -> new int[]{c.startOffset(), c.endOffset()}).toList();
            chunkService.persistChunks(kb.getId(), raw.getId(), chunks, offsets);
        } catch (Exception e) {
            log.warn("[Wiki] Chunk persistence failed for raw={}, continuing without: {}", raw.getId(), e.getMessage());
        }

        if (totalChunks == 1) {
            // 单 chunk 不走并行
            try {
                int pages = processChunk(kb, raw, chunks.get(0), existingPagesIndex, documentMap);
                return new int[]{pages, pages == 0 ? 1 : 0, 1};
            } catch (Exception e) {
                log.warn("[Wiki] Single chunk failed: {}", e.getMessage());
                return new int[]{0, 1, 1};
            }
        }

        // Phase 2: 并行处理（Semaphore 限制并发数）
        int parallelChunks = Math.max(1, properties.getMaxParallelChunks());
        Semaphore semaphore = new Semaphore(parallelChunks);
        AtomicInteger totalPages = new AtomicInteger(0);
        AtomicInteger failedChunks = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            final int chunkIndex = i;
            final String chunk = chunks.get(i);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failedChunks.incrementAndGet();
                    return;
                }
                try {
                    // Skip remaining chunks if the user deleted the raw while
                    // earlier chunks were still in flight. Counts as a failed
                    // chunk for terminal-status accounting.
                    if (isAborted(raw.getId(), "chunk " + (chunkIndex + 1) + "/" + totalChunks)) {
                        failedChunks.incrementAndGet();
                        return;
                    }
                    log.info("[Wiki] Processing chunk {}/{}: {} chars", chunkIndex + 1, totalChunks, chunk.length());
                    int pages = processChunk(kb, raw, chunk, existingPagesIndex, documentMap);
                    totalPages.addAndGet(pages);
                } catch (Exception e) {
                    failedChunks.incrementAndGet();
                    if (e.getMessage() != null && e.getMessage().contains("content_filter")) {
                        log.warn("[Wiki] Chunk {}/{} blocked by content filter", chunkIndex + 1, totalChunks);
                    } else {
                        log.warn("[Wiki] Chunk {}/{} failed: {}", chunkIndex + 1, totalChunks, e.getMessage());
                    }
                } finally {
                    semaphore.release();
                }
            }, WIKI_EXECUTOR));
        }

        // 等待全部完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return new int[]{totalPages.get(), failedChunks.get(), totalChunks};
    }

    /**
     * 将文本切分为多个 chunks（智能句子边界，支持中英文）
     */
    private List<String> splitIntoChunks(String text) {
        return splitIntoChunksWithOffsets(text).stream().map(ChunkWithOffset::text).toList();
    }

    /** chunk 文本 + 在原始文本中的偏移 */
    record ChunkWithOffset(String text, int startOffset, int endOffset) {}

    /**
     * 切分并记录每个 chunk 的原始偏移（RFC-013：供 WikiChunkService 持久化）
     */
    private List<ChunkWithOffset> splitIntoChunksWithOffsets(String text) {
        int chunkSize = properties.getMaxChunkSize();
        int overlap = Math.min(500, chunkSize / 10);
        List<ChunkWithOffset> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // 在句子边界切分（支持中英文）
            if (end < text.length()) {
                int breakAt = findSentenceBoundary(text, start, end, chunkSize);
                if (breakAt > start) {
                    end = breakAt;
                }
            }

            chunks.add(new ChunkWithOffset(text.substring(start, end), start, end));

            // 前进（带 overlap 防止边界上下文丢失）
            int nextStart = end - overlap;
            if (nextStart <= start) nextStart = end; // 防止死循环
            start = nextStart;
        }
        return chunks;
    }

    /**
     * 在指定范围内找句子边界（优先级：段落 > 中文句号 > 英文句号 > 换行 > 空格）
     */
    private int findSentenceBoundary(String text, int start, int end, int chunkSize) {
        int halfChunk = start + chunkSize / 2;

        // 优先：段落分隔（双换行）
        int lastPara = text.lastIndexOf("\n\n", end);
        if (lastPara > halfChunk) return lastPara + 2;

        // 中文句号
        int lastChinese = text.lastIndexOf("。", end);
        if (lastChinese > halfChunk) return lastChinese + 1;

        // 英文句号（后面跟空格或换行，排除缩写如 "Dr." "e.g."）
        for (int i = end - 1; i > halfChunk; i--) {
            if (text.charAt(i) == '.' && i + 1 < text.length()
                    && (text.charAt(i + 1) == ' ' || text.charAt(i + 1) == '\n')
                    && i > 0 && Character.isLowerCase(text.charAt(i - 1))) {
                return i + 1;
            }
        }

        // 换行
        int lastNewline = text.lastIndexOf("\n", end);
        if (lastNewline > halfChunk) return lastNewline + 1;

        // 空格（word boundary）
        int lastSpace = text.lastIndexOf(" ", end);
        if (lastSpace > halfChunk) return lastSpace + 1;

        return end; // 无合适边界，硬切
    }

    /**
     * 处理单个文本块
     *
     * @return 创建+更新的页面数
     */
    private int processChunk(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw, String textContent,
                               String existingPagesIndex, String documentMap) {
        // RFC-012 M2：两阶段消化（路由 → 逐页 merge），单次 LLM 调用输出量大幅缩减，避免 nginx 60s 网关超时
        if (properties.isUseTwoPhaseDigest()) {
            return processChunkTwoPhase(kb, raw, textContent, existingPagesIndex, documentMap);
        }

        // 旧路径：单次调用让 LLM 同时处理新建 + 全量 merge（输出爆炸，易触发 504）
        String systemPrompt = PromptLoader.loadPrompt("wiki/digest-system");
        String userTemplate = PromptLoader.loadPrompt("wiki/digest-user");

        String userPrompt = userTemplate
                .replace("{config}", kb.getConfigContent() != null ? kb.getConfigContent() : "")
                .replace("{existing_pages}", existingPagesIndex)
                .replace("{raw_title}", raw.getTitle())
                .replace("{raw_content}", textContent);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ));
        if (isAborted(raw.getId(), "single-chunk legacy")) return 0;
        String llmResponse = callLlmWithResilientRetry(prompt, "chunk of raw=" + raw.getId(),
                kb.getId(), vip.mate.wiki.job.WikiJobStep.CREATE_PAGE);

        return applyLlmResponse(kb.getId(), raw.getId(), llmResponse);
    }

    /**
     * RFC-012 M2 两阶段消化：
     * <p>
     * 阶段 A（route）：一次 LLM 调用决定要 create 哪些新页 + 要 update 哪些已有页（仅 slug 列表）。
     * 输入小、输出短，单次稳定在 30s 内返回。
     * <p>
     * 阶段 B（merge）：对 update 列表里的每个 slug 单独发 LLM 调用，输入只塞这一页的现有正文 + 当前
     * chunk 文本，输出该页 merge 后的完整内容。每次调用单页规模，远不会触发 nginx 60s 超时。
     * <p>
     * 新建页直接落库；merge 页因互不依赖，可在当前 chunk 的 virtual thread 内顺序处理（chunk 之间
     * 已通过 maxParallelChunks Semaphore 拿到了并行度）。
     *
     * @return 创建+更新的页面数
     */
    private int processChunkTwoPhase(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw,
                                       String textContent, String existingPagesIndex, String documentMap) {
        Long kbId = kb.getId();
        Long rawId = raw.getId();
        String configContent = kb.getConfigContent() != null ? kb.getConfigContent() : "";
        String rawTitle = raw.getTitle();

        // RFC-012 M2 v2 UI v2：取共享进度计数器（processRawMaterial 入口已 put）。
        // 多 chunk 并行时所有 chunk 共享同一份 atomic 计数，避免互相覆盖把 UI 拉回 preparing。
        ProgressCounter pc = progressCounters.get(rawId);

        // ─── 阶段 A：路由 ───
        // Rebuild existingPagesIndex fresh at route time so pages created by earlier chunks
        // in this run are visible. This prevents the route from scheduling "create" for a
        // concept that was already created by a previous chunk (which would be caught by
        // savePageContent and converted to update, but wastes a merge LLM call).
        // listSummaries uses a 5-min TTL cache that is evicted on every create/update, so
        // this picks up changes from sequential chunks without an extra DB hit when nothing changed.
        String freshIndex = buildExistingPagesIndex(kbId);

        String routeSystem = PromptLoader.loadPrompt("wiki/route-system");
        String routeUserTemplate = PromptLoader.loadPrompt("wiki/route-user");
        String documentMapSection = buildDocumentMapSection(documentMap);
        String routeUser = routeUserTemplate
                .replace("{config}", configContent)
                .replace("{document_map_section}", documentMapSection)
                .replace("{existing_pages}", freshIndex)
                .replace("{raw_title}", rawTitle)
                .replace("{raw_content}", textContent);

        // RFC-051 PR-6b: optionally inject Spring AI's structured-output hint so the
        // LLM produces strict RouteResult JSON. KB config wins; falls back to global
        // mate.wiki.use-structured-route default when the KB hasn't expressed a preference.
        boolean useStructured = resolveStructuredRouteFlag(kb);
        org.springframework.ai.converter.BeanOutputConverter<vip.mate.wiki.dto.RouteResult> routeConverter =
                useStructured
                        ? new org.springframework.ai.converter.BeanOutputConverter<>(vip.mate.wiki.dto.RouteResult.class)
                        : null;
        if (routeConverter != null) {
            routeUser = routeUser + "\n\n" + routeConverter.getFormat();
        }

        Prompt routePrompt = new Prompt(List.of(
                new SystemMessage(routeSystem),
                new UserMessage(routeUser)
        ));
        if (isAborted(rawId, "route phase")) return 0;
        String routeResponse = callLlmWithResilientRetry(routePrompt, "route chunk of raw=" + rawId,
                kbId, vip.mate.wiki.job.WikiJobStep.ROUTE);

        // RFC-012 follow-up #3：phase B 现在并行执行，计数必须是 atomic
        AtomicInteger created = new AtomicInteger(0);
        AtomicInteger updated = new AtomicInteger(0);

        // ─── 收集 route 输出（仅 metadata，无 content） ───
        List<JsonNode> createMetas = new ArrayList<>();
        List<String> updateSlugs = new ArrayList<>();

        boolean structuredOk = false;
        if (routeConverter != null) {
            try {
                vip.mate.wiki.dto.RouteResult bound = routeConverter.convert(routeResponse);
                if (bound != null) {
                    for (vip.mate.wiki.dto.RoutedPageMeta meta : bound.create()) {
                        if (meta == null || meta.slug() == null || meta.slug().isBlank()
                                || meta.title() == null || meta.title().isBlank()) continue;
                        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
                        node.put("slug", meta.slug());
                        node.put("title", meta.title());
                        if (meta.summary() != null) node.put("summary", meta.summary());
                        if (meta.purposeHint() != null) node.put("purposeHint", meta.purposeHint());
                        createMetas.add(node);
                    }
                    for (String slug : bound.update()) {
                        if (slug != null && !slug.isBlank()) updateSlugs.add(slug);
                    }
                    structuredOk = true;
                    log.debug("[Wiki] Route phase: structured parse ok kbId={} rawId={} create={} update={}",
                            kbId, rawId, createMetas.size(), updateSlugs.size());
                }
            } catch (Exception e) {
                log.warn("[Wiki] Route phase: structured parse failed for rawId={}, falling back to lenient JSON: {}",
                        rawId, e.getMessage());
            }
        }

        if (!structuredOk) {
            JsonNode routeJson = parseJsonResponse(routeResponse);
            if (routeJson == null) {
                // Some models (GLM in "thinking" mode, Kimi with a "Let me
                // analyze..." preamble) ignore the strict-JSON instruction
                // on first try and emit prose instead. Their response often
                // contains no { or } at all, so even substring extraction
                // can't recover. Re-issue once with an explicit corrective
                // suffix — empirically this is enough to cut the preamble
                // and get clean JSON. One retry only; we don't want to
                // chase down the rabbit hole if the model is fundamentally
                // misaligned with the schema.
                log.info("[Wiki] Route phase: first parse failed, retrying with strict-JSON correction for rawId={}", rawId);
                String correctedUser = routeUser
                        + "\n\n⚠️ 你上一次的回答无法解析为 JSON。"
                        + "这次请**只输出**符合 schema 的 JSON 对象（必须以 `{` 开头、以 `}` 结尾），"
                        + "不要包含任何思考过程、解释说明、Markdown 代码块标记或前后文字。";
                Prompt retryPrompt = new Prompt(List.of(
                        new SystemMessage(routeSystem),
                        new UserMessage(correctedUser)
                ));
                String retryResponse = callLlmWithResilientRetry(retryPrompt,
                        "route chunk RETRY of raw=" + rawId,
                        kbId, vip.mate.wiki.job.WikiJobStep.ROUTE);
                routeJson = parseJsonResponse(retryResponse);
                if (routeJson == null) {
                    log.warn("[Wiki] Route phase: failed to parse JSON for kbId={}, rawId={}, responseLen={}, first200={}",
                            kbId, rawId, retryResponse != null ? retryResponse.length() : 0,
                            retryResponse != null ? retryResponse.substring(0, Math.min(200, retryResponse.length())) : "null");
                    return 0;
                }
                log.info("[Wiki] Route phase: JSON correction retry succeeded for rawId={}", rawId);
            }
            JsonNode createNode = routeJson.path("create");
            if (createNode.isArray()) {
                for (JsonNode metaNode : createNode) {
                    String slug = metaNode.path("slug").asText("");
                    String title = metaNode.path("title").asText("");
                    if (slug.isBlank() || title.isBlank()) continue;
                    createMetas.add(metaNode);
                }
            }
            JsonNode updateNode = routeJson.path("update");
            if (updateNode.isArray()) {
                for (JsonNode slugNode : updateNode) {
                    String slug = slugNode.asText("");
                    if (!slug.isBlank()) updateSlugs.add(slug);
                }
            }
        }
        int totalPlanned = createMetas.size() + updateSlugs.size();

        // Chunk fallback: if route returned nothing for a non-trivial chunk, inject an overview page
        // so no content is silently dropped (mirrors llm_wiki source-summary guarantee).
        if (totalPlanned == 0 && textContent.length() >= properties.getChunkFallbackMinChars()) {
            String overviewSlug = WikiPageService.toSlug(rawTitle) + "-overview";
            com.fasterxml.jackson.databind.node.ObjectNode fallbackMeta =
                    objectMapper.createObjectNode();
            fallbackMeta.put("slug", overviewSlug);
            fallbackMeta.put("title", rawTitle + " 概述");
            fallbackMeta.put("summary", "来自「" + rawTitle + "」的综合概述，涵盖本章节的核心内容。");
            createMetas.add(fallbackMeta);
            totalPlanned = 1;
            log.info("[Wiki] Chunk fallback: route returned empty for rawId={} chunkLen={}, injecting overview page '{}'",
                    rawId, textContent.length(), overviewSlug);
        }

        log.info("[Wiki] Route phase: kbId={}, rawId={}, planned create={}, planned update={}",
                kbId, rawId, createMetas.size(), updateSlugs.size());

        // RFC-012 M2 v2 UI v2：把本 chunk 的计划数累加到共享 total；切换到 phase-b（仅首次切换需 log）
        if (pc != null) {
            pc.total.addAndGet(totalPlanned);
            if (pc.phaseBStarted.compareAndSet(false, true)) {
                log.info("[Wiki] Progress: switching to phase-b for raw={}", rawId);
                // RFC-012 M3：route 完成、phase-b 启动 → 通知前端确定进度（可显示 0/N）
                progressBus.broadcast(kbId, WikiProgressBus.EVENT_ROUTE_DONE,
                        java.util.Map.of(
                                "rawId", rawId,
                                "phase", "phase-b",
                                "done", pc.done.get(),
                                "total", pc.total.get()));
            }
            rawService.updateProgress(rawId, "phase-b", pc.done.get(), pc.total.get());
        }

        // RFC-012 follow-up #3：阶段 B 页级并发。每个 page 是独立的 LLM 调用，相互无依赖，
        // 串行跑会让一个卡超时的 page 阻塞整个 chunk。受 maxParallelPhaseBPages Semaphore 约束，
        // 复用虚拟线程池 WIKI_EXECUTOR。
        int parallelPages = Math.max(1, properties.getMaxParallelPhaseBPages());
        Semaphore pageSem = new Semaphore(parallelPages);

        // ─── Phase B-1: BatchCreate (RFC-047 P1) ───
        // One LLM call for all N creates instead of N individual calls.
        // batchCreatePages handles sub-batching, liveIndex updates, and progress counting.
        batchCreatePages(kb, raw, textContent, existingPagesIndex, createMetas, created, pc, documentMap);
        List<CompletableFuture<Void>> createFutures = new ArrayList<>(0); // kept for allOf join below

        // ─── 阶段 B-2：并行 merge ───
        // Dedup: skip slugs already merged in this run to prevent N-version churn on
        // high-frequency reference pages (e.g. a herb mentioned in every chapter gets v1,
        // not v19). The first chunk that merges a slug wins; later chunks skip it and
        // adjust the shared total counter so progress stays consistent.
        List<String> effectiveUpdateSlugs = new ArrayList<>();
        for (String slug : updateSlugs) {
            if (pc != null && pc.mergedSlugs.putIfAbsent(slug, Boolean.TRUE) != null) {
                // Already merged in a previous chunk — remove from total so progress bar stays accurate
                pc.total.decrementAndGet();
                log.debug("[Wiki] Phase B merge slug='{}' deduped (already merged this run), skipping", slug);
            } else {
                effectiveUpdateSlugs.add(slug);
            }
        }

        List<CompletableFuture<Void>> mergeFutures = new ArrayList<>(effectiveUpdateSlugs.size());
        for (String slug : effectiveUpdateSlugs) {
            final String mergeSlug = slug;
            mergeFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    pageSem.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                boolean ok = false;
                try {
                    try {
                        if (mergeOnePage(kb, raw, textContent, mergeSlug)) {
                            updated.incrementAndGet();
                        }
                        ok = true;
                    } catch (RuntimeException e) {
                        log.warn("[Wiki] Phase B merge page slug='{}' failed: {}", mergeSlug, e.getMessage());
                    }
                    if (pc != null) {
                        int d = pc.done.incrementAndGet();
                        if (!ok) pc.failed.incrementAndGet();
                        rawService.updateProgress(rawId, "phase-b", d, pc.total.get());
                        progressBus.broadcast(kbId, WikiProgressBus.EVENT_CHUNK_DONE,
                                java.util.Map.of(
                                        "rawId", rawId,
                                        "kind", "merge",
                                        "ok", ok,
                                        "done", d,
                                        "total", pc.total.get()));
                    }
                } finally {
                    pageSem.release();
                }
            }, WIKI_EXECUTOR));
        }

        // 等待本 chunk 的 create + merge 全部完成
        List<CompletableFuture<Void>> allFutures = new ArrayList<>(createFutures.size() + mergeFutures.size());
        allFutures.addAll(createFutures);
        allFutures.addAll(mergeFutures);
        CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).join();
        // 单 chunk 完成时不写"done"——多 chunk 还在跑；最终"done"由 processRawMaterial 的 finally 写入

        log.info("[wiki-telemetry-chunk] kbId={} rawId={} chunkLen={} indexLen={} creates={} updates={}",
                kbId, rawId, textContent.length(), existingPagesIndex.length(), created.get(), updated.get());
        return created.get() + updated.get();
    }

    /**
     * RFC-047 P1: BatchCreate — single LLM call generating all new pages for a chunk.
     * <p>
     * Splits {@code createMetas} into sub-batches of {@code batchCreatePageSize}; after each
     * sub-batch the saved pages are appended to {@code liveIndex} so subsequent sub-batches
     * can link to them. Returns the count of actually-created (not updated) pages.
     */
    private int batchCreatePages(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw,
                                   String chunkText, String existingPagesIndex,
                                   List<JsonNode> createMetas, AtomicInteger created,
                                   ProgressCounter pc, String documentMap) {
        if (createMetas.isEmpty()) return 0;
        Long kbId = kb.getId();
        Long rawId = raw.getId();
        String configContent = kb.getConfigContent() != null ? kb.getConfigContent() : "";
        int batchSize = Math.max(1, properties.getBatchCreatePageSize());

        WikiBatchCreateParser batchParser = new WikiBatchCreateParser();
        StringBuilder liveIndex = new StringBuilder(existingPagesIndex);
        int totalCreated = 0;

        for (int bStart = 0; bStart < createMetas.size(); bStart += batchSize) {
            int bEnd = Math.min(bStart + batchSize, createMetas.size());
            List<JsonNode> subBatch = createMetas.subList(bStart, bEnd);

            // Build pages_to_create JSON array for this sub-batch
            StringBuilder metasJson = new StringBuilder("[");
            for (int i = 0; i < subBatch.size(); i++) {
                if (i > 0) metasJson.append(",");
                metasJson.append(subBatch.get(i).toString());
            }
            metasJson.append("]");

            String batchSystem = PromptLoader.loadPrompt("wiki/batch-create-system");
            String batchUserTemplate = PromptLoader.loadPrompt("wiki/batch-create-user");
            String docMapSection = buildDocumentMapSection(documentMap);
            String batchUser = batchUserTemplate
                    .replace("{config}", configContent)
                    .replace("{document_map_section}", docMapSection)
                    .replace("{existing_pages}", liveIndex.toString())
                    .replace("{pages_to_create}", metasJson.toString())
                    .replace("{raw_title}", raw.getTitle())
                    .replace("{raw_content}", chunkText);
            Prompt batchPrompt = new Prompt(List.of(
                    new SystemMessage(batchSystem),
                    new UserMessage(batchUser)
            ));

            if (isAborted(rawId, "batch-create sub-batch " + (bStart / batchSize + 1))) {
                // Abandon remaining sub-batches; return however many pages we already created.
                return totalCreated;
            }
            String batchResponse = callLlmWithResilientRetry(batchPrompt,
                    "batch-create " + subBatch.size() + " pages of raw=" + rawId
                    + " subBatch=" + (bStart / batchSize + 1),
                    kbId, vip.mate.wiki.job.WikiJobStep.CREATE_PAGE);

            List<WikiBatchCreateParser.ParsedPage> parsedPages = batchParser.parse(batchResponse);

            // Fallback: if LLM ignored FILE block format (common for single-page sub-batches),
            // try treating the entire response as a bare JSON page.
            if (parsedPages.isEmpty() && subBatch.size() == 1 && batchResponse != null && !batchResponse.isBlank()) {
                String fallbackSlug = subBatch.get(0).path("slug").asText("");
                parsedPages = List.of(new WikiBatchCreateParser.ParsedPage(fallbackSlug, batchResponse.strip()));
                log.info("[Wiki] BatchCreate sub-batch {}: no FILE blocks found, trying bare-JSON fallback for slug='{}'",
                        bStart / batchSize + 1, fallbackSlug);
            }

            log.info("[Wiki] BatchCreate sub-batch {}: planned={} parsed={}",
                    bStart / batchSize + 1, subBatch.size(), parsedPages.size());

            for (WikiBatchCreateParser.ParsedPage pp : parsedPages) {
                JsonNode pageJson = parseJsonResponse(pp.rawJson());
                if (pageJson == null) {
                    // Truncated or malformed JSON inside the FILE block — by far the most
                    // common batch-create failure mode (provider hits max-tokens mid-object).
                    // Recover by re-issuing this slug as a single-page LLM call: tiny payload,
                    // tiny truncation risk. Up to 2 attempts.
                    log.info("[Wiki] BatchCreate: unparseable JSON for slug='{}', retrying individually",
                            pp.slug());
                    final String headerSlug = pp.slug();
                    JsonNode retryMeta = subBatch.stream()
                            .filter(m -> headerSlug.equals(m.path("slug").asText("")))
                            .findFirst().orElse(null);
                    if (retryMeta != null) {
                        int delta = tryCreateOneWithRetry(kb, raw, chunkText, liveIndex, retryMeta, 2,
                                created, pc, rawId, kbId, headerSlug);
                        if (delta > 0) totalCreated++;
                    } else if (pc != null) {
                        // Sub-batch's parsed FILE header references a slug that wasn't in the
                        // planned metas — nothing useful to retry. Tick progress and move on.
                        int d = pc.done.incrementAndGet();
                        pc.failed.incrementAndGet();
                        rawService.updateProgress(rawId, "phase-b", d, pc.total.get());
                        progressBus.broadcast(kbId, WikiProgressBus.EVENT_CHUNK_DONE,
                                java.util.Map.of("rawId", rawId, "kind", "create",
                                        "ok", false, "done", d, "total", pc.total.get()));
                    }
                    continue;
                }
                // Use slug from JSON body; fall back to FILE header slug
                String slug = pageJson.path("slug").asText(pp.slug());
                if (slug.isBlank()) slug = pp.slug();
                String title = pageJson.path("title").asText("");
                String content = pageJson.path("content").asText("");
                String pageSummary = pageJson.path("summary").asText("");
                String pageType = pageJson.path("page_type").asText("");
                if (content.isBlank()) {
                    log.info("[Wiki] BatchCreate: blank content for slug='{}', retrying individually", slug);
                    final String blankSlug = slug;
                    final String headerSlug = pp.slug();
                    // The LLM occasionally renames a slug between the FILE header and the
                    // JSON body, so match either when finding the meta to retry against.
                    JsonNode retryMeta = subBatch.stream()
                            .filter(m -> blankSlug.equals(m.path("slug").asText(""))
                                    || headerSlug.equals(m.path("slug").asText("")))
                            .findFirst().orElse(null);
                    if (retryMeta != null) {
                        int delta = tryCreateOneWithRetry(kb, raw, chunkText, liveIndex, retryMeta, 2,
                                created, pc, rawId, kbId, slug);
                        if (delta > 0) totalCreated++;
                    } else if (pc != null) {
                        int d = pc.done.incrementAndGet();
                        pc.failed.incrementAndGet();
                        rawService.updateProgress(rawId, "phase-b", d, pc.total.get());
                        progressBus.broadcast(kbId, WikiProgressBus.EVENT_CHUNK_DONE,
                                java.util.Map.of("rawId", rawId, "kind", "create-retry",
                                        "ok", false, "done", d, "total", pc.total.get()));
                    }
                    continue;
                }

                boolean wasCreated = false;
                boolean ok = false;
                try {
                    wasCreated = savePageContent(kb, raw, slug, title, content, pageSummary, pageType);
                    if (wasCreated) {
                        created.incrementAndGet();
                        totalCreated++;
                        // Append to liveIndex so the NEXT sub-batch can link to this freshly
                        // created page. Mirrors the slug-first row format produced by
                        // {@link #buildExistingPagesIndex}: `[[slug]] — title — summary`.
                        // Keeps the LLM's view of the index uniformly slug-first across
                        // pre-existing rows and just-created rows.
                        String briefSummary = pageSummary.length() > 100
                                ? pageSummary.substring(0, 100) : pageSummary;
                        liveIndex.append("\n- [[").append(slug).append("]]");
                        if (title != null && !title.isBlank()) {
                            liveIndex.append(" — ").append(title);
                        }
                        if (briefSummary != null && !briefSummary.isBlank()) {
                            liveIndex.append(" — ").append(briefSummary);
                        }
                    }
                    ok = true;
                } catch (RuntimeException e) {
                    log.warn("[Wiki] BatchCreate: savePageContent failed for slug='{}': {}", slug, e.getMessage());
                }

                if (pc != null) {
                    int d = pc.done.incrementAndGet();
                    if (!ok) pc.failed.incrementAndGet();
                    rawService.updateProgress(rawId, "phase-b", d, pc.total.get());
                    progressBus.broadcast(kbId, WikiProgressBus.EVENT_CHUNK_DONE,
                            java.util.Map.of(
                                    "rawId", rawId,
                                    "kind", "create",
                                    "ok", ok,
                                    "done", d,
                                    "total", pc.total.get()));
                }
            }

            // Retry any pages that LLM omitted from the batch response
            int subBatchNum = bStart / batchSize + 1;
            java.util.Set<String> returnedSlugs = new java.util.HashSet<>();
            for (WikiBatchCreateParser.ParsedPage pp : parsedPages) {
                returnedSlugs.add(pp.slug());
            }
            for (JsonNode missingMeta : subBatch) {
                String missingSlug = missingMeta.path("slug").asText("");
                if (missingSlug.isBlank() || returnedSlugs.contains(missingSlug)) continue;
                log.info("[Wiki] BatchCreate sub-batch {}: slug='{}' missing, retrying individually",
                        subBatchNum, missingSlug);
                int delta = tryCreateOneWithRetry(kb, raw, chunkText, liveIndex, missingMeta, 2,
                        created, pc, rawId, kbId, missingSlug);
                if (delta > 0) totalCreated++;
            }
        }
        return totalCreated;
    }

    /**
     * RFC-047 P1 retry: call the single-page create prompt for one missing slug.
     * Used when a BatchCreate sub-batch omits a planned page.
     *
     * @return raw LLM response string, or null on failure
     */
    private String retrySingleCreate(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw,
                                      String chunkText, String existingPagesIndex,
                                      JsonNode pageMeta) {
        String slug = pageMeta.path("slug").asText("");
        String title = pageMeta.path("title").asText("");
        String summary = pageMeta.path("summary").asText("");
        String configContent = kb.getConfigContent() != null ? kb.getConfigContent() : "";
        String createSystem = PromptLoader.loadPrompt("wiki/create-page-system");
        String createUserTemplate = PromptLoader.loadPrompt("wiki/create-page-user");
        String createUser = createUserTemplate
                .replace("{config}", configContent)
                .replace("{existing_pages}", existingPagesIndex)
                .replace("{page_slug}", slug)
                .replace("{page_title}", title)
                .replace("{page_summary}", summary)
                .replace("{raw_title}", raw.getTitle())
                .replace("{raw_content}", chunkText);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(createSystem),
                new UserMessage(createUser)
        ));
        if (isAborted(raw.getId(), "retry-create slug=" + slug)) return null;
        return callLlmWithResilientRetry(prompt, "retry-create slug=" + slug + " of raw=" + raw.getId(),
                kb.getId(), vip.mate.wiki.job.WikiJobStep.CREATE_PAGE);
    }

    /**
     * Recover one slug that BatchCreate failed to deliver — calls
     * {@link #retrySingleCreate} up to {@code maxAttempts} times, retrying on
     * any of: null response, unparseable JSON, blank content, thrown exception.
     * Each attempt is an independent single-page LLM call: small payload, low
     * truncation risk — two attempts is enough to recover from transient
     * provider hiccups without ballooning latency.
     *
     * <p>This consolidates what used to be three near-identical inline retry
     * blocks (omitted slug / blank content / unparseable JSON) into one path
     * with consistent attempt count, log lines, and progress accounting.
     *
     * <p>Side effects on success: {@code created} is incremented (only when a
     * brand-new page is persisted, not on dedupe), {@code liveIndex} grows so
     * subsequent batched pages can wikilink to this one, and {@code pc} ticks
     * one {@code done} regardless of outcome with {@code failed} on exhaustion.
     *
     * @param slugForLog logical slug used in log messages — the actual write
     *                   slug comes from the LLM response or retryMeta.slug
     * @return 1 if a new page was persisted, 0 if a parseable response landed
     *         but the slug already existed (dedup), -1 if all attempts failed
     */
    private int tryCreateOneWithRetry(WikiKnowledgeBaseEntity kb,
                                       WikiRawMaterialEntity raw,
                                       String chunkText,
                                       StringBuilder liveIndex,
                                       JsonNode retryMeta,
                                       int maxAttempts,
                                       AtomicInteger created,
                                       ProgressCounter pc,
                                       Long rawId,
                                       Long kbId,
                                       String slugForLog) {
        String metaSlug = retryMeta.path("slug").asText("");
        String fallbackTitle = retryMeta.path("title").asText("");
        String fallbackSummary = retryMeta.path("summary").asText("");
        int delta = -1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String retryResult = retrySingleCreate(kb, raw, chunkText, liveIndex.toString(), retryMeta);
                if (retryResult == null) {
                    log.warn("[Wiki] retry-create slug='{}' attempt {}/{}: null response",
                            slugForLog, attempt, maxAttempts);
                    continue;
                }
                JsonNode retryJson = parseJsonResponse(retryResult);
                if (retryJson == null) {
                    log.warn("[Wiki] retry-create slug='{}' attempt {}/{}: unparseable JSON",
                            slugForLog, attempt, maxAttempts);
                    continue;
                }
                String resolvedSlug = retryJson.path("slug").asText(metaSlug);
                if (resolvedSlug.isBlank()) resolvedSlug = metaSlug;
                String content = retryJson.path("content").asText("");
                if (content.isBlank()) {
                    log.warn("[Wiki] retry-create slug='{}' attempt {}/{}: blank content",
                            slugForLog, attempt, maxAttempts);
                    continue;
                }
                String title = retryJson.path("title").asText(fallbackTitle);
                String summary = retryJson.path("summary").asText(fallbackSummary);
                boolean wasCreated = savePageContent(kb, raw, resolvedSlug, title, content, summary);
                if (wasCreated) {
                    created.incrementAndGet();
                    String brief = summary.length() > 100 ? summary.substring(0, 100) : summary;
                    liveIndex.append("\n- ").append(resolvedSlug).append(": ").append(brief);
                    delta = 1;
                } else {
                    delta = 0;
                }
                log.info("[Wiki] retry-create slug='{}' succeeded on attempt {}/{} (newPage={})",
                        slugForLog, attempt, maxAttempts, wasCreated);
                break;
            } catch (Exception e) {
                log.warn("[Wiki] retry-create slug='{}' attempt {}/{} threw: {}",
                        slugForLog, attempt, maxAttempts, e.getMessage());
            }
        }
        if (delta < 0) {
            log.warn("[Wiki] retry-create slug='{}' exhausted {} attempts — giving up",
                    slugForLog, maxAttempts);
        }
        if (pc != null) {
            int d = pc.done.incrementAndGet();
            if (delta < 0) pc.failed.incrementAndGet();
            rawService.updateProgress(rawId, "phase-b", d, pc.total.get());
            progressBus.broadcast(kbId, WikiProgressBus.EVENT_CHUNK_DONE,
                    java.util.Map.of("rawId", rawId, "kind", "create-retry",
                            "ok", delta >= 0, "done", d, "total", pc.total.get()));
        }
        return delta;
    }

    /**
     * RFC-047 P1: Shared DB save logic for a new page, extracted for use by both
     * {@link #createOnePage} and {@link #batchCreatePages}.
     * Handles canonical-slug matching, in-flight slug-claim arbitration, and DuplicateKey fallback.
     *
     * @return true if a new row was inserted; false if an existing page was updated instead
     */
    private boolean savePageContent(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw,
                                     String slug, String title, String content, String pageSummary) {
        return savePageContent(kb, raw, slug, title, content, pageSummary, null);
    }

    private boolean savePageContent(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw,
                                     String slug, String title, String content, String pageSummary,
                                     String pageType) {
        Long kbId = kb.getId();
        Long rawId = raw.getId();

        // Refuse to materialize a page, or merge into an existing one, for a
        // raw the user just deleted. This prevents pages whose source_raw_ids
        // point at a tombstoned row.
        if (isAborted(rawId, "savePageContent slug=" + slug)) return false;

        // Fallback 0: cross-spelling canonical match (DB has same concept under different slug)
        WikiPageEntity existingByCanonical = pageService.findByCanonicalSlug(kbId, slug);
        if (existingByCanonical != null && !existingByCanonical.getSlug().equals(slug)) {
            String actualSlug = existingByCanonical.getSlug();
            pageService.updatePageByAi(kbId, actualSlug, content, pageSummary, rawId);
            pageService.mergeSourceLineage(existingByCanonical.getId(), rawId, raw.getTitle());
            log.info("[Wiki] Phase B create slug='{}' canonical-matches existing '{}', updated",
                    slug, actualSlug);
            return false;
        }

        // Fallback 0.5: in-flight slug-claim arbitration across parallel chunks
        ProgressCounter pcLocal = progressCounters.get(rawId);
        String canonical = WikiPageService.canonicalSlug(slug);
        if (pcLocal != null && !canonical.isEmpty()) {
            final String routedSlug = slug;
            String winnerSlug = pcLocal.slugClaims.computeIfAbsent(canonical, k -> routedSlug);
            if (!winnerSlug.equals(slug)) {
                WikiPageEntity winner = pageService.getBySlug(kbId, winnerSlug);
                if (winner != null) {
                    pageService.updatePageByAi(kbId, winnerSlug, content, pageSummary, rawId);
                    pageService.mergeSourceLineage(winner.getId(), rawId, raw.getTitle());
                    log.info("[Wiki] Phase B create slug='{}' lost slug-claim race to '{}', updated",
                            slug, winnerSlug);
                    return false;
                }
                log.info("[Wiki] Phase B create slug='{}' redirects to in-flight winner '{}'",
                        slug, winnerSlug);
                slug = winnerSlug;
            }
        }

        // Fallback 1: slug already in DB (route misclassified or prior run)
        WikiPageEntity existing = pageService.getBySlug(kbId, slug);
        if (existing != null) {
            pageService.updatePageByAi(kbId, slug, content, pageSummary, rawId);
            pageService.mergeSourceLineage(existing.getId(), rawId, raw.getTitle());
            log.info("[Wiki] Phase B create page slug='{}' done (updated existing)", slug);
            return false;
        }

        String sourceRawIds = "[" + rawId + "]";
        try {
            WikiPageEntity created = pageService.createPage(kbId, slug, title, content, pageSummary, sourceRawIds, pageType);
            pageService.mergeSourceLineage(created.getId(), rawId, raw.getTitle());
            log.info("[Wiki] Phase B create page slug='{}' done (created)", slug);
            citationService.buildCitationsAsync(created.getId(), kbId);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Fallback 2: concurrent INSERT race — degrade to update
            pageService.updatePageByAi(kbId, slug, content, pageSummary, rawId);
            log.info("[Wiki] Phase B create page slug='{}' lost INSERT race -> updated existing", slug);
            return false;
        }
    }

    /**
     * RFC-012 M2 v2 — 阶段 B 单页 merge：把 chunk 文本合并进一个已有页面。
     * <p>
     * 输入仅几 KB（该页现有 content + chunk 主题片段），输出仅一页 markdown。
     *
     * @return true 表示成功 update 一页
     */
    private boolean mergeOnePage(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw,
                                   String chunkText, String slug) {
        Long kbId = kb.getId();
        Long rawId = raw.getId();
        WikiPageEntity existing = pageService.getBySlug(kbId, slug);
        if (existing == null) {
            // 兜底：跨拼写 canonical 匹配——LLM 给的 slug 在 DB 里找不到，
            // 但 canonical 形式（去连字符）对得上某个已有 page（典型场景：
            // route 输出 `zhong-yao-qi-qing-pei-wu`，DB 存 `zhongyao-qiqing-peiwu`）
            existing = pageService.findByCanonicalSlug(kbId, slug);
            if (existing != null && !existing.getSlug().equals(slug)) {
                log.info("[Wiki] Phase B merge slug='{}' canonical-matches existing '{}', using canonical slug for LLM call",
                        slug, existing.getSlug());
                slug = existing.getSlug();
            } else {
                log.warn("[Wiki] Phase B merge page slug='{}' planned for update but not found in DB (even by canonical), skipping", slug);
                return false;
            }
        }

        String configContent = kb.getConfigContent() != null ? kb.getConfigContent() : "";
        String mergeSystem = PromptLoader.loadPrompt("wiki/merge-page-system");
        // Trim existing content to prevent context overflow on small models (qwen-turbo: 4096 tokens).
        // Merging a 3000-char page + 30K chunk blows past the limit → truncated JSON → parse failure.
        // 1800 chars ≈ ~600 tokens, leaving ample room for the chunk and response.
        final int MAX_EXISTING_CHARS = 1800;
        String rawExisting = existing.getContent() != null ? existing.getContent() : "";
        String trimmedExisting = rawExisting.length() > MAX_EXISTING_CHARS
                ? rawExisting.substring(0, MAX_EXISTING_CHARS) + "\n...(内容已截断，请基于以上内容合并新信息)"
                : rawExisting;

        String mergeUserTemplate = PromptLoader.loadPrompt("wiki/merge-page-user");
        String mergeUser = mergeUserTemplate
                .replace("{config}", configContent)
                .replace("{page_slug}", existing.getSlug() != null ? existing.getSlug() : slug)
                .replace("{page_title}", existing.getTitle() != null ? existing.getTitle() : "")
                .replace("{page_last_updated_by}", existing.getLastUpdatedBy() != null ? existing.getLastUpdatedBy() : "ai")
                .replace("{page_content}", trimmedExisting)
                .replace("{raw_title}", raw.getTitle())
                .replace("{raw_content}", chunkText);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(mergeSystem),
                new UserMessage(mergeUser)
        ));
        if (isAborted(rawId, "merge slug=" + slug)) return false;
        String response = callLlmWithResilientRetry(prompt,
                "merge page slug=" + slug + " of raw=" + rawId,
                kbId, vip.mate.wiki.job.WikiJobStep.MERGE_PAGE);
        JsonNode mergeJson = parseJsonResponse(response);
        if (mergeJson == null) {
            log.warn("[Wiki] Phase B merge page slug='{}' returned unparseable JSON, skipping", slug);
            return false;
        }
        String content = mergeJson.path("content").asText("");
        String summary = mergeJson.path("summary").asText("");
        if (content.isBlank()) {
            log.warn("[Wiki] Phase B merge page slug='{}' returned blank content, skipping", slug);
            return false;
        }
        WikiPageEntity updated = pageService.updatePageByAi(kbId, slug, content, summary, rawId);
        log.info("[Wiki] Phase B merge page slug='{}' done", slug);
        // RFC-047 P2: merge paired source lineage on update
        if (updated != null) {
            pageService.mergeSourceLineage(updated.getId(), rawId, raw.getTitle());
        }
        // RFC-029: async citation rebuild
        if (updated != null) {
            citationService.buildCitationsAsync(updated.getId(), kbId);
        }
        return true;
    }

    /**
     * 解析 LLM 响应并创建/更新 Wiki 页面
     *
     * @return 创建+更新的页面总数
     */
    private int applyLlmResponse(Long kbId, Long rawId, String llmResponse) {
        JsonNode root = parseJsonResponse(llmResponse);
        if (root == null) {
            log.warn("[Wiki] Failed to parse LLM response for kbId={}, rawId={}, responseLen={}, first200={}",
                    kbId, rawId, llmResponse != null ? llmResponse.length() : 0,
                    llmResponse != null ? llmResponse.substring(0, Math.min(200, llmResponse.length())) : "null");
            return 0;
        }

        // 结构校验：必须有 pages 数组
        if (!root.has("pages") || !root.get("pages").isArray()) {
            log.warn("[Wiki] LLM response missing 'pages' array for kbId={}, rawId={}", kbId, rawId);
            return 0;
        }

        String sourceRawIds = "[" + rawId + "]";
        int created = 0;
        int updated = 0;

        // 新页面
        JsonNode pagesNode = root.path("pages");
        if (pagesNode.isArray()) {
            for (JsonNode pageNode : pagesNode) {
                String slug = pageNode.path("slug").asText("");
                String title = pageNode.path("title").asText("");
                String content = pageNode.path("content").asText("");
                String summary = pageNode.path("summary").asText("");

                if (slug.isBlank() || title.isBlank()) continue;

                // 检查是否已存在（LLM 可能将已有页面误判为新页面）
                WikiPageEntity existing = pageService.getBySlug(kbId, slug);
                if (existing != null) {
                    pageService.updatePageByAi(kbId, slug, content, summary, rawId);
                    updated++;
                } else {
                    pageService.createPage(kbId, slug, title, content, summary, sourceRawIds);
                    created++;
                }
            }
        }

        // 更新的页面
        JsonNode updatedPagesNode = root.path("updated_pages");
        if (updatedPagesNode.isArray()) {
            for (JsonNode pageNode : updatedPagesNode) {
                String slug = pageNode.path("slug").asText("");
                String content = pageNode.path("content").asText("");
                String summary = pageNode.path("summary").asText("");

                if (slug.isBlank()) continue;

                WikiPageEntity existing = pageService.getBySlug(kbId, slug);
                if (existing != null) {
                    // 保护手动编辑的页面：仍然更新，但 LLM 已在 prompt 中被告知要保留手动内容
                    pageService.updatePageByAi(kbId, slug, content, summary, rawId);
                    updated++;
                }
            }
        }

        log.info("[Wiki] Applied LLM response: kbId={}, rawId={}, created={}, updated={}",
                kbId, rawId, created, updated);
        return created + updated;
    }

    /**
     * RFC-047 follow-up: Document-level analysis pass.
     * Single LLM call on a sample of the full document to produce a concept map
     * (topics + key_concepts + structure_notes). The result is injected into
     * every chunk's route prompt so the router has global document awareness,
     * reducing concept omissions caused by chunk-local context blindness.
     *
     * @return pretty-printed JSON string of the concept map, or "" on failure
     */
    private String analyzeDocument(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw, String textContent) {
        int sampleChars = Math.max(1000, properties.getDocumentAnalysisSampleChars());
        String sample = textContent.length() > sampleChars
                ? textContent.substring(0, sampleChars) + "\n...[文档较长，以上为节选]"
                : textContent;
        // Inject the existing-pages index so the LLM can pick a real `related_pages`
        // whitelist of slugs that already exist in the KB. Generation prompts
        // downstream see the validated whitelist via `documentMap`, which lets
        // them link confidently instead of inventing targets.
        String existingPagesIndex = buildExistingPagesIndex(kb.getId());
        String system = PromptLoader.loadPrompt("wiki/analyze-system");
        String userTemplate = PromptLoader.loadPrompt("wiki/analyze-user");
        String user = userTemplate
                .replace("{raw_title}", raw.getTitle())
                .replace("{existing_pages}", existingPagesIndex)
                .replace("{text_sample}", sample);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(system),
                new UserMessage(user)
        ));
        try {
            if (isAborted(raw.getId(), "doc analysis")) return "";
            String response = callLlmWithResilientRetry(prompt, "analyze doc raw=" + raw.getId(),
                    kb.getId(), vip.mate.wiki.job.WikiJobStep.ROUTE);
            JsonNode json = parseJsonResponse(response);
            if (json != null) {
                // Validate related_pages against the active KB slug set BEFORE
                // letting it flow downstream. An LLM that ignores the "must
                // come from the index" rule and invents slugs would otherwise
                // pollute the generation prompt, undoing the work of Phase 3.
                JsonNode validated = validateRelatedPages(kb.getId(), json);
                log.info("[Wiki] Document analysis done for raw={}: topics={}, concepts={}, related_pages={}",
                        raw.getId(),
                        validated.path("topics").size(),
                        validated.path("key_concepts").size(),
                        validated.path("related_pages").size());
                return validated.toPrettyString();
            }
        } catch (Exception e) {
            log.warn("[Wiki] Document analysis failed for raw={}, continuing without: {}", raw.getId(), e.getMessage());
        }
        return "";
    }

    /**
     * Render the analyze-stage output for inclusion in route / batch-create
     * user prompts. The full JSON goes into a fenced code block, and any
     * {@code related_pages} array is also surfaced as a plain "recommended
     * link targets" section right above it so the LLM doesn't have to
     * parse JSON to find the whitelist.
     */
    private String buildDocumentMapSection(String documentMap) {
        if (documentMap == null || documentMap.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        try {
            JsonNode node = objectMapper.readTree(documentMap);
            JsonNode related = node.path("related_pages");
            if (related.isArray() && related.size() > 0) {
                sb.append("## 推荐链接到的页面（由分析阶段产出，已通过 slug 白名单校验，可优先使用）\n\n");
                for (JsonNode el : related) {
                    String slug = el.asText("").trim();
                    if (slug.isEmpty()) continue;
                    sb.append("- [[").append(slug).append("]]\n");
                }
                sb.append("\n");
            }
        } catch (Exception ignored) {
            // documentMap might not be parseable JSON (older runs, partial
            // output) — fall through and emit the raw block below.
        }
        sb.append("## 文档全局概念地图（预分析结果，供页面内容生成参考）\n\n```json\n")
                .append(documentMap).append("\n```\n");
        return sb.toString();
    }

    /**
     * Drop any {@code related_pages} entry not in the KB's active slug set.
     * <p>
     * The analyze-stage system prompt explicitly tells the LLM that every
     * entry must come from the supplied index, but production LLMs are not
     * 100% reliable on negative constraints. This server-side validator is
     * the contract enforcement: invalid entries are silently dropped (with
     * a single warning log per analyze call, batched), so the downstream
     * generation prompt never sees an invented slug masquerading as a
     * curated whitelist.
     */
    private JsonNode validateRelatedPages(Long kbId, JsonNode analysisJson) {
        JsonNode relatedNode = analysisJson.path("related_pages");
        if (!relatedNode.isArray() || relatedNode.size() == 0) return analysisJson;

        java.util.Set<String> activeSlugs;
        try {
            activeSlugs = linkService.lowercaseSlugSet(pageService.listSummaries(kbId));
        } catch (RuntimeException e) {
            // Without a slug set, no validation is possible. Drop the whole
            // related_pages array — better than passing through unvalidated
            // suggestions that could be hallucinated.
            log.warn("[Wiki] Cannot validate related_pages for kbId={}, dropping array: {}",
                    kbId, e.toString());
            ObjectNode result = analysisJson.deepCopy();
            result.putArray("related_pages");
            return result;
        }

        com.fasterxml.jackson.databind.node.ArrayNode keptArray = objectMapper.createArrayNode();
        java.util.List<String> dropped = new java.util.ArrayList<>();
        for (JsonNode el : relatedNode) {
            String slug = el.asText("").trim();
            if (slug.isEmpty()) continue;
            if (activeSlugs.contains(slug.toLowerCase(java.util.Locale.ROOT))) {
                keptArray.add(slug);
            } else {
                dropped.add(slug);
            }
        }
        if (!dropped.isEmpty()) {
            log.warn("[Wiki] Analyze dropped {} hallucinated related_pages entries for kbId={}: {}",
                    dropped.size(), kbId, dropped);
        }
        ObjectNode result = analysisJson.deepCopy();
        result.set("related_pages", keptArray);
        return result;
    }

    /**
     * Build the "existing pages" index that the LLM consults when picking
     * cross-references during page generation / merge / compile.
     * <p>
     * Slug-first format — each line begins with {@code [[slug]]} so the
     * model has exactly one syntactically-valid target shape to copy. Title
     * and summary follow as semantic context, separated by em-dashes, so the
     * model can pick a relevant target without being confused about whether
     * to write the title or the slug. The earlier "**[[Title]]** (slug: `x`)"
     * format exposed two candidate target strings on every row, which let
     * the model write {@code [[Title]]} freely and produced the lint noise
     * this RFC exists to close.
     * <p>
     * Manual-edit and archived markers stay as plain-text trailing tags so
     * they don't drift into the link form. The lint downstream treats a
     * title-only match as a soft warning in v1; the long-term direction is
     * to delete the title-fallback once content has been regenerated under
     * the slug-first prompt.
     */
    private String buildExistingPagesIndex(Long kbId) {
        List<WikiPageEntity> summaries = pageService.listSummaries(kbId);
        if (summaries.isEmpty()) {
            return "（暂无已有页面）";
        }

        StringBuilder sb = new StringBuilder();
        for (WikiPageEntity page : summaries) {
            sb.append("- [[").append(page.getSlug()).append("]]");
            if (page.getTitle() != null && !page.getTitle().isBlank()) {
                sb.append(" — ").append(page.getTitle());
            }
            if ("manual".equals(page.getLastUpdatedBy())) {
                sb.append(" (手动编辑)");
            }
            String summary = page.getSummary();
            if (summary != null && !summary.isBlank()) {
                sb.append(" — ").append(summary);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * RFC-012 follow-up #3：Wiki 调用自带重试层（{@link #callLlmWithResilientRetry}），
     * 所以用 maxAttempts=1 的 RetryTemplate 关掉 Spring AI 的内层重试，避免两层重试互相抵消
     * （内层默认 2-3 次 × 180s readTimeout = 一次"外层 attempt"消耗 360-540s，
     * 让 wiki 的 maxTotalDurationMs=240s 被穿越，maxAttempts=5 永远到不了）。
     */
    private static final RetryTemplate WIKI_NO_RETRY = RetryTemplate.builder()
            .maxAttempts(1)
            .build();

    private ChatModel buildChatModel() {
        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        return agentGraphBuilder.buildRuntimeChatModel(defaultModel, WIKI_NO_RETRY);
    }

    /**
     * RFC-051 PR-3: build a {@link ChatModel} for an eager-pipeline step,
     * honoring the KB-level routing chain when {@link #modelRoutingService}
     * is available. Falls back to the system default on any lookup failure
     * so a misconfigured KB never blocks ingest.
     */
    private ChatModel buildChatModelFor(Long kbId, vip.mate.wiki.job.WikiJobStep step) {
        if (modelRoutingService != null && kbId != null && step != null) {
            try {
                Long modelId = modelRoutingService.selectModelId(kbId, "heavy_ingest", step);
                ModelConfigEntity model = modelConfigService.getModel(modelId);
                if (model != null) {
                    return agentGraphBuilder.buildRuntimeChatModel(model, WIKI_NO_RETRY);
                }
            } catch (Exception e) {
                log.warn("[Wiki] Model routing failed for kbId={} step={}, falling back to default: {}",
                        kbId, step, e.getMessage());
            }
        }
        return buildChatModel();
    }

    /**
     * 调用 LLM，带"任务完成或模型不可用才终止"的重试策略。
     * <p>
     * 可重试（一直重试直到成功）：网络抖动、5xx、429 限流、超时、连接中断、内容过滤偶发、JSON 空输出。
     * <p>
     * 立即终止（模型不可用）：401/403 认证失败、模型不存在、quota 用尽、非法 API key、
     * InterruptedException（优雅关停）。
     * <p>
     * 使用指数退避（1s → 2s → 4s → ... → 封顶 60s）。
     * <p>
     * RFC-012 M1：加入 maxAttempts 与 maxTotalDurationMs 双重上限，避免 nginx 504 这种
     * 反复瞬时错误把单 chunk 卡到永远；buildChatModel 提到循环外，所有重试复用同一实例。
     */
    private String callLlmWithResilientRetry(Prompt prompt, String ctx) {
        return callLlmWithResilientRetry(prompt, ctx, null, null);
    }

    /**
     * RFC-051 PR-3: step-aware LLM retry helper. {@code kbId} and {@code step}
     * pick the routed chat model; passing {@code null} for either reproduces
     * the legacy behavior (system default model).
     */
    private String callLlmWithResilientRetry(Prompt prompt, String ctx, Long kbId, vip.mate.wiki.job.WikiJobStep step) {
        long backoffMs = 1000;
        final long maxBackoffMs = 60_000;
        final int maxAttempts = Math.max(1, properties.getLlmMaxAttempts());
        final long maxTotalDurationMs = Math.max(1_000L, properties.getLlmMaxTotalDurationMs());
        final long startNanos = System.nanoTime();

        ResolvedChatModel resolved = resolveChatModel(kbId, step);
        ChatModel chatModel = resolved.chatModel;
        Long currentModelId = resolved.modelId;
        boolean alreadyFellBack = false;
        // When we fail over after a primary fatal, hold onto the primary's
        // exception so we can surface BOTH errors if the fallback also dies.
        // Operators reading logs need to see "the GLM 1113 we tried first" —
        // not just "DashScope timed out" with the original cause lost.
        Throwable primaryFatalError = null;
        String primaryRootInfo = null;

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                ChatResponse response = chatModel.call(prompt);
                if (response == null || response.getResult() == null
                        || response.getResult().getOutput() == null
                        || response.getResult().getOutput().getText() == null
                        || response.getResult().getOutput().getText().isBlank()) {
                    throw new TransientLlmException("Empty response from model");
                }
                if (attempt > 1) {
                    log.info("[Wiki] LLM call for {} succeeded on attempt {}", ctx, attempt);
                }
                // The provider that just answered is healthy: clear any pending
                // cooldown so subsequent calls can pick it freely. No-op on the
                // happy path (counter is already 0); cleanup after a fallback
                // success.
                recordProviderSuccess(currentModelId);
                // P0 telemetry: log token usage per LLM call so we can baseline costs before optimizing
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                try {
                    var usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
                    long pt = (usage != null && usage.getPromptTokens() != null) ? usage.getPromptTokens() : -1L;
                    long ct = (usage != null && usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : -1L;
                    log.info("[wiki-telemetry] ctx={} promptTokens={} completionTokens={} durationMs={}",
                            ctx, pt, ct, durationMs);
                } catch (Exception ignored) {}
                return response.getResult().getOutput().getText();
            } catch (Throwable t) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new RuntimeException("LLM call interrupted for " + ctx, t);
                }
                String rootInfo = summarizeRoot(t);
                if (isFatalModelError(t)) {
                    // Mark the wedged provider down so the chat-agent path,
                    // any future fallback walks, and the admin diagnostics
                    // know to skip it during cooldown.
                    recordProviderFailure(currentModelId);
                    // One-hop fallback: when the primary provider is wedged
                    // (auth / quota / 余额不足 / model-not-found), try the next
                    // configured chat model with a different provider before
                    // giving up. The hop is one-shot — if the fallback also
                    // fatals, we surface BOTH errors rather than walking the
                    // whole chain to avoid pathological loops.
                    if (!alreadyFellBack) {
                        ResolvedChatModel next = pickFallbackChatModel(currentModelId);
                        if (next != null) {
                            log.warn("[Wiki] LLM fatal on primary model={} for {}; failing over to model={} (rootCause={}): {}",
                                    currentModelId, ctx, next.modelId, rootInfo, t.getMessage());
                            primaryFatalError = t;
                            primaryRootInfo = rootInfo;
                            chatModel = next.chatModel;
                            currentModelId = next.modelId;
                            alreadyFellBack = true;
                            // Reset attempt counter so the fallback gets a fresh budget; total time
                            // budget continues to count down.
                            attempt = 0;
                            backoffMs = 1000;
                            continue;
                        }
                    }
                    if (alreadyFellBack && primaryFatalError != null) {
                        log.error("[Wiki] LLM unavailable (fatal on BOTH primary + fallback) for {}: primary rootCause={}: {} | fallback rootCause={}: {}",
                                ctx, primaryRootInfo, primaryFatalError.getMessage(), rootInfo, t.getMessage());
                        RuntimeException re = new RuntimeException(
                                "LLM unavailable on both primary and fallback. Primary (rootCause="
                                        + primaryRootInfo + "): " + primaryFatalError.getMessage()
                                        + " | Fallback (rootCause=" + rootInfo + "): " + t.getMessage(),
                                t);
                        re.addSuppressed(primaryFatalError);
                        throw re;
                    }
                    log.error("[Wiki] LLM unavailable (fatal) for {} after {} attempts on model={} (rootCause={}): {}",
                            ctx, attempt, currentModelId, rootInfo, t.getMessage());
                    throw new RuntimeException("LLM unavailable (rootCause=" + rootInfo + "): " + t.getMessage(), t);
                }
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                if (attempt >= maxAttempts || elapsedMs >= maxTotalDurationMs) {
                    log.error("[Wiki] LLM exhausted for {} after {} attempts in {}ms (limits: maxAttempts={}, maxTotalDurationMs={}, rootCause={}): {}",
                            ctx, attempt, elapsedMs, maxAttempts, maxTotalDurationMs, rootInfo, t.getMessage());
                    throw new RuntimeException("LLM exhausted after " + attempt + " attempts in " + elapsedMs
                            + "ms (rootCause=" + rootInfo + "): " + t.getMessage(), t);
                }
                long sleepMs = Math.min(backoffMs, Math.max(0L, maxTotalDurationMs - elapsedMs));
                log.warn("[Wiki] LLM transient failure for {} attempt={}/{} elapsed={}ms, retrying in {}ms (rootCause={}): {}",
                        ctx, attempt, maxAttempts, elapsedMs, sleepMs, rootInfo, t.getMessage());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("LLM retry interrupted for " + ctx, ie);
                }
                backoffMs = Math.min(maxBackoffMs, backoffMs * 2);
            }
        }
    }

    private void recordProviderFailure(Long modelId) {
        if (providerHealthTracker == null || modelId == null) return;
        try {
            ModelConfigEntity m = modelConfigService.getModel(modelId);
            if (m != null && m.getProvider() != null) {
                providerHealthTracker.recordFailure(m.getProvider());
            }
        } catch (Exception ignored) {}
    }

    private void recordProviderSuccess(Long modelId) {
        if (providerHealthTracker == null || modelId == null) return;
        try {
            ModelConfigEntity m = modelConfigService.getModel(modelId);
            if (m != null && m.getProvider() != null) {
                providerHealthTracker.recordSuccess(m.getProvider());
            }
        } catch (Exception ignored) {}
    }

    /** Pair of modelId + built ChatModel — null modelId means we used the system default. */
    private record ResolvedChatModel(Long modelId, ChatModel chatModel) {}

    private ResolvedChatModel resolveChatModel(Long kbId, vip.mate.wiki.job.WikiJobStep step) {
        if (modelRoutingService != null && kbId != null && step != null) {
            try {
                Long modelId = modelRoutingService.selectModelId(kbId, "heavy_ingest", step);
                ModelConfigEntity model = modelConfigService.getModel(modelId);
                if (model != null) {
                    return new ResolvedChatModel(modelId,
                            agentGraphBuilder.buildRuntimeChatModel(model, WIKI_NO_RETRY));
                }
            } catch (Exception e) {
                log.warn("[Wiki] Model routing failed for kbId={} step={}, falling back to default: {}",
                        kbId, step, e.getMessage());
            }
        }
        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        Long id = defaultModel == null ? null : defaultModel.getId();
        return new ResolvedChatModel(id, buildChatModel());
    }

    /**
     * Pick the next enabled chat model whose provider differs from the failed
     * one, so we cycle to a fresh credential / billing account rather than
     * retrying a wedged one. Returns null when no alternative exists.
     *
     * <p>Provider order is deterministic — driven by
     * {@code mate_model_provider.fallback_priority} (asc, lowest first) when
     * {@link #modelProviderService} is wired. Falls back to
     * {@code listEnabledModels} in DB order only when the provider service
     * isn't available (older test harnesses), so behavior is at-least
     * stable, never random.
     *
     * <p>Skips providers currently in cooldown ({@link
     * vip.mate.llm.failover.ProviderHealthTracker}) so a flapping provider
     * doesn't keep getting tried while we wait for it to recover.
     */
    private ResolvedChatModel pickFallbackChatModel(Long failedModelId) {
        try {
            String failedProviderId = null;
            if (failedModelId != null) {
                ModelConfigEntity failed = modelConfigService.getModel(failedModelId);
                if (failed != null) failedProviderId = failed.getProvider();
            }

            if (modelProviderService != null) {
                for (var provider : modelProviderService.listFallbackChain()) {
                    String pid = provider.getProviderId();
                    if (pid == null) continue;
                    if (pid.equals(failedProviderId)) continue;
                    if (providerHealthTracker != null && providerHealthTracker.isInCooldown(pid)) continue;
                    ResolvedChatModel built = firstChatModelForProvider(pid, failedModelId);
                    if (built != null) return built;
                }
            }

            // Defensive fallback path — only triggers in tests / minimal
            // environments without ModelProviderService wired. Mirrors the
            // pre-PR behavior so legacy tests don't regress.
            for (ModelConfigEntity candidate : modelConfigService.listEnabledModels()) {
                if (!isUsableChatFallback(candidate, failedModelId, failedProviderId)) continue;
                ResolvedChatModel built = tryBuild(candidate);
                if (built != null) return built;
            }
        } catch (Exception e) {
            log.debug("[Wiki] fallback lookup failed: {}", e.getMessage());
        }
        return null;
    }

    private ResolvedChatModel firstChatModelForProvider(String providerId, Long failedModelId) {
        List<ModelConfigEntity> rows;
        try {
            rows = modelConfigService.listModelsByProvider(providerId);
        } catch (Exception e) {
            log.debug("[Wiki] listModelsByProvider({}) failed: {}", providerId, e.getMessage());
            return null;
        }
        for (ModelConfigEntity candidate : rows) {
            if (!isUsableChatFallback(candidate, failedModelId, null)) continue;
            ResolvedChatModel built = tryBuild(candidate);
            if (built != null) return built;
        }
        return null;
    }

    private static boolean isUsableChatFallback(ModelConfigEntity candidate, Long failedModelId, String failedProviderId) {
        if (candidate == null || candidate.getId() == null) return false;
        if (candidate.getId().equals(failedModelId)) return false;
        if (!Boolean.TRUE.equals(candidate.getEnabled())) return false;
        String mt = candidate.getModelType();
        if (mt != null && !mt.isBlank() && !"chat".equalsIgnoreCase(mt)) return false;
        if (failedProviderId != null && failedProviderId.equals(candidate.getProvider())) return false;
        return true;
    }

    private ResolvedChatModel tryBuild(ModelConfigEntity candidate) {
        try {
            ChatModel built = agentGraphBuilder.buildRuntimeChatModel(candidate, WIKI_NO_RETRY);
            return new ResolvedChatModel(candidate.getId(), built);
        } catch (Exception e) {
            log.debug("[Wiki] fallback candidate model={} unbuildable: {}", candidate.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 判断是否为"模型不可用"级别的致命错误（不重试，立即终止）。
     * <p>
     * 三类视为 fatal：
     * <ul>
     *   <li><b>鉴权 / 配额 / 模型不存在</b>：401/403、invalid api key、model not found、quota 用尽</li>
     *   <li><b>prompt 结构性错误</b>：上下文超长、max_tokens 限制、prompt too long（重试也得同样结果）</li>
     *   <li><b>内容审核过滤</b>：content_filter 触发（被 safety 挡下的 prompt 重试也是同样结果）</li>
     * </ul>
     * 其余（网络、超时、5xx、429 限流、偶发空响应）均视为瞬时，按指数退避持续重试。
     * <p>
     * 说明：关键字启发式在极少数场景可能误判（例如瞬时错误的 message 恰好含 "authentication"），
     * 但实际云厂商 SDK 的错误消息规范度较高，这个风险可接受。
     */
    private boolean isFatalModelError(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 8) {
            // 按异常类型直接判 fatal —— DNS / 连接拒绝 / TLS 问题重试都是浪费
            String className = cur.getClass().getSimpleName();
            if ("UnknownHostException".equals(className)
                    || "SSLHandshakeException".equals(className)
                    || "CertificateException".equals(className)
                    || "SSLPeerUnverifiedException".equals(className)) {
                return true;
            }
            if ("ConnectException".equals(className) && cur.getMessage() != null
                    && cur.getMessage().toLowerCase().contains("refused")) {
                return true;
            }

            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                // 鉴权 / 配额 / 模型不存在（HTTP 401/403 + 提供方错误字段）
                if (m.contains("401") || m.contains("unauthorized")
                        || m.contains("403") || m.contains("forbidden")
                        || m.contains("invalid api key") || m.contains("invalid_api_key")
                        || m.contains("authentication") || m.contains("api key not valid")
                        || m.contains("model not found") || m.contains("model_not_found")
                        || m.contains("invalidapikey") || m.contains("invalid_request_error")
                        || m.contains("quota") || m.contains("insufficient_quota")
                        || m.contains("no default model") || m.contains("model configuration")) {
                    return true;
                }
                // 中文 provider 余额耗尽：Zhipu 1113 / DashScope "Throttling" 中文返回 / 通用 "余额不足"。
                // 原本走 "transient retry" 5 次 × 1s 退避，3 分钟才放弃；归类为 fatal 后立即触发 fallback hop。
                // Chinese patterns checked against original msg (case-insensitive in Chinese is moot);
                // codes / English snippets against the already-lowercased m.
                if (msg.contains("余额不足") || msg.contains("请充值")
                        || m.contains("\"code\":\"1113\"") || m.contains("\"code\":1113")
                        || m.contains("accountbalancenotenough")
                        || m.contains("balance not enough")) {
                    return true;
                }
                // 【Review Bug 3】prompt 结构性错误：重试也得同样结果，立即终止
                if (m.contains("context_length_exceeded")
                        || m.contains("context length")
                        || m.contains("maximum context")
                        || m.contains("max_tokens")
                        || m.contains("prompt too long")
                        || m.contains("input is too long")
                        || m.contains("token limit")) {
                    return true;
                }
                // 【Review Bug 2】内容审核过滤：被 safety 挡下的 prompt 重试也是同样结果
                if (m.contains("content_filter")
                        || m.contains("content filter")
                        || m.contains("data_inspection_failed")
                        || (m.contains("safety") && m.contains("block"))) {
                    return true;
                }
                // 基础设施类永久错误：关键字兜底（和类名判断互补，跨语言 SDK 也能抓到）
                if (m.contains("unknown host") || m.contains("no such host")
                        || m.contains("connection refused")
                        || m.contains("pkix path building failed")
                        || m.contains("certificate verify failed")
                        || m.contains("certificate_unknown")
                        || m.contains("ssl handshake")) {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 沿 getCause() 遍历到最深，返回根因异常的 "SimpleName: message" 形式。
     * <p>
     * Spring RestClient 会把 HTTP 层异常包装成 ResourceAccessException，外层消息统一是
     * "I/O error on POST request for ...: <rootMsg>"，根因的异常类型（HttpTimeoutException
     * / UnknownHostException / SSLHandshakeException / ConnectException）在最外层是看不到的。
     * UI 截断的时候又会把宝贵的根因关键字（"rootCause=..."）切掉，运维排错几乎盲猜。
     * <p>
     * 拼进最终抛出的 RuntimeException 消息里，UI 就算截断也能在前几十字看见类名。
     */
    private String summarizeRoot(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && cur.getCause() != null && cur.getCause() != cur && depth < 8) {
            cur = cur.getCause();
            depth++;
        }
        String cls = cur != null ? cur.getClass().getSimpleName() : "Unknown";
        String msg = cur != null ? cur.getMessage() : null;
        if (msg == null) return cls;
        // 截短 message 避免把整段 HTML 错误页塞进异常链
        String trimmed = msg.replaceAll("\\s+", " ").trim();
        if (trimmed.length() > 200) trimmed = trimmed.substring(0, 200) + "...";
        return cls + ": " + trimmed;
    }

    /** Transient error marker to route empty responses through the retry path */
    private static class TransientLlmException extends RuntimeException {
        TransientLlmException(String msg) { super(msg); }
    }

    // ==================== RFC-030: Error classification ====================

    /**
     * Classify an exception into an error code aligned with RFC-009 ErrorType.
     *
     * @return error code string: AUTH_ERROR, BILLING, MODEL_NOT_FOUND,
     *         RATE_LIMIT, SERVER_ERROR, TIMEOUT, CONTENT_FILTER, UNKNOWN
     */
    public String classifyErrorCode(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 8) {
            String className = cur.getClass().getSimpleName();
            if ("UnknownHostException".equals(className)
                    || "SSLHandshakeException".equals(className)) {
                return "AUTH_ERROR";
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("401") || m.contains("unauthorized") || m.contains("403")
                        || m.contains("forbidden") || m.contains("invalid api key")
                        || m.contains("invalid_api_key") || m.contains("authentication")) {
                    return "AUTH_ERROR";
                }
                if (m.contains("quota") || m.contains("insufficient_quota") || m.contains("billing")) {
                    return "BILLING";
                }
                if (m.contains("model not found") || m.contains("model_not_found")) {
                    return "MODEL_NOT_FOUND";
                }
                if (m.contains("429") || m.contains("rate_limit") || m.contains("too many requests")) {
                    return "RATE_LIMIT";
                }
                if (m.contains("content_filter") || m.contains("content filter")
                        || m.contains("data_inspection_failed")) {
                    return "CONTENT_FILTER";
                }
                if (m.contains("timeout") || m.contains("timed out")) {
                    return "TIMEOUT";
                }
                if (m.contains("500") || m.contains("502") || m.contains("503") || m.contains("504")) {
                    return "SERVER_ERROR";
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return "UNKNOWN";
    }

    // ==================== RFC-031: Methods for template delegation ====================

    /**
     * Repair a single page by regenerating its content.
     * Used by LocalRepairTemplate.
     */
    public void repairSinglePage(Long targetPageId, Long modelId) {
        WikiPageEntity page = pageService.getById(targetPageId);
        if (page == null) {
            log.warn("[Wiki] repairSinglePage: page not found: {}", targetPageId);
            return;
        }

        WikiKnowledgeBaseEntity kb = kbService.getById(page.getKbId());
        if (kb == null) return;

        // Find source raw material
        List<Long> rawIds = parseSourceRawIds(page.getSourceRawIds());
        if (rawIds.isEmpty()) {
            log.warn("[Wiki] repairSinglePage: no source raw IDs for page {}", targetPageId);
            return;
        }

        WikiRawMaterialEntity raw = rawService.getById(rawIds.get(0));
        if (raw == null) return;

        String textContent = rawService.getTextContent(raw);
        if (textContent == null || textContent.isBlank()) return;

        // Use existing two-phase single-page create logic
        String existingPagesIndex = buildExistingPagesIndex(kb.getId());
        String configContent = kb.getConfigContent() != null ? kb.getConfigContent() : "";
        String createSystem = PromptLoader.loadPrompt("wiki/create-page-system");
        String createUserTemplate = PromptLoader.loadPrompt("wiki/create-page-user");
        String createUser = createUserTemplate
                .replace("{config}", configContent)
                .replace("{existing_pages}", existingPagesIndex)
                .replace("{page_slug}", page.getSlug())
                .replace("{page_title}", page.getTitle())
                .replace("{page_summary}", page.getSummary() != null ? page.getSummary() : "")
                .replace("{raw_title}", raw.getTitle())
                .replace("{raw_content}", textContent);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(createSystem),
                new UserMessage(createUser)
        ));
        if (isAborted(raw.getId(), "repair page=" + page.getSlug())) return;
        String response = callLlmWithResilientRetry(prompt, "repair page=" + page.getSlug(),
                kb.getId(), vip.mate.wiki.job.WikiJobStep.MERGE_PAGE);
        com.fasterxml.jackson.databind.JsonNode pageJson = parseJsonResponse(response);
        if (pageJson == null) return;

        String content = pageJson.path("content").asText("");
        String summary = pageJson.path("summary").asText("");
        if (!content.isBlank()) {
            pageService.updatePageByAi(kb.getId(), page.getSlug(), content, summary, rawIds.get(0));
            log.info("[Wiki] Repaired page: {} (kbId={})", page.getSlug(), kb.getId());
        }
    }

    private List<Long> parseSourceRawIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private JsonNode parseJsonResponse(String response) {
        if (response == null || response.isBlank()) return null;

        String cleaned = response.trim();

        // 1. 剥离 markdown 代码块标记
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        // 2. 清洗控制字符（保留 \n \r \t），防止 LLM 输出含不可见字符导致 JSON 解析失败
        cleaned = cleaned.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        // 3. 第一次尝试直接解析
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            // 4. 如果整体不是 JSON，尝试提取第一个 JSON 对象块（LLM 可能在 JSON 前后加了说明文字）
            int jsonStart = cleaned.indexOf("{");
            int jsonEnd = cleaned.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String extracted = cleaned.substring(jsonStart, jsonEnd + 1);
                try {
                    return objectMapper.readTree(extracted);
                } catch (Exception e2) {
                    log.warn("[Wiki] Failed to parse extracted JSON block: {}", e2.getMessage());
                }
            }
            log.warn("[Wiki] Failed to parse JSON response: {}", e.getMessage());
            return null;
        }
    }

    /**
     * KB-level override for structured route output, falling back to the global
     * property when the KB has not set a preference. Parse failures fall back to
     * global too; ingestion must not be blocked by bad optional config.
     */
    private boolean resolveStructuredRouteFlag(WikiKnowledgeBaseEntity kb) {
        boolean fallback = properties.isUseStructuredRoute();
        if (kb == null || kb.getConfigContent() == null) return fallback;
        WikiKbConfig config = WikiKbConfigParser.parse(objectMapper, kb.getConfigContent());
        return config != null && config.getUseStructuredRoute() != null
                ? config.getUseStructuredRoute()
                : fallback;
    }

    /**
     * Read {@code ingestMode} from KB config JSON or markdown frontmatter.
     * Returns null on any parse error or missing field so the caller falls
     * through to eager mode.
     */
    private String resolveIngestMode(WikiKnowledgeBaseEntity kb) {
        if (kb == null || kb.getConfigContent() == null) return null;
        WikiKbConfig config = WikiKbConfigParser.parse(objectMapper, kb.getConfigContent());
        return config != null ? config.getIngestMode() : null;
    }

    /**
     * Returns {@code true} when the caller should bail out of an in-flight
     * processing path because the raw material has been deleted.
     * <p>
     * {@link WikiRawMaterialService#delete(Long)} is a logical delete. The row
     * disappears from normal lookups as soon as deletion commits. Checking
     * before each LLM call keeps token spend bounded by a single in-flight call
     * after the user clicks delete.
     *
     * @param rawId the raw material id this processing path is about
     * @param ctx   short string used in the log line
     * @return {@code true} if the raw is gone; caller should stop work
     */
    private boolean isAborted(Long rawId, String ctx) {
        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null) {
            log.info("[Wiki] Aborting {} for raw={}: raw was deleted mid-processing", ctx, rawId);
            return true;
        }
        if (Boolean.TRUE.equals(raw.getCancelRequested())) {
            log.info("[Wiki] Aborting {} for raw={}: cancellation requested by user", ctx, rawId);
            return true;
        }
        return false;
    }

    /**
     * RFC-051 PR-1c: bridge from {@link DocumentPreprocessService.Chunker} to
     * the existing sentence-boundary chunker. Returns {@code [start, end]}
     * pairs over the supplied text.
     */
    private List<int[]> splitToOffsetPairs(String text) {
        List<ChunkWithOffset> windows = splitIntoChunksWithOffsets(text);
        List<int[]> out = new ArrayList<>(windows.size());
        for (ChunkWithOffset w : windows) {
            out.add(new int[]{w.startOffset(), w.endOffset()});
        }
        return out;
    }

    /**
     * Legacy chunk persistence path used by the lazy branch when the
     * preprocessor is unavailable or yields nothing usable. Returns the
     * persisted chunk count.
     */
    private int persistLegacyLazy(Long kbId, Long rawId, String textContent) {
        List<ChunkWithOffset> chunksWithOffset = splitIntoChunksWithOffsets(textContent);
        List<String> chunks = chunksWithOffset.stream().map(ChunkWithOffset::text).toList();
        List<int[]> offsets = chunksWithOffset.stream()
                .map(c -> new int[]{c.startOffset(), c.endOffset()}).toList();
        chunkService.persistChunks(kbId, rawId, chunks, offsets);
        return chunks.size();
    }

    /**
     * Lazy ingest: chunk and embed, without page generation.
     * <p>
     * Intentionally minimal: reuses the legacy {@code persistChunks(List<String>, offsets)}
     * overload and the existing {@code embedMissingChunks} entry point. Zero
     * pages is the expected outcome, not a failure.
     */
    private void processLazyIngest(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw) {
        Long rawId = raw.getId();
        Long kbId = kb.getId();
        log.info("[Wiki] Lazy ingest starting for raw={}, kbId={}", rawId, kbId);

        rawService.updateProgress(rawId, "lazy", 0, 0);
        progressBus.broadcast(kbId, WikiProgressBus.EVENT_RAW_STARTED,
                java.util.Map.of("rawId", rawId, "phase", "lazy"));

        try {
            String textContent = rawService.getTextContent(raw);
            if (textContent == null || textContent.isBlank()) {
                rawService.updateProcessingStatus(rawId, "failed", "No text content available");
                kbService.updateStatus(kbId, "active");
                progressBus.broadcast(kbId, WikiProgressBus.EVENT_RAW_FAILED,
                        java.util.Map.of("rawId", rawId, "error", "No text content available"));
                return;
            }

            // Text extraction can take many seconds on large binaries. If the
            // user deleted the raw during that window, persisting chunks for a
            // tombstoned row is wasted work already covered by cascade cleanup.
            if (isAborted(rawId, "lazy ingest after extract")) {
                kbService.updateStatus(kbId, "active");
                return;
            }

            int totalChunks;
            // PR-1c: when the preprocessor is on the classpath, normalize +
            // attach metadata; otherwise fall back to the legacy chunker.
            if (preprocessService != null) {
                List<WikiChunkDraft> drafts = preprocessService.preprocess(raw, textContent, this::splitToOffsetPairs);
                if (drafts.isEmpty()) {
                    log.warn("[Wiki] Lazy preprocess produced 0 drafts for raw={}, falling back to legacy split", rawId);
                    totalChunks = persistLegacyLazy(kbId, rawId, textContent);
                } else {
                    chunkService.persistChunks(kbId, rawId, drafts);
                    totalChunks = drafts.size();
                }
            } else {
                totalChunks = persistLegacyLazy(kbId, rawId, textContent);
            }
            log.info("[Wiki] Lazy ingest persisted {} chunks for raw={}", totalChunks, rawId);

            // Async embedding — mirror the eager path so a slow embedding model
            // does not block the raw from reaching completed.
            final Long fKbId = kbId;
            WIKI_EXECUTOR.submit(() -> {
                try {
                    int embedded = embeddingService.embedMissingChunks(fKbId);
                    if (embedded > 0) {
                        log.info("[Wiki] Lazy async embedding completed: kbId={}, embedded={}", fKbId, embedded);
                    }
                } catch (vip.mate.wiki.job.WikiEmbeddingProviderFailingException ex) {
                    log.warn("[Wiki] Lazy async embedding aborted by circuit-breaker for kbId={}: {}",
                            fKbId, ex.getMessage());
                } catch (Exception ex) {
                    log.warn("[Wiki] Lazy async embedding failed for kbId={}: {}", fKbId, ex.getMessage());
                }
            });

            rawService.updateProcessingStatus(rawId, "completed", null);
            if (raw.getContentHash() != null) {
                rawService.setLastProcessedHash(rawId, raw.getContentHash());
            }
            rawService.updateProgress(rawId, "done", totalChunks, totalChunks);
            int pageCount = pageService.countByKbId(kbId);
            kbService.setPageCount(kbId, pageCount);
            kbService.updateStatus(kbId, "active");

            progressBus.broadcast(kbId, WikiProgressBus.EVENT_RAW_COMPLETED,
                    java.util.Map.of(
                            "rawId", rawId,
                            "status", "completed",
                            "totalPages", 0,
                            "kbPageCount", pageCount,
                            "totalChunks", totalChunks));

            // RFC-051 PR-2c: write an activity log entry. Lead with title so the log
            // is human-readable; raw id is implied by chunk lineage.
            if (logService != null) {
                String title = (raw.getTitle() == null || raw.getTitle().isBlank())
                        ? ("raw#" + rawId) : raw.getTitle();
                logService.append(kbId, WikiLogService.EventType.INGEST,
                        "lazy ingest · " + title + " · " + totalChunks + " chunks");
            }
            // RFC-051 PR-2b: refresh overview stats.
            if (overviewService != null) overviewService.rebuild(kbId);
            // Tier 2: dirty event drives the LLM-narrated overview section.
            eventPublisher.publishEvent(new vip.mate.wiki.event.WikiKbDirtyEvent(this, kbId));

            log.info("[Wiki] Lazy processing completed for raw={}, kbId={}, chunks={}",
                    rawId, kbId, totalChunks);
        } catch (Exception e) {
            log.error("[Wiki] Lazy processing failed for raw={}: {}", rawId, e.getMessage(), e);
            rawService.updateProcessingStatus(rawId, "failed", e.getMessage());
            kbService.updateStatus(kbId, "active");
            progressBus.broadcast(kbId, WikiProgressBus.EVENT_RAW_FAILED,
                    java.util.Map.of("rawId", rawId,
                            "error", e.getMessage() == null ? "unknown" : e.getMessage()));
        }
    }
}
