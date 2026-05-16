package vip.mate.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.approval.event.WorkflowApprovalResolvedEvent;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 审批工作流服务（write-through: 内存 + DB 双写）
 * <p>
 * 在现有 ApprovalService（内存层）之上，增加 DB 持久化。
 * 所有写操作先走 ApprovalService，再写 DB。
 * 启动时从 DB 恢复 PENDING 状态到内存。
 */
@Slf4j
@Service
@Order(55) // Schema 由 Flyway 管理，在 Flyway 迁移完成后执行
@RequiredArgsConstructor
public class ApprovalWorkflowService implements ApplicationRunner {

    private final ApprovalService approvalService;
    private final ToolApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;
    /** Optional — injected only in full Spring context. The workflow
     *  module listens for {@link WorkflowApprovalResolvedEvent}; in tests
     *  that don't wire the workflow runtime this stays null and the
     *  publish is a no-op. */
    @Autowired(required = false)
    private ApplicationEventPublisher events;

    /**
     * GC scheduler — owns the 5-minute clock for the entire approval state machine
     * (RFC-067 §4.4). Lives on the workflow rather than {@link ApprovalService} so
     * timeout / overflow eviction goes through the same DB+metadata+memory two-phase
     * path as approve / deny — the in-memory map can no longer drift ahead of DB.
     */
    private ScheduledExecutorService gcScheduler;

    @Override
    public void run(ApplicationArguments args) {
        recoverFromDb();
    }

    @PostConstruct
    void initGc() {
        gcScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "approval-gc");
            t.setDaemon(true);
            return t;
        });
        gcScheduler.scheduleAtFixedRate(this::garbageCollect, 5, 5, TimeUnit.MINUTES);
        log.info("[ApprovalWorkflow] GC scheduler started (interval=5min)");
    }

    @PreDestroy
    void shutdownGc() {
        if (gcScheduler != null) {
            gcScheduler.shutdownNow();
        }
    }

    /**
     * Drop in-memory approval state for a deleted conversation. The cascade in
     * {@link ConversationService#deleteConversation} already removed the
     * {@code mate_tool_approval} rows; this listener clears the parallel
     * {@code pendingMap} entries so {@code findPendingByConversation} cannot
     * keep returning a ghost approval that points at a non-existent
     * conversation row.
     * <p>
     * Runs after the DB cascade commits — see
     * {@link ConversationDeletedEvent}.
     */
    @EventListener
    public void onConversationDeleted(ConversationDeletedEvent event) {
        int removed = approvalService.removeAllByConversation(event.conversationId());
        if (removed > 0) {
            log.info("[ApprovalWorkflow] Dropped {} in-memory pending entries for deleted conversation {}",
                    removed, event.conversationId());
        }
    }

    /**
     * Reconstruct in-memory pending approvals from DB at startup, preserving the
     * original {@code pendingId} and {@code createdAt} so subsequent resolve / GC
     * paths stay consistent with the persisted row.
     * <p>
     * Effective expiration follows {@code expireAt != null ? expireAt : createdAt + PENDING_TTL},
     * so legacy / test rows whose {@code expireAt} column is NULL still time out. Expired
     * rows are reconciled (DB → TIMEOUT, message metadata → DENIED) and skipped from
     * the in-memory map. See RFC-067 §4.1.
     */
    void recoverFromDb() {
        try {
            List<ToolApprovalEntity> pendingRecords = approvalMapper.selectList(
                    new LambdaQueryWrapper<ToolApprovalEntity>()
                            .eq(ToolApprovalEntity::getStatus, "PENDING")
                            .orderByAsc(ToolApprovalEntity::getCreatedAt)
            );

            int recovered = 0;
            int expired = 0;
            Instant now = Instant.now();
            for (ToolApprovalEntity entity : pendingRecords) {
                // Defensive null handling: a row with neither createdAt nor expireAt is
                // treated as freshly created so the next GC tick can revisit it instead
                // of being silently lost.
                Instant createdAt = entity.getCreatedAt() != null
                        ? entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()
                        : now;
                Instant effectiveExpireAt = entity.getExpireAt() != null
                        ? entity.getExpireAt().atZone(ZoneId.systemDefault()).toInstant()
                        : createdAt.plus(ApprovalService.PENDING_TTL);

                if (now.isAfter(effectiveExpireAt)) {
                    expireRecoveredRow(entity);
                    expired++;
                    continue;
                }

                PendingApproval snapshot = new PendingApproval(
                        entity.getPendingId(),
                        entity.getConversationId(),
                        entity.getUserId(),
                        entity.getToolName(),
                        entity.getToolArguments(),
                        entity.getSummary(),
                        createdAt,
                        "pending"
                );
                snapshot.setToolCallPayload(entity.getToolCallPayload());
                snapshot.setSiblingToolCalls(entity.getSiblingToolCalls());
                snapshot.setAgentId(entity.getAgentId());
                snapshot.setChannelType(entity.getChannelType());
                snapshot.setRequesterName(entity.getRequesterName());
                snapshot.setReplyTarget(entity.getReplyTarget());
                snapshot.setFindingsJson(entity.getFindingsJson());
                snapshot.setMaxSeverity(entity.getMaxSeverity());
                snapshot.setSummary(entity.getSummary());
                snapshot.setChatOrigin(entity.getChatOrigin());

                approvalService.registerRecovered(snapshot);
                recovered++;
            }

            if (recovered > 0 || expired > 0) {
                log.info("[ApprovalWorkflow] DB recovery: recovered={}, expired={}", recovered, expired);
            }
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to recover from DB (table may not exist yet): {}", e.getMessage());
        }
    }

    /**
     * Move an expired DB row to TIMEOUT and reconcile message metadata so the UI does
     * not hydrate a ghost approval after restart.
     * <p>
     * Order matters: metadata writes are gated on DB success. If {@code updateById}
     * throws or affects zero rows, we skip the metadata flip so the three persistence
     * loci (DB / message metadata / in-memory map) cannot drift apart — DB stuck on
     * PENDING + metadata flipped to DENIED is the worst-case ghost state because the
     * next recoverFromDb would re-revive the approval while the UI insists it was
     * already settled.
     */
    private void expireRecoveredRow(ToolApprovalEntity entity) {
        int rowsUpdated;
        try {
            entity.setStatus("TIMEOUT");
            entity.setResolvedAt(LocalDateTime.now());
            rowsUpdated = approvalMapper.updateById(entity);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to mark expired row {} as TIMEOUT: {}",
                    entity.getPendingId(), e.getMessage());
            return;
        }
        if (rowsUpdated == 0) {
            log.warn("[ApprovalWorkflow] Expire skipped: DB row for pending {} affected 0 rows " +
                    "(concurrent resolve?); leaving metadata untouched", entity.getPendingId());
            return;
        }
        try {
            conversationService.markPendingApprovalsResolved(
                    entity.getConversationId(),
                    Set.of(entity.getPendingId()),
                    MetadataDecision.DENIED);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to reconcile metadata for expired pending {}: {}",
                    entity.getPendingId(), e.getMessage());
        }
    }

    /**
     * 创建待审批记录（增强版，含 GuardEvaluation）
     */
    public String createPending(String conversationId, String userId,
                                String toolName, String toolArguments, String reason,
                                String toolCallPayload, String siblingToolCalls, String agentId,
                                GuardEvaluation evaluation) {
        // 1. 内存层
        String pendingId = approvalService.createPending(
                conversationId, userId, toolName, toolArguments, reason,
                toolCallPayload, siblingToolCalls, agentId);

        // RFC-063r §2.12: capture the originating ChatOrigin from the holder.
        // The holder was set by AgentService.{chat,chatStream,...} for the
        // duration of the agent invocation that produced this approval — so
        // it is non-null for IM / web triggered tool calls. Snapshot is
        // serialized once here and persisted on the DB row so cross-restart
        // replays keep the channel binding.
        String chatOriginJson = serializeChatOrigin(ChatOriginHolder.get());

        // 2. 增强内存记录
        approvalService.getPending(pendingId).ifPresent(pending -> {
            if (evaluation != null) {
                pending.setFindingsJson(serializeFindings(evaluation.findings()));
                pending.setMaxSeverity(evaluation.maxSeverity() != null ? evaluation.maxSeverity().name() : null);
                pending.setSummary(evaluation.summary());
            }
            pending.setChatOrigin(chatOriginJson);
        });

        // 3. DB 层
        persistToDb(pendingId, conversationId, userId, toolName, toolArguments,
                toolCallPayload, siblingToolCalls, agentId, evaluation, chatOriginJson);

        return pendingId;
    }

    /**
     * 创建待审批记录（基础版，向后兼容）
     */
    public String createPending(String conversationId, String userId,
                                String toolName, String toolArguments, String reason,
                                String toolCallPayload, String siblingToolCalls, String agentId) {
        return createPending(conversationId, userId, toolName, toolArguments, reason,
                toolCallPayload, siblingToolCalls, agentId, null);
    }

    /**
     * Workflow-scoped approval request — creates a {@code mate_tool_approval}
     * row keyed to a workflow run + step instead of a conversation, so an
     * {@code await_approval} step is visible in the same approval inbox the
     * tool-approval flow uses. Returns the row's auto-generated long id; the
     * caller (typically {@code AwaitApprovalStepAdapter}) writes that id back
     * onto {@code mate_workflow_run_pause.external_approval_id} so a future
     * approval-resolve callback can map "approval X resolved → resume run Y".
     *
     * <p>The approval row's {@code conversationId} is set to
     * {@code "workflow:run:{runId}"} as a synthetic key — that lets the
     * existing {@link ApprovalService#findPendingByConversation} surface the
     * workflow approval to operator UIs without needing a parallel query
     * surface. {@code toolName} is set to {@code "workflow:{kind}"} so the
     * inbox can group / filter workflow approvals from tool approvals.
     *
     * <p>v0 keeps the resume path through {@code WorkflowResumeController}
     * with the pauseToken; this method does not yet wire a resolve→resume
     * callback. The approval row's purpose for v0 is operator visibility
     * and a stable foreign key for the pause record.
     */
    public Long requestWorkflowApproval(long workspaceId,
                                        long runId,
                                        Long stepId,
                                        String approvalKind,
                                        String approvalMessage,
                                        java.util.List<String> approverChannels,
                                        Integer timeoutSecs) {
        try {
            ToolApprovalEntity entity = new ToolApprovalEntity();
            // pendingId is the string handle the existing approval pipeline
            // uses for resolve / get; "wf-" prefix lets future code branch
            // on workflow-scoped vs tool-scoped approvals at a glance. The
            // pending_id column is VARCHAR(32) so we trim a no-dashes UUID
            // down to fit ("wf-" + 24 hex chars = 27 chars; collisions of
            // 24 hex chars per workflow are astronomically rare and we
            // also fall back to UNIQUE-key violation handling).
            String shortId = java.util.UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 24);
            entity.setPendingId("wf-" + shortId);
            entity.setConversationId("workflow:run:" + runId);
            String kind = approvalKind == null || approvalKind.isBlank() ? "manual" : approvalKind.trim();
            entity.setToolName("workflow:" + kind);
            entity.setSummary(approvalMessage == null ? "" : approvalMessage);
            // Encode approver channels in tool_arguments so the inbox UI can
            // render which channels were asked. Plain JSON to keep parsing
            // trivial on the read path.
            try {
                if (approverChannels != null && !approverChannels.isEmpty()) {
                    entity.setToolArguments(objectMapper.writeValueAsString(
                            java.util.Map.of(
                                    "runId", runId,
                                    "stepId", stepId,
                                    "approverChannels", approverChannels)));
                }
            } catch (Exception e) {
                log.warn("[ApprovalWorkflow] failed to encode approverChannels: {}", e.getMessage());
            }
            entity.setStatus("PENDING");
            entity.setCreatedAt(LocalDateTime.now());
            entity.setExpireAt(LocalDateTime.now().plusSeconds(
                    timeoutSecs != null && timeoutSecs > 0 ? timeoutSecs : 30 * 60));
            approvalMapper.insert(entity);
            log.info("[ApprovalWorkflow] requested workflow approval row id={}, runId={}, workspace={}, kind={}",
                    entity.getId(), runId, workspaceId, kind);
            return entity.getId();
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] requestWorkflowApproval failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve a pending approval (approve / deny) following the RFC-067 §4.2 two-phase
     * contract: snapshot → DB UPDATE conditional on {@code status='PENDING'} →
     * metadata reconciliation → memory mutation queued for after-commit.
     * <p>
     * Idempotent under concurrent resolve: when the DB UPDATE affects 0 rows (because
     * another caller — IM channel, GC, recoverFromDb — already moved the row off
     * PENDING), this method returns {@link ResolveOutcome#alreadyResolved(String)}
     * without touching metadata or in-memory state. Callers should treat this as a
     * silent no-op; do not surface a user-facing error.
     * <p>
     * On DB / metadata write failure the transaction rolls back and in-memory state
     * stays untouched, so a retry from the next GC tick can recover. Memory mutation
     * is registered as an {@code afterCommit} synchronization, never inline, so a
     * post-update commit failure cannot leave memory ahead of DB.
     *
     * @param pendingId target approval id
     * @param userId    actor performing the resolution (for audit)
     * @param decision  case-insensitive {@code "approved"} or {@code "denied"}
     * @return {@link ResolveOutcome} carrying the resolved snapshot + DB / metadata
     *         counters; idempotent return on no-op
     */
    @Transactional
    public ResolveOutcome resolve(String pendingId, String userId, String decision) {
        boolean approved = "approved".equalsIgnoreCase(decision);
        String dbStatus = approved ? "APPROVED" : "DENIED";
        MetadataDecision metaDecision = approved ? MetadataDecision.APPROVED : MetadataDecision.DENIED;
        String snapshotStatus = approved ? "approved" : "denied";

        return performResolve(pendingId, userId, dbStatus, metaDecision, snapshotStatus,
                /* removeFromMap */ false);
    }

    /**
     * Atomically resolve {@code approved} and consume the snapshot for replay.
     * Same two-phase contract as {@link #resolve}, additionally removing the
     * pending entry from the in-memory map after commit so a subsequent
     * {@link #findPendingByConversation(String)} returns null and consume is
     * single-shot. The returned {@link ResolveOutcome#consumedSnapshot()} carries
     * {@code toolCallPayload} for replay.
     */
    @Transactional
    public ResolveOutcome resolveAndConsume(String pendingId, String userId) {
        return performResolve(pendingId, userId, "CONSUMED", MetadataDecision.APPROVED,
                "consumed", /* removeFromMap */ true);
    }

    /**
     * Consume the earliest already-{@code approved} record for the conversation +
     * tool — used when an out-of-band approval (e.g. /approve text command flow that
     * resolved the record) needs to be redeemed for replay.
     */
    @Transactional
    public ResolveOutcome consumeApproved(String conversationId, String toolName) {
        PendingApproval target = approvalService.findApprovedForConsume(conversationId, toolName);
        if (target == null) {
            return ResolveOutcome.alreadyResolved(null);
        }
        return performResolveOnSnapshot(target, null, "CONSUMED", MetadataDecision.APPROVED,
                "consumed", /* removeFromMap */ true);
    }

    /**
     * Bulk-deny every pending approval in the conversation (RFC-067 §4.4.1). Used by
     * the Web Stop endpoint to clear orphaned approvals when the user halts a turn
     * mid-stream; without this sweep, in-flight pendings linger in the map and
     * resurrect via metadata after refresh / restart.
     * <p>
     * Two-phase per row: DB → {@code DENIED}, message metadata → {@code DENIED},
     * map removed. Per-row failures are logged and the sweep continues; the returned
     * list contains only the outcomes that successfully advanced through DB.
     *
     * @return outcome per pending that successfully transitioned to {@code DENIED}
     */
    @Transactional
    public List<ResolveOutcome> denyAllByConversation(String conversationId, String userId) {
        List<PendingApproval> targets = approvalService.snapshotPendingByConversation(
                conversationId, /* excludePendingId */ null);
        if (targets.isEmpty()) return List.of();
        List<ResolveOutcome> outcomes = new java.util.ArrayList<>(targets.size());
        for (PendingApproval target : targets) {
            try {
                ResolveOutcome outcome = performResolveOnSnapshot(target, userId, "DENIED",
                        MetadataDecision.DENIED, "denied", /* removeFromMap */ true);
                if (outcome.dbSynced()) outcomes.add(outcome);
            } catch (Exception e) {
                log.warn("[ApprovalWorkflow] denyAll: failed to deny {}: {}",
                        target.getPendingId(), e.getMessage());
            }
        }
        return outcomes;
    }

    /**
     * Cancel every other pending approval in the conversation (excluding optional
     * {@code excludePendingId}) — used when a user submits a fresh message and the
     * old approval is implicitly abandoned. Each cancelled record goes through the
     * same two-phase contract; metadata flips to {@code DENIED} (per RFC-067 §4.4.1
     * state mapping for {@code superseded}).
     *
     * @return one outcome per pending that was actually moved off PENDING (empty list
     *         if there was nothing to cancel)
     */
    @Transactional
    public List<ResolveOutcome> cancelStalePending(String conversationId, String excludePendingId) {
        List<PendingApproval> targets = approvalService.snapshotPendingByConversation(
                conversationId, excludePendingId);
        if (targets.isEmpty()) return List.of();
        List<ResolveOutcome> outcomes = new java.util.ArrayList<>(targets.size());
        for (PendingApproval target : targets) {
            ResolveOutcome outcome = performResolveOnSnapshot(target, null, "SUPERSEDED",
                    MetadataDecision.DENIED, "superseded", /* removeFromMap */ true);
            if (outcome.dbSynced()) outcomes.add(outcome);
        }
        return outcomes;
    }

    /**
     * Time out a single pending approval (RFC-067 §4.4): same two-phase contract as
     * {@link #resolve} but with DB → {@code TIMEOUT} and metadata → {@code DENIED}
     * (per RFC-067 §4.4.1 state mapping). Called by the GC scheduler for entries
     * past {@link ApprovalService#PENDING_TTL} or beyond {@link ApprovalService#MAX_PENDING}.
     * Package-private — not part of the external resolve API.
     */
    @Transactional
    ResolveOutcome markTimeout(String pendingId) {
        return performResolve(pendingId, null, "TIMEOUT", MetadataDecision.DENIED, "timeout",
                /* removeFromMap */ true);
    }

    /**
     * GC tick (5-minute cadence; runs in {@code approval-gc} daemon thread).
     * <ol>
     *   <li>Phase A — pending older than {@link ApprovalService#PENDING_TTL} time out
     *       through the full DB+metadata+memory contract.</li>
     *   <li>Phase B — when total pending count exceeds {@link ApprovalService#MAX_PENDING},
     *       evict the oldest excess via the same {@code markTimeout} path.</li>
     *   <li>Phase C — already-resolved entries (DB row already terminal) past
     *       {@link ApprovalService#RESOLVED_TTL} or beyond
     *       {@link ApprovalService#MAX_RESOLVED} are dropped from the map only —
     *       the DB does not need touching, nor does message metadata.</li>
     * </ol>
     * Each pending entry's transition runs in its own transaction (markTimeout is
     * @Transactional) so a single bad row doesn't block the rest of the sweep.
     */
    public void garbageCollect() {
        Instant now = Instant.now();

        // Phase A — TTL-expired pending. Snapshot first so we don't mutate a map
        // we're iterating; markTimeout handles its own DB+metadata+memory contract.
        int timedOut = 0;
        for (PendingApproval expired : approvalService.snapshotExpiredPending(now)) {
            try {
                ResolveOutcome outcome = markTimeout(expired.getPendingId());
                if (outcome.dbSynced()) timedOut++;
            } catch (Exception e) {
                log.warn("[ApprovalWorkflow] GC: markTimeout failed for {}: {}",
                        expired.getPendingId(), e.getMessage());
            }
        }

        // Phase B — pending overflow eviction. Same path; just driven by a count cap.
        int evictedPending = 0;
        for (PendingApproval excess : approvalService.snapshotExcessPending(ApprovalService.MAX_PENDING)) {
            try {
                ResolveOutcome outcome = markTimeout(excess.getPendingId());
                if (outcome.dbSynced()) evictedPending++;
            } catch (Exception e) {
                log.warn("[ApprovalWorkflow] GC: overflow markTimeout failed for {}: {}",
                        excess.getPendingId(), e.getMessage());
            }
        }

        // Phase C — resolved cleanup. Memory-only; DB rows for these entries are
        // already terminal (CONSUMED / DENIED / TIMEOUT / SUPERSEDED) so nothing
        // would change in DB or metadata.
        int droppedResolved = approvalService.dropResolvedExceedingLimits(now);

        if (timedOut > 0 || evictedPending > 0 || droppedResolved > 0) {
            log.info("[ApprovalWorkflow] GC: timed-out {}, evicted-pending {}, dropped-resolved {}, remaining={}",
                    timedOut, evictedPending, droppedResolved, approvalService.size());
        }
    }

    // ==================== Global pending query (admin/notification surface) ====================

    /**
     * Default cap for {@link #listPendingFromDb(int)} when callers don't specify
     * one, so a runaway pending table cannot drown the notification panel.
     */
    public static final int DEFAULT_PENDING_LIST_LIMIT = 200;

    /**
     * Hard ceiling regardless of caller-requested limit.
     */
    public static final int MAX_PENDING_LIST_LIMIT = 500;

    /**
     * Return every {@code PENDING} approval row, newest first, capped at {@code limit}.
     * Reads from {@code mate_tool_approval} directly so restart / recovery edge
     * cases cannot leave the in-memory map and the DB out of sync from a caller's
     * perspective.
     *
     * <p>Payload shape matches {@link ApprovalService#getPendingByConversation},
     * so the same frontend renderer can consume both surfaces.
     */
    public List<Map<String, Object>> listPendingFromDb(int limit) {
        int effectiveLimit = limit <= 0 ? DEFAULT_PENDING_LIST_LIMIT
                : Math.min(limit, MAX_PENDING_LIST_LIMIT);
        List<ToolApprovalEntity> rows;
        try {
            rows = approvalMapper.selectList(
                    new LambdaQueryWrapper<ToolApprovalEntity>()
                            .eq(ToolApprovalEntity::getStatus, "PENDING")
                            .orderByDesc(ToolApprovalEntity::getCreatedAt)
                            .last("LIMIT " + effectiveLimit)
            );
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] listPendingFromDb failed: {}", e.getMessage());
            return List.of();
        }
        return rows.stream().map(this::toPendingPayload).toList();
    }

    /**
     * Count of pending approvals in {@code mate_tool_approval}. Used by the
     * notification summary endpoint; cheap enough to call on every poll.
     */
    public long countPendingFromDb() {
        try {
            Long n = approvalMapper.selectCount(
                    new LambdaQueryWrapper<ToolApprovalEntity>()
                            .eq(ToolApprovalEntity::getStatus, "PENDING")
            );
            return n == null ? 0L : n;
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] countPendingFromDb failed: {}", e.getMessage());
            return 0L;
        }
    }

    private Map<String, Object> toPendingPayload(ToolApprovalEntity entity) {
        java.util.LinkedHashMap<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("pendingId", entity.getPendingId());
        entry.put("conversationId", entity.getConversationId());
        entry.put("agentId", entity.getAgentId());
        entry.put("toolName", entity.getToolName());
        entry.put("toolArguments", entity.getToolArguments() != null ? entity.getToolArguments() : "");
        entry.put("status", "pending");
        entry.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        if (entity.getFindingsJson() != null) {
            entry.put("findingsJson", entity.getFindingsJson());
        }
        if (entity.getMaxSeverity() != null) {
            entry.put("maxSeverity", entity.getMaxSeverity());
        }
        if (entity.getSummary() != null) {
            entry.put("summary", entity.getSummary());
        }
        if (entity.getChannelType() != null) {
            entry.put("channelType", entity.getChannelType());
        }
        if (entity.getRequesterName() != null) {
            entry.put("requesterName", entity.getRequesterName());
        }
        return entry;
    }

    // ---------- shared two-phase machinery ----------

    private ResolveOutcome performResolve(String pendingId, String userId,
                                          String dbStatus, MetadataDecision metaDecision,
                                          String snapshotStatus, boolean removeFromMap) {
        PendingApproval snapshot = approvalService.getPending(pendingId).orElse(null);
        if (snapshot == null || !"pending".equals(snapshot.getStatus())) {
            log.debug("[ApprovalWorkflow] resolve {}: not pending (snapshot={}, status={})",
                    pendingId, snapshot != null, snapshot != null ? snapshot.getStatus() : "n/a");
            return ResolveOutcome.alreadyResolved(pendingId);
        }
        return performResolveOnSnapshot(snapshot, userId, dbStatus, metaDecision,
                snapshotStatus, removeFromMap);
    }

    private ResolveOutcome performResolveOnSnapshot(PendingApproval snapshot, String userId,
                                                    String dbStatus, MetadataDecision metaDecision,
                                                    String snapshotStatus, boolean removeFromMap) {
        // Phase 1 — DB UPDATE (conditional). The eq("PENDING") guard makes the call
        // idempotent: if another path already won, we get rows=0 and bail without
        // touching metadata or memory.
        int rows;
        try {
            LambdaUpdateWrapper<ToolApprovalEntity> wrapper = new LambdaUpdateWrapper<ToolApprovalEntity>()
                    .eq(ToolApprovalEntity::getPendingId, snapshot.getPendingId())
                    .eq(ToolApprovalEntity::getStatus, "PENDING")
                    .set(ToolApprovalEntity::getStatus, dbStatus)
                    .set(ToolApprovalEntity::getResolvedAt, LocalDateTime.now());
            if (userId != null) {
                wrapper.set(ToolApprovalEntity::getResolvedBy, userId);
            }
            rows = approvalMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] DB UPDATE failed for {} -> {}: {}",
                    snapshot.getPendingId(), dbStatus, e.getMessage());
            // Re-throw so @Transactional rolls back any partial state and the caller sees the failure.
            throw e;
        }
        if (rows == 0) {
            log.info("[ApprovalWorkflow] resolve no-op for {}: DB row not in PENDING (concurrent resolve)",
                    snapshot.getPendingId());
            return ResolveOutcome.alreadyResolved(snapshot.getPendingId());
        }

        // Phase 2 — metadata. Same transaction. If this throws, @Transactional rolls back DB.
        int rewritten = conversationService.markPendingApprovalsResolved(
                snapshot.getConversationId(),
                Set.of(snapshot.getPendingId()),
                metaDecision);

        // Phase 3 — memory mutation, deferred until after commit. Registering inside
        // a @Transactional method binds the hook to the active tx; if the tx rolls
        // back (post-method but pre-commit failure, e.g. constraint violation at
        // flush), the hook never fires and memory stays consistent with DB.
        Instant resolvedAt = Instant.now();
        afterCommit(() -> {
            snapshot.setStatus(snapshotStatus);
            snapshot.setResolvedAt(resolvedAt);
            if (userId != null) snapshot.setResolvedBy(userId);
            if (removeFromMap) approvalService.removeFromMap(snapshot.getPendingId());
        });

        // Phase 4 — workflow bridge. Workflow-scoped approval rows
        // (pendingId starting with "wf-") are linked to a paused workflow
        // run via {@code mate_workflow_run_pause.external_approval_id}.
        // Publishing the resolve here lets the workflow module's listener
        // call WorkflowResumer with the matching outcome, so an operator
        // approving in the inbox actually advances the workflow instead
        // of leaving it paused forever. We publish AFTER commit so a tx
        // rollback can't fire a stale resume; the row id is stable
        // because the row already lived in DB.
        if (events != null && snapshot.getPendingId() != null
                && snapshot.getPendingId().startsWith("wf-")) {
            // Look up the row id since the snapshot only carries the string
            // pendingId, not the long primary key. One quick equality query.
            try {
                ToolApprovalEntity row = approvalMapper.selectOne(
                        new LambdaQueryWrapper<ToolApprovalEntity>()
                                .eq(ToolApprovalEntity::getPendingId, snapshot.getPendingId()));
                if (row != null && row.getId() != null) {
                    final long rowId = row.getId();
                    final String pendingId = snapshot.getPendingId();
                    afterCommit(() -> {
                        try {
                            events.publishEvent(new WorkflowApprovalResolvedEvent(
                                    rowId, pendingId, snapshotStatus, /* workspaceId */ null));
                        } catch (Exception e) {
                            log.warn("[ApprovalWorkflow] failed to publish workflow-resolved event for {}: {}",
                                    pendingId, e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("[ApprovalWorkflow] approval row lookup for resolve event failed for {}: {}",
                        snapshot.getPendingId(), e.getMessage());
            }
        }

        boolean consumed = "consumed".equals(snapshotStatus);
        ResolveOutcome outcome = consumed
                ? ResolveOutcome.consumed(snapshot, true, rewritten)
                : ResolveOutcome.resolved(snapshot,
                        "superseded".equals(snapshotStatus) ? "superseded" : snapshotStatus,
                        true, rewritten);
        log.info("[ApprovalWorkflow] resolved id={}, decision={}, dbStatus={}, messagesRewritten={}",
                snapshot.getPendingId(), outcome.decision(), dbStatus, rewritten);
        return outcome;
    }

    /**
     * Run a memory mutation only after the surrounding {@code @Transactional} method's
     * tx commits. When called outside a transaction (e.g. unit tests that bypass the
     * proxy), executes immediately to keep test ergonomics simple.
     */
    private void afterCommit(Runnable hook) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    hook.run();
                }
            });
        } else {
            hook.run();
        }
    }

    /**
     * 代理查询方法
     */
    public PendingApproval findPendingByConversation(String conversationId) {
        return approvalService.findPendingByConversation(conversationId);
    }

    public List<Map<String, Object>> getPendingByConversation(String conversationId) {
        return approvalService.getPendingByConversation(conversationId);
    }

    // ==================== 内部方法 ====================

    private void persistToDb(String pendingId, String conversationId, String userId,
                             String toolName, String toolArguments,
                             String toolCallPayload, String siblingToolCalls, String agentId,
                             GuardEvaluation evaluation, String chatOriginJson) {
        try {
            ToolApprovalEntity entity = new ToolApprovalEntity();
            entity.setPendingId(pendingId);
            entity.setConversationId(conversationId);
            entity.setUserId(userId);
            entity.setAgentId(agentId);
            entity.setToolName(toolName);
            entity.setToolArguments(toolArguments);
            entity.setToolCallPayload(toolCallPayload);
            entity.setSiblingToolCalls(siblingToolCalls);
            entity.setStatus("PENDING");
            entity.setCreatedAt(LocalDateTime.now());
            entity.setExpireAt(LocalDateTime.now().plusMinutes(30));
            // RFC-063r §2.12: persist Memento snapshot. Null when the entry
            // path didn't supply an origin — replay falls back to ChatOrigin.EMPTY.
            entity.setChatOrigin(chatOriginJson);

            if (evaluation != null) {
                entity.setFindingsJson(serializeFindings(evaluation.findings()));
                entity.setMaxSeverity(evaluation.maxSeverity() != null ? evaluation.maxSeverity().name() : null);
                entity.setSummary(evaluation.summary());

                if (toolCallPayload != null) {
                    entity.setToolCallHash(String.valueOf(toolCallPayload.hashCode()));
                }
            }

            approvalMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to persist approval to DB: {}", e.getMessage());
        }
    }

    /**
     * RFC-063r §2.12: serialize a {@link ChatOrigin} for persistence on
     * {@code mate_tool_approval.chat_origin}. Returns null for
     * {@code ChatOrigin.EMPTY} so legacy approvals that never captured an
     * origin do not store a meaningless empty record.
     */
    private String serializeChatOrigin(ChatOrigin origin) {
        if (origin == null || origin == ChatOrigin.EMPTY) return null;
        if (origin.agentId() == null && origin.channelId() == null
                && origin.conversationId() == null && origin.workspaceId() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(origin);
        } catch (JsonProcessingException e) {
            log.warn("[ApprovalWorkflow] Failed to serialize ChatOrigin: {}", e.getMessage());
            return null;
        }
    }

    /**
     * RFC-063r §2.12: deserialize a persisted Memento back into a
     * {@link ChatOrigin}. Returns {@link ChatOrigin#EMPTY} when the column
     * is null or the payload is corrupt — the caller treats that as
     * "no channel binding" and replay proceeds with a web-style flow.
     */
    public ChatOrigin restoreChatOrigin(String json) {
        if (json == null || json.isBlank()) return ChatOrigin.EMPTY;
        try {
            ChatOrigin restored = objectMapper.readValue(json, ChatOrigin.class);
            return restored != null ? restored : ChatOrigin.EMPTY;
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to restore ChatOrigin: {} (payload-len={})",
                    e.getMessage(), json.length());
            return ChatOrigin.EMPTY;
        }
    }

    private void updateDbStatus(String pendingId, String status, String resolvedBy) {
        try {
            LambdaUpdateWrapper<ToolApprovalEntity> wrapper = new LambdaUpdateWrapper<ToolApprovalEntity>()
                    .eq(ToolApprovalEntity::getPendingId, pendingId)
                    .set(ToolApprovalEntity::getStatus, status)
                    .set(ToolApprovalEntity::getResolvedAt, LocalDateTime.now());

            if (resolvedBy != null) {
                wrapper.set(ToolApprovalEntity::getResolvedBy, resolvedBy);
            }
            approvalMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to update DB status: {}", e.getMessage());
        }
    }

    private String serializeFindings(List<GuardFinding> findings) {
        if (findings == null || findings.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(
                    findings.stream().map(GuardFinding::toMap).toList()
            );
        } catch (JsonProcessingException e) {
            log.warn("[ApprovalWorkflow] Failed to serialize findings: {}", e.getMessage());
            return null;
        }
    }
}
