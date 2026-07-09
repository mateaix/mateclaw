package vip.mate.wiki.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.tool.builtin.DocumentExtractTool;
import vip.mate.tool.image.vision.ImageVisionService;
import vip.mate.tool.image.vision.VisionRequest;
import vip.mate.tool.image.vision.VisionResult;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.event.WikiProcessingEvent;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import vip.mate.wiki.dto.WikiFailureItem;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wiki 原始材料服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiRawMaterialService {

    private static final String VISION_FLAG_KEY = "wiki.ocr.enabled";

    private final WikiRawMaterialMapper rawMapper;
    private final WikiKnowledgeBaseService kbService;
    private final WikiProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentExtractTool documentExtractTool;
    /** Optional — wired by Spring; null in minimal test harnesses where the cascade path is exercised separately. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WikiPageService pageService;
    /** RFC-013：删除时级联清理 chunk */
    private final WikiChunkService chunkService;
    private final ImageVisionService imageVisionService;
    private final PdfImageExtractor pdfImageExtractor;
    private final FeatureFlagService featureFlagService;

    /**
     * RFC-012 follow-up #3：从 partial 状态触发的 reprocess 会在此 set 中打标，
     * 供 {@link vip.mate.wiki.service.WikiProcessingService#processRawMaterial(Long, boolean)}
     * 在 claim 之前消费，从而决定是否保留已生成的 exclusive page（续传语义）。
     * <p>
     * 内存态：server 重启会丢，但原 raw 的 status 已被 reprocess 改为 pending，
     * 重启后按正常 pending 流程跑（退化为「不删旧页的全量重跑」，功能不丢失只是
     * 没有走 route 的 "update" 识别路径）。
     */
    private final Set<Long> partialResumeIds = ConcurrentHashMap.newKeySet();

    public List<WikiRawMaterialEntity> listByKbId(Long kbId) {
        List<WikiRawMaterialEntity> list = rawMapper.selectList(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .orderByDesc(WikiRawMaterialEntity::getCreateTime));
        // 不返回大文本字段
        list.forEach(r -> {
            r.setOriginalContent(null);
            r.setExtractedText(null);
        });
        return list;
    }

    public WikiRawMaterialEntity getById(Long id) {
        return rawMapper.selectById(id);
    }

    public WikiRawMaterialEntity findBySourcePath(Long kbId, String sourcePath) {
        return rawMapper.selectOne(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .eq(WikiRawMaterialEntity::getSourcePath, sourcePath)
                        .last("LIMIT 1"));
    }

    /**
     * Import a text file discovered by a directory scan.
     *
     * <p>Dedup strategy (in priority order):
     * <ol>
     *   <li><b>Source path</b>: if a raw for {@code (kbId, absolutePath)} already exists,
     *       compare hashes. Unchanged → skip; changed → update in-place so the same raw
     *       row is reused rather than creating a duplicate row for the same file path.</li>
     *   <li><b>Content hash</b>: if a different file with identical content already exists
     *       in the KB, the existing raw is reused (copy/duplicate scenario).</li>
     * </ol>
     *
     * @return {@code true} when the file was newly ingested or updated (new or changed
     *         content), {@code false} when skipped as unchanged
     */
    public boolean ingestTextFileFromScan(Long kbId, String fileName, String absolutePath, String content) {
        String hash = computeHash(content);

        // Primary dedup: same source path already in this KB
        WikiRawMaterialEntity byPath = findBySourcePath(kbId, absolutePath);
        if (byPath != null) {
            if (hash != null && hash.equals(byPath.getContentHash())) {
                return false; // unchanged
            }
            // File changed: update existing raw in-place to avoid a duplicate row
            updateTextContentFromScan(byPath.getId(), fileName, content, absolutePath);
            return true;
        }

        // Secondary dedup: different path but identical content (copy of an existing file)
        WikiRawMaterialEntity sameContent = rawMapper.selectOne(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .eq(WikiRawMaterialEntity::getContentHash, hash)
                        .last("LIMIT 1"));
        WikiRawMaterialEntity raw = addText(kbId, fileName, content);
        // Only stamp the path on a genuinely new raw. When the content matched
        // an existing raw (possibly a different file with identical content),
        // overwriting its sourcePath would corrupt that raw's provenance.
        if (raw != null && sameContent == null) {
            updateSourcePath(raw.getId(), absolutePath);
        }
        return sameContent == null;
    }

    /**
     * Update an existing text raw in-place when a directory-scanned file has changed content.
     * Resets processing state and triggers re-processing so new pages are generated from the
     * updated content without leaving a stale duplicate row alongside the new one.
     *
     * <p>No {@code @Transactional}: this runs a single atomic {@code updateById} and is only
     * reached via self-invocation from {@code ingestTextFileFromScan}, where the annotation
     * would be bypassed by the proxy anyway. The re-processing event is published after the
     * row is persisted so the async listener reads committed state.
     */
    public void updateTextContentFromScan(Long rawId, String title, String content, String sourcePath) {
        WikiRawMaterialEntity entity = rawMapper.selectById(rawId);
        if (entity == null) return;
        String hash = computeHash(content);
        entity.setTitle(title);
        entity.setOriginalContent(content);
        entity.setContentHash(hash);
        entity.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        entity.setSourcePath(sourcePath);
        entity.setProcessingStatus("pending");
        clearFailureState(entity);
        entity.setExtractedText(null);
        entity.setProgressPhase(null);
        entity.setProgressDone(0);
        entity.setProgressTotal(0);
        rawMapper.updateById(entity);
        log.info("[Wiki] Text raw updated in-place from scan: id={}, kbId={}, newHash={}", rawId, entity.getKbId(), hash);
        if (properties.isAutoProcessOnUpload()) {
            eventPublisher.publishEvent(new WikiProcessingEvent(this, rawId, entity.getKbId()));
        }
    }

    /**
     * Import a binary file discovered by a directory scan.
     *
     * <p>Dedup strategy (in priority order):
     * <ol>
     *   <li><b>Source path</b>: if a raw for {@code (kbId, absolutePath)} already exists,
     *       compare hashes. Unchanged → skip; changed → update in-place.</li>
     *   <li><b>Content hash</b>: if a different file with identical bytes already exists,
     *       the existing raw is reused.</li>
     * </ol>
     *
     * @return {@code true} when newly ingested or updated, {@code false} when unchanged
     */
    public boolean ingestBinaryFileFromScan(Long kbId, String title, String sourceType,
                                            String absolutePath, long fileSize) {
        String hash = null;
        try {
            hash = computeHashOfBytes(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(absolutePath)));
        } catch (Exception e) {
            log.warn("[Wiki] Could not hash file for change detection: {}", e.getMessage());
        }

        // Primary dedup: same source path already in this KB
        WikiRawMaterialEntity byPath = findBySourcePath(kbId, absolutePath);
        if (byPath != null) {
            if (hash != null && hash.equals(byPath.getContentHash())) {
                return false; // unchanged
            }
            // File changed: update existing raw in-place
            updateBinaryFileFromScan(byPath.getId(), absolutePath, fileSize, hash);
            return true;
        }

        // Secondary dedup: same content hash → copy at a different path
        if (hash != null) {
            WikiRawMaterialEntity sameContent = rawMapper.selectOne(
                    new LambdaQueryWrapper<WikiRawMaterialEntity>()
                            .eq(WikiRawMaterialEntity::getKbId, kbId)
                            .eq(WikiRawMaterialEntity::getContentHash, hash)
                            .last("LIMIT 1"));
            if (sameContent != null) {
                return false; // unchanged — addFile not called, avoids a second read
            }
        }
        // Pass the hash we already computed so addFile does not re-read the file.
        addFile(kbId, title, sourceType, null, absolutePath, fileSize, hash);
        return true;
    }

    /**
     * Update an existing binary raw in-place when a directory-scanned file has changed content.
     *
     * <p>No {@code @Transactional}: single atomic {@code updateById} reached only via
     * self-invocation from {@code ingestBinaryFileFromScan} (proxy bypassed); the re-processing
     * event is published after the row is persisted.
     */
    public void updateBinaryFileFromScan(Long rawId, String sourcePath, long fileSize, String hash) {
        WikiRawMaterialEntity entity = rawMapper.selectById(rawId);
        if (entity == null) return;
        entity.setContentHash(hash);
        entity.setFileSize(fileSize);
        entity.setSourcePath(sourcePath);
        entity.setProcessingStatus("pending");
        clearFailureState(entity);
        entity.setExtractedText(null);
        entity.setProgressPhase(null);
        entity.setProgressDone(0);
        entity.setProgressTotal(0);
        rawMapper.updateById(entity);
        log.info("[Wiki] Binary raw updated in-place from scan: id={}, kbId={}, newHash={}", rawId, entity.getKbId(), hash);
        if (properties.isAutoProcessOnUpload()) {
            eventPublisher.publishEvent(new WikiProcessingEvent(this, rawId, entity.getKbId()));
        }
    }

    /**
     * Record the originating file path on a raw material via a partial update,
     * so a later directory re-scan can dedup it by source path. Used for
     * text-file imports, which otherwise carry no path.
     */
    public void updateSourcePath(Long rawId, String sourcePath) {
        if (rawId == null) {
            return;
        }
        rawMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiRawMaterialEntity>()
                .eq(WikiRawMaterialEntity::getId, rawId)
                .set(WikiRawMaterialEntity::getSourcePath, sourcePath));
    }

    /**
     * 改分组（含清空为 null）。必须用 LambdaUpdateWrapper.set 显式赋值，
     * 否则 updateById 在默认 NOT_NULL 策略下无法把 groupId 置空。
     */
    public void updateGroup(Long rawId, Long groupId) {
        if (rawId == null) {
            return;
        }
        rawMapper.update(null, new LambdaUpdateWrapper<WikiRawMaterialEntity>()
                .eq(WikiRawMaterialEntity::getId, rawId)
                .set(WikiRawMaterialEntity::getGroupId, groupId));
    }

    /**
     * 在给定的 rawIds 里筛出真正属于该 kbId 的那些，供批量改分组一类接口拒绝跨库越权
     * 的 rawId。单条 SQL 查询完成，避免拉全表再内存 filter。
     */
    public List<Long> filterOwnedIds(Long kbId, Collection<Long> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        return rawMapper.selectList(
                        new LambdaQueryWrapper<WikiRawMaterialEntity>()
                                .eq(WikiRawMaterialEntity::getKbId, kbId)
                                .in(WikiRawMaterialEntity::getId, rawIds)
                                .select(WikiRawMaterialEntity::getId))
                .stream()
                .map(WikiRawMaterialEntity::getId)
                .toList();
    }

    public int updateGroupBatch(Collection<Long> rawIds, Long groupId) {
        if (rawIds == null || rawIds.isEmpty()) {
            return 0;
        }
        return rawMapper.update(null, new LambdaUpdateWrapper<WikiRawMaterialEntity>()
                .in(WikiRawMaterialEntity::getId, rawIds)
                .set(WikiRawMaterialEntity::getGroupId, groupId));
    }

    /** 分组删除时批量改挂：把某分组下所有 raw 的 groupId 从 fromGroupId 改成 toGroupId（可为 null）。 */
    public int reassignGroup(Long kbId, Long fromGroupId, Long toGroupId) {
        return rawMapper.update(null, new LambdaUpdateWrapper<WikiRawMaterialEntity>()
                .eq(WikiRawMaterialEntity::getKbId, kbId)
                .eq(WikiRawMaterialEntity::getGroupId, fromGroupId)
                .set(WikiRawMaterialEntity::getGroupId, toGroupId));
    }

    /** 按 groupId 聚合统计某知识库下各分组的 raw 数量，一次查询避免 N+1。 */
    public Map<Long, Long> countRawByGroup(Long kbId) {
        List<Map<String, Object>> rows = rawMapper.selectMaps(
                new QueryWrapper<WikiRawMaterialEntity>()
                        .select("group_id", "COUNT(*) as cnt")
                        .eq("kb_id", kbId)
                        .isNotNull("group_id")
                        .groupBy("group_id"));
        java.util.Map<Long, Long> result = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            Object groupIdObj = row.get("group_id");
            Object cntObj = row.get("cnt");
            if (groupIdObj != null && cntObj != null) {
                result.put(((Number) groupIdObj).longValue(), ((Number) cntObj).longValue());
            }
        }
        return result;
    }

    public List<WikiRawMaterialEntity> listPending(Long kbId) {
        return rawMapper.selectList(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .eq(WikiRawMaterialEntity::getProcessingStatus, "pending"));
    }

    /**
     * 添加文本类型的原始材料
     */
    @Transactional
    public WikiRawMaterialEntity addText(Long kbId, String title, String content) {
        String hash = computeHash(content);

        // Dedup: reuse any existing row with the same hash in this KB (any status)
        WikiRawMaterialEntity existing = rawMapper.selectOne(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .eq(WikiRawMaterialEntity::getContentHash, hash)
                        .last("LIMIT 1"));
        if (existing != null) {
            return handleDuplicate(existing);
        }

        WikiRawMaterialEntity entity = new WikiRawMaterialEntity();
        entity.setKbId(kbId);
        entity.setTitle(title);
        entity.setSourceType("text");
        entity.setOriginalContent(content);
        entity.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        entity.setContentHash(hash);
        entity.setProcessingStatus("pending");
        rawMapper.insert(entity);

        kbService.incrementRawCount(kbId);

        if (properties.isAutoProcessOnUpload()) {
            eventPublisher.publishEvent(new WikiProcessingEvent(this, entity.getId(), kbId));
        }

        log.info("[Wiki] Raw material added: id={}, kbId={}, title={}", entity.getId(), kbId, title);
        return entity;
    }

    /**
     * Create a raw material record for agent-authored content WITHOUT triggering
     * the LLM ingest pipeline. Used by {@code wiki_create_page} so an agent-written
     * page gets a lineage anchor — it appears in the Raw Material panel, hosts
     * chunks, supports the download button (text raws are served from the
     * {@code original_content} column by the download endpoint), and can be
     * reprocessed later — without re-running LLM page generation, since the
     * agent has already produced the final page content.
     *
     * <p>The raw is left in {@code processing} status; the caller flips it to
     * {@code completed} via {@link WikiProcessingService#linkAgentPageToRaw}
     * once chunks + citations have landed. Dedup by content hash reuses an
     * existing row when the agent writes the same content again (idempotent),
     * mirroring {@link #addText}'s dedup semantics.
     *
     * @return the raw material entity (newly inserted or an existing same-content row)
     */
    @Transactional
    public WikiRawMaterialEntity addAgentAuthored(Long kbId, String title, String content) {
        String hash = computeHash(content);

        // Dedup: reuse any existing row with the same hash in this KB (any status).
        // An agent often re-writes the same report title in a conversation; stacking
        // duplicate raws would pollute the Raw Material panel.
        WikiRawMaterialEntity existing = rawMapper.selectOne(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .eq(WikiRawMaterialEntity::getContentHash, hash)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        WikiRawMaterialEntity entity = new WikiRawMaterialEntity();
        entity.setKbId(kbId);
        entity.setTitle(title);
        entity.setSourceType("text");
        entity.setOriginalContent(content);
        entity.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        entity.setContentHash(hash);
        // 'processing' rather than 'pending': this raw is claimed by the agent
        // tool's own synchronous post-processing (linkAgentPageToRaw), so it
        // must NOT be picked up by the async ingest listener (which would
        // re-run LLM page generation). The caller flips it to 'completed'.
        entity.setProcessingStatus("processing");
        rawMapper.insert(entity);
        kbService.incrementRawCount(kbId);

        log.info("[Wiki] Agent-authored raw material added: id={}, kbId={}, title={}", entity.getId(), kbId, title);
        return entity;
    }

    /**
     * Adds a file-type raw material (PDF / DOCX / image / ...).
     *
     * <p>Backwards-compatible overload that omits the MIME type. Callers
     * with the upload Content-Type in hand should prefer the four-argument
     * variant — image-routing in particular needs an authoritative MIME so
     * downstream vision providers know what they are decoding.
     */
    @Transactional
    public WikiRawMaterialEntity addFile(Long kbId, String title, String sourceType,
                                          String sourcePath, long fileSize) {
        return addFile(kbId, title, sourceType, null, sourcePath, fileSize);
    }

    /**
     * Adds a file-type raw material with explicit MIME type.
     *
     * @param mimeType Content-Type string from the upload (e.g. {@code image/png});
     *                 may be null if unknown
     */
    @Transactional
    public WikiRawMaterialEntity addFile(Long kbId, String title, String sourceType,
                                          String mimeType, String sourcePath, long fileSize) {
        return addFile(kbId, title, sourceType, mimeType, sourcePath, fileSize, null);
    }

    /**
     * As {@link #addFile(Long, String, String, String, String, long)}, but with
     * an optional precomputed content hash so a caller that already read the
     * file (e.g. the directory scan's change detection) does not pay a second
     * full-file read to dedup.
     */
    @Transactional
    public WikiRawMaterialEntity addFile(Long kbId, String title, String sourceType,
                                          String mimeType, String sourcePath, long fileSize,
                                          String precomputedHash) {
        WikiRawMaterialEntity entity = new WikiRawMaterialEntity();
        entity.setKbId(kbId);
        entity.setTitle(title);
        entity.setSourceType(sourceType);
        entity.setMimeType(capMimeType(mimeType));
        entity.setSourcePath(sourcePath);
        entity.setFileSize(fileSize);
        entity.setProcessingStatus("pending");

        // Compute hash of original upload bytes (for dedup). RFC-051: hash raw bytes
        // directly — the previous `new String(bytes, UTF_8)` round-trip produced unstable
        // hashes for binary files (PDF/Office) because invalid UTF-8 sequences become
        // replacement characters, collapsing distinct files into the same hash.
        if (precomputedHash != null) {
            entity.setContentHash(precomputedHash);
        } else {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(sourcePath));
                entity.setContentHash(computeHashOfBytes(bytes));
            } catch (Exception e) {
                log.warn("[Wiki] Could not compute file hash for dedup: {}", e.getMessage());
            }
        }

        // Dedup: reuse any existing row with the same hash in this KB (any status)
        if (entity.getContentHash() != null) {
            WikiRawMaterialEntity existing = rawMapper.selectOne(
                    new LambdaQueryWrapper<WikiRawMaterialEntity>()
                            .eq(WikiRawMaterialEntity::getKbId, kbId)
                            .eq(WikiRawMaterialEntity::getContentHash, entity.getContentHash())
                            .last("LIMIT 1"));
            if (existing != null) {
                // Clean up the newly uploaded file — we won't use it
                cleanupFile(sourcePath);
                return handleDuplicate(existing);
            }
        }

        rawMapper.insert(entity);
        kbService.incrementRawCount(kbId);

        if (properties.isAutoProcessOnUpload()) {
            eventPublisher.publishEvent(new WikiProcessingEvent(this, entity.getId(), kbId));
        }

        log.info("[Wiki] Raw file added: id={}, kbId={}, type={}", entity.getId(), kbId, sourceType);
        return entity;
    }

    /**
     * Defensive cap on the persisted Content-Type so a long upload header
     * never blocks the insert. The column itself is wide (V84 → VARCHAR(255))
     * but capping at the service layer keeps the schema and the writer in
     * lockstep — we'd rather drop the rare overlong header (chrome-derived
     * Content-Type with charset / boundary parameters) than fail the upload.
     */
    private static final int MIME_TYPE_MAX_CHARS = 255;

    private static String capMimeType(String mimeType) {
        if (mimeType == null) return null;
        if (mimeType.length() <= MIME_TYPE_MAX_CHARS) return mimeType;
        log.warn("[Wiki] Truncating Content-Type ({} chars) to fit storage column: {}",
                mimeType.length(), mimeType.substring(0, 60) + "…");
        return mimeType.substring(0, MIME_TYPE_MAX_CHARS);
    }

    /**
     * CAS 式抢占：仅当当前状态为 pending 时才更新为 processing。
     *
     * @return true 表示抢占成功，false 表示已被其他线程处理
     */
    @Transactional
    public boolean claimForProcessing(Long id) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null || !"pending".equals(entity.getProcessingStatus())) {
            return false;
        }
        entity.setProcessingStatus("processing");
        clearFailureState(entity);
        // RFC-012 M2 v2 UI：新一轮处理开始，清掉上次遗留的进度显示
        entity.setProgressPhase(null);
        entity.setProgressTotal(0);
        entity.setProgressDone(0);
        // Fresh start clears any stale cancel request from a previous run.
        entity.setCancelRequested(Boolean.FALSE);
        rawMapper.updateById(entity);
        return true;
    }

    /**
     * Mark a raw material for cancellation. Only valid while it is currently
     * being processed; for any other status this is a no-op so the call is
     * idempotent and safe to retry from the UI.
     *
     * @return {@code true} if the flag was set, {@code false} otherwise
     */
    @Transactional
    public boolean requestCancel(Long id) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) {
            return false;
        }
        if (!"processing".equals(entity.getProcessingStatus())) {
            return false;
        }
        if (Boolean.TRUE.equals(entity.getCancelRequested())) {
            // Already requested; treat as success without redundant write.
            return true;
        }
        entity.setCancelRequested(Boolean.TRUE);
        rawMapper.updateById(entity);
        return true;
    }

    /**
     * Returns {@code true} if the user has asked to cancel this raw material's
     * current processing run. Used by abort checkpoints inside the processing
     * pipeline to bail out early.
     */
    public boolean isCancelRequested(Long id) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        return entity != null && Boolean.TRUE.equals(entity.getCancelRequested());
    }

    /**
     * RFC-012 M2 v2 UI：更新 wiki 两阶段消化的进度字段。
     * <p>
     * 在 {@code WikiProcessingService.processChunkTwoPhase} 的四个节点被调用：
     * <ul>
     *   <li>方法开头 → {@code phase="route"}, done=0, total=0（进度条显示 indeterminate）</li>
     *   <li>route 返回后 → {@code phase="phase-b"}, done=0, total=N+M（切换到 determinate）</li>
     *   <li>每页 create/merge 成功 → done +1</li>
     *   <li>方法结束 → {@code phase="done"}（UI 会随 status 变成 completed 自动隐藏进度条）</li>
     * </ul>
     */
    @Transactional
    public void updateProgress(Long id, String phase, int done, int total) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setProgressPhase(phase);
        entity.setProgressDone(done);
        entity.setProgressTotal(total);
        rawMapper.updateById(entity);
    }

    public void updateProcessingStatus(Long id, String status, String errorMessage) {
        updateProcessingStatus(id, status, null, errorMessage);
    }

    /**
     * Reset all failure/warning surfacing fields to a clean slate for a fresh
     * run. Required because {@code errorCode}/{@code errorMessage}/{@code warning*}
     * all carry {@code FieldStrategy.ALWAYS}: a row loaded then re-saved would
     * otherwise re-persist its stale values.
     */
    private static void clearFailureState(WikiRawMaterialEntity e) {
        e.setErrorCode(null);
        e.setErrorMessage(null);
        e.setWarningCode(null);
        e.setWarningMessage(null);
    }

    /**
     * Record a non-blocking warning on a material that finished processing but
     * had an async sub-step (embedding / entity extraction) fail. Does not touch
     * {@code processingStatus} — the material is still usable, just degraded.
     */
    @Transactional
    public void recordWarning(Long id, String warningCode, String warningMessage) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setWarningCode(warningCode);
        entity.setWarningMessage(warningMessage);
        rawMapper.updateById(entity);
    }

    /** Cross-KB count of materials needing operator attention (failed/partial/degraded). */
    public long countFailures() {
        return rawMapper.countFailures();
    }

    /** Cross-KB list of materials needing operator attention, newest first (capped). */
    public List<WikiFailureItem> listFailures(int limit) {
        return rawMapper.listFailures(Math.max(1, Math.min(limit, 500)));
    }

    /**
     * Terminal/intermediate status transition that also records a structured
     * {@code errorCode} (see {@code WikiProcessingService#classifyErrorCode}).
     * Both error fields carry {@link com.baomidou.mybatisplus.annotation.FieldStrategy#ALWAYS}
     * on the entity, so a success transition with {@code null} code/message
     * clears any stale failure left from a prior run.
     */
    @Transactional
    public void updateProcessingStatus(Long id, String status, String errorCode, String errorMessage) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setProcessingStatus(status);
        entity.setErrorCode(errorCode);
        entity.setErrorMessage(errorMessage);
        if ("completed".equals(status)) {
            entity.setLastProcessedAt(java.time.LocalDateTime.now());
        }
        // Cancellation flag is only meaningful while a row is being processed.
        // Any transition out of 'processing' clears it so the field reflects
        // an idle row's true state and the next reprocess starts clean.
        if (!"processing".equals(status)) {
            entity.setCancelRequested(Boolean.FALSE);
        }
        rawMapper.updateById(entity);
    }

    /**
     * Cache the extracted text for a raw material.
     * <p>
     * RFC-051: this method no longer touches {@code contentHash}. The previous
     * behavior overwrote the original-upload hash with an extracted-text hash,
     * which broke upload dedup (re-uploading the same file would compute a hash
     * over raw bytes but find a row whose hash had been replaced with extracted
     * text). The {@code contentHash} field is now an immutable identity for the
     * uploaded artifact; downstream short-circuiting uses {@code lastProcessedHash}.
     */
    @Transactional
    public void updateExtractedText(Long id, String extractedText) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setExtractedText(extractedText);
        rawMapper.updateById(entity);
    }

    /**
     * 记录本次成功处理时的 content_hash（RFC-012 Change 5 的短路依据）。
     */
    @Transactional
    public void setLastProcessedHash(Long id, String hash) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setLastProcessedHash(hash);
        rawMapper.updateById(entity);
    }

    /**
     * 重新处理：重置状态为 pending 并发布事件。
     * <p>
     * 如果之前状态是 {@code partial}，把 rawId 加入 {@link #partialResumeIds}，
     * 下游的 WikiProcessingService 会据此决定是否保留已生成的 exclusive page（续传语义）。
     */
    @Transactional
    public void reprocess(Long id) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Raw material not found: " + id);
        }
        boolean wasPartial = "partial".equals(entity.getProcessingStatus());
        entity.setProcessingStatus("pending");
        clearFailureState(entity);
        rawMapper.updateById(entity);

        if (wasPartial) {
            partialResumeIds.add(id);
            log.info("[Wiki] Raw material queued for PARTIAL RESUME: id={} (existing pages will be kept)", id);
        } else {
            log.info("[Wiki] Raw material queued for reprocessing: id={}", id);
        }
        eventPublisher.publishEvent(new WikiProcessingEvent(this, entity.getId(), entity.getKbId()));
    }

    /**
     * 消费 partial resume 标记：若存在则返回 true 并从 set 中移除（一次性）。
     * <p>
     * 必须在 {@link #claimForProcessing(Long)} 之前调用：claim 会把 status 改成 processing，
     * 此时已无法区分 raw 原本是从 partial 还是从 failed/pending 过来的。
     */
    public boolean consumePartialResumeFlag(Long id) {
        return partialResumeIds.remove(id);
    }

    @Transactional
    public void delete(Long id) {
        // Load the entity first so we know which KB the cascade lives in.
        // Once the raw row is gone we'd lose kb_id and couldn't run the
        // page cleanup; do it before the deleteById.
        WikiRawMaterialEntity entity = rawMapper.selectById(id);

        // Cascade-delete pages this raw was the sole source of, and strip
        // the raw_id reference from multi-source pages — same semantics
        // reprocess uses (WikiProcessingService line ~257). Without this,
        // pages survive the raw delete and become orphans: search keeps
        // returning them, the page list is polluted, citations dangle.
        if (entity != null && entity.getKbId() != null && pageService != null) {
            try {
                int cleaned = pageService.deleteExclusiveBySourceRawId(entity.getKbId(), id);
                if (cleaned > 0) {
                    log.info("[Wiki] Cascade-deleted {} exclusive page(s) for raw={}", cleaned, id);
                }
            } catch (Exception e) {
                log.warn("[Wiki] Failed to cascade-delete pages for raw={}: {}", id, e.getMessage());
            }
        }

        rawMapper.deleteById(id);

        // Cascade-clean chunks so semantic search doesn't hit orphan rows.
        try {
            if (chunkService != null) {
                chunkService.deleteByRawId(id);
            }
        } catch (Exception e) {
            log.warn("[Wiki] Failed to cascade-delete chunks for raw={}: {}", id, e.getMessage());
        }

        // Source file last. cleanupFile is sandboxed to the upload dir, so:
        //   - uploaded raws (server-managed copy under uploadDir) are removed —
        //     each upload has a timestamp-prefixed unique name, no other row
        //     references it, leaving it would just accumulate disk garbage.
        //   - directory-scanned raws (sourcePath points at the user's own file
        //     outside uploadDir) are left untouched — the scanner references
        //     the original in place; the user's file is theirs to keep.
        // Failure here is soft-logged and non-blocking — operator can run a
        // sweep later if disk usage matters more than the delete RTT.
        if (entity != null) {
            cleanupFile(entity.getSourcePath());
        }
    }

    /**
     * 获取可用文本内容
     * <p>
     * 优先级：已缓存的 extractedText → 原始文本 → 调用 DocumentExtractTool 提取二进制文件
     */
    public String getTextContent(WikiRawMaterialEntity entity) {
        // 已有缓存的提取文本
        if (entity.getExtractedText() != null && !entity.getExtractedText().isBlank()) {
            return entity.getExtractedText();
        }
        // 文本类型直接返回原始内容
        if ("text".equals(entity.getSourceType())) {
            return entity.getOriginalContent();
        }
        // Image source: route through the vision-in pipeline. Failures (feature
        // flag off, no provider configured, all providers failed) degrade to an
        // empty caption rather than blocking the upload — the user keeps the
        // raw row and can retry once vision is configured.
        if ("image".equals(entity.getSourceType())) {
            return extractTextFromImage(entity);
        }
        // 二进制文件：调用 DocumentExtractTool 提取
        if (entity.getSourcePath() != null && !entity.getSourcePath().isBlank()) {
            try {
                // Server-managed path (staged under the wiki upload dir): use the
                // sandbox-exempt entry so the workspace boundary guard does not
                // reject the upload dir as "outside workspace boundary".
                String result = documentExtractTool.extractTrustedDocument(entity.getSourcePath(), null);
                JSONObject json = JSONUtil.parseObj(result);
                if (json.getBool("success", false)) {
                    String text = json.getStr("text");
                    if (text != null && !text.isBlank()) {
                        boolean truncated = json.getBool("truncated", false);
                        // Append inline-image captions for PDFs so chunk-level search
                        // hits chart/diagram contents that the text extractor missed.
                        // Failures are non-fatal: the body text is still returned.
                        String enriched = appendPdfImageCaptions(entity, text);

                        if (truncated) {
                            // Truncated results are not cached so we don't lose the tail
                            // permanently — we still return the text for chunking use.
                            log.warn("[Wiki] Extracted text truncated at {} chars for: {} (full document may be larger)",
                                    text.length(), entity.getSourcePath());
                        } else {
                            updateExtractedText(entity.getId(), enriched);
                        }
                        log.info("[Wiki] Extracted text from {}: {} chars (text) → {} chars (enriched), method={}, truncated={}, cached={}",
                                entity.getSourcePath(), text.length(), enriched.length(),
                                json.getStr("method"), truncated, !truncated);
                        return enriched;
                    }
                }
                log.warn("[Wiki] Document extraction returned no text for: {}", entity.getSourcePath());
            } catch (Exception e) {
                log.error("[Wiki] Document extraction failed for {}: {}", entity.getSourcePath(), e.getMessage());
            }
        }
        return entity.getOriginalContent();
    }

    /**
     * For PDF raw materials, walks the inline images and appends a section of
     * {@code [图 P{n}#{m}]: <caption>} markers so downstream chunking and
     * search can index image contents.
     *
     * <p>When {@link #VISION_FLAG_KEY} is off, returns the body unchanged
     * without re-parsing the PDF — the operator's "off = ignore images"
     * intent. Flipping the flag on later requires a manual reprocess for
     * existing rows to pick up captions.
     */
    private String appendPdfImageCaptions(WikiRawMaterialEntity entity, String body) {
        if (!"pdf".equals(entity.getSourceType()) || pdfImageExtractor == null) {
            return body;
        }
        if (featureFlagService == null || !featureFlagService.isEnabled(VISION_FLAG_KEY)) {
            return body;
        }

        java.nio.file.Path pdfPath = java.nio.file.Paths.get(entity.getSourcePath());
        try {
            List<String> snippets = pdfImageExtractor.captionInlineImages(pdfPath);
            if (snippets.isEmpty()) {
                return body;
            }
            StringBuilder sb = new StringBuilder(body);
            sb.append("\n\n--- Inline images ---\n");
            for (String snippet : snippets) {
                sb.append(snippet).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Wiki] PDF inline-image captioning failed for id={}: {}",
                    entity.getId(), e.getMessage());
            return body;
        }
    }

    /**
     * Routes an image-typed raw material through the vision-in pipeline,
     * caches the resulting caption into {@code extracted_text} so the next
     * call short-circuits, and degrades gracefully on failure.
     *
     * <p>Failure modes (feature flag off, no provider, all providers failed,
     * IO errors reading the image bytes) are intentionally swallowed and
     * surfaced as the empty string. The upload still succeeded; the raw
     * row remains and downstream code is expected to tolerate "no
     * extracted text yet" — calling this method again later (e.g. after
     * an operator enables the feature flag) re-runs the pipeline.
     */
    private String extractTextFromImage(WikiRawMaterialEntity entity) {
        if (entity.getSourcePath() == null || entity.getSourcePath().isBlank()) {
            log.warn("[Wiki] Image raw material missing sourcePath: id={}", entity.getId());
            return "";
        }
        if (featureFlagService == null || !featureFlagService.isEnabled(VISION_FLAG_KEY)) {
            log.info("[Wiki] Image raw id={} skipped: {} is disabled",
                    entity.getId(), VISION_FLAG_KEY);
            return "";
        }
        byte[] imageBytes;
        try {
            imageBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(entity.getSourcePath()));
        } catch (Exception e) {
            log.error("[Wiki] Failed to read image bytes for id={}: {}", entity.getId(), e.getMessage());
            return "";
        }

        VisionRequest request = VisionRequest.builder()
                .imageBytes(imageBytes)
                .mimeType(resolveMimeType(entity))
                .build();
        VisionResult result;
        try {
            result = imageVisionService.caption(request);
        } catch (Exception e) {
            log.warn("[Wiki] Vision pipeline failed for raw id={}: {}", entity.getId(), e.getMessage());
            return "";
        }

        StringBuilder text = new StringBuilder(result.getCaption() == null ? "" : result.getCaption());
        if (result.getVisibleText() != null && !result.getVisibleText().isBlank()) {
            text.append("\n\n--- Visible text ---\n").append(result.getVisibleText());
        }
        String combined = text.toString();
        updateExtractedText(entity.getId(), combined);
        log.info("[Wiki] Image vision captioned raw id={} provider={} model={} chars={}",
                entity.getId(), result.getProviderId(), result.getModel(), combined.length());
        return combined;
    }

    /** Best-effort MIME resolution: prefer the persisted column, fall back to file extension. */
    private static String resolveMimeType(WikiRawMaterialEntity entity) {
        if (entity.getMimeType() != null && !entity.getMimeType().isBlank()) {
            return entity.getMimeType();
        }
        String path = entity.getSourcePath() == null ? "" : entity.getSourcePath().toLowerCase();
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = path.substring(dot + 1);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "tiff", "tif" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    /**
     * Recover raw materials stuck in 'processing' status after a server restart.
     * Resets them to 'pending', clears stale progress fields, and optionally
     * fires processing events so they get picked up automatically.
     *
     * @return number of recovered rows
     */
    @Transactional
    public int recoverStuckRawMaterialsOnStartup() {
        List<WikiRawMaterialEntity> stuck = rawMapper.selectList(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getProcessingStatus, "processing"));
        if (stuck.isEmpty()) return 0;

        for (WikiRawMaterialEntity raw : stuck) {
            raw.setProcessingStatus("pending");
            raw.setProgressPhase(null);
            raw.setProgressTotal(0);
            raw.setProgressDone(0);
            clearFailureState(raw);
            rawMapper.updateById(raw);

            if (properties.isAutoProcessOnUpload()) {
                eventPublisher.publishEvent(new WikiProcessingEvent(this, raw.getId(), raw.getKbId()));
            }
            log.info("[Wiki] Recovered stuck processing raw material: id={}, kbId={}", raw.getId(), raw.getKbId());
        }
        return stuck.size();
    }

    /**
     * Handle a duplicate upload: decide what to do based on the existing row's status.
     * - completed → return as-is (no reprocessing needed)
     * - partial / failed → reprocess (partial enters resume branch)
     * - pending / processing → return as-is (already queued or running)
     */
    private WikiRawMaterialEntity handleDuplicate(WikiRawMaterialEntity existing) {
        String prevStatus = existing.getProcessingStatus();
        log.info("[Wiki] Duplicate file detected, reusing id={}, prevStatus={}", existing.getId(), prevStatus);

        if ("partial".equals(prevStatus) || "failed".equals(prevStatus)) {
            reprocess(existing.getId());
        }
        // completed / pending / processing → return as-is
        return existing;
    }

    /**
     * Best-effort delete of a raw material's source file on disk. Used both
     * when a fresh upload turns out to be a duplicate (the new file is
     * redundant) and when a raw material row is deleted (its source file
     * becomes a disk orphan with no DB pointer to it).
     * <p>
     * Sandboxed to {@link WikiProperties#getUploadDir()}: only deletes files
     * that live under the configured upload directory — i.e. files this
     * service is responsible for (uploaded raws + KB pipeline outputs).
     * Files outside the upload tree are left alone, because the directory
     * scanner imports raws by referencing the user's local file in place
     * (no copy); deleting them would wipe the user's original document, not
     * just our internal cache. See {@code WikiDirectoryScanService}.
     * <p>
     * Idempotent — silently succeeds when the path is null, the file is
     * already gone, or the path is outside the upload sandbox.
     */
    private void cleanupFile(String path) {
        if (path == null || path.isBlank()) return;
        try {
            java.nio.file.Path target = java.nio.file.Paths.get(path).toAbsolutePath().normalize();
            java.nio.file.Path uploadRoot = java.nio.file.Paths.get(properties.getUploadDir())
                    .toAbsolutePath().normalize();
            if (!target.startsWith(uploadRoot)) {
                // User-owned file (imported by directory scan in place). The DB row is
                // gone but the file on the user's disk must stay — that's their data,
                // not ours.
                log.info("[Wiki] Skip file delete (outside upload dir, user-owned): path={} uploadDir={}",
                        target, uploadRoot);
                return;
            }
            java.nio.file.Files.deleteIfExists(target);
        } catch (Exception e) {
            log.warn("[Wiki] Failed to clean up upload file {}: {}", path, e.getMessage());
        }
    }

    private String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("[Wiki] Failed to compute content hash: {}", e.getMessage());
            return null;
        }
    }

    /**
     * SHA-256 over raw bytes. Used for file uploads so that PDF/Office binaries
     * produce a stable identity hash regardless of UTF-8 round-tripping.
     */
    private String computeHashOfBytes(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("[Wiki] Failed to compute byte hash: {}", e.getMessage());
            return null;
        }
    }
}
