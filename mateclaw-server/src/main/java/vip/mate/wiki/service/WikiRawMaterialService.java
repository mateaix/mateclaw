package vip.mate.wiki.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.HexFormat;
import java.util.List;
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
                        .eq(WikiRawMaterialEntity::getSourcePath, sourcePath));
    }

    /**
     * Import a text file discovered by a directory scan, detecting content
     * changes by hash: unchanged content (a raw with the same hash already
     * exists) is a no-op, while changed content creates a new raw and triggers
     * processing — so a modified file is re-ingested rather than silently
     * skipped. The originating path is recorded for diagnostics.
     *
     * @return {@code true} when the file was newly ingested (new or changed
     *         content), {@code false} when skipped as unchanged
     */
    public boolean ingestTextFileFromScan(Long kbId, String fileName, String absolutePath, String content) {
        String hash = computeHash(content);
        WikiRawMaterialEntity sameContent = rawMapper.selectOne(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .eq(WikiRawMaterialEntity::getContentHash, hash)
                        .last("LIMIT 1"));
        // addText dedups internally by hash, so this reuses sameContent when
        // unchanged and inserts + triggers processing when the content differs.
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
     * Import a binary file discovered by a directory scan, detecting content
     * changes by hashing the bytes: unchanged content (a raw with the same hash
     * exists) is skipped, while changed content is re-ingested via
     * {@link #addFile}. The unchanged case reads the file once; only a
     * new/changed file is read again by addFile.
     *
     * @return {@code true} when newly ingested, {@code false} when unchanged
     */
    public boolean ingestBinaryFileFromScan(Long kbId, String title, String sourceType,
                                            String absolutePath, long fileSize) {
        String hash = null;
        try {
            hash = computeHashOfBytes(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(absolutePath)));
        } catch (Exception e) {
            log.warn("[Wiki] Could not hash file for change detection: {}", e.getMessage());
        }
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
        entity.setErrorMessage(null);
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

    @Transactional
    public void updateProcessingStatus(Long id, String status, String errorMessage) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setProcessingStatus(status);
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
        entity.setErrorMessage(null);
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
                String result = documentExtractTool.extract_document_text(entity.getSourcePath(), null, null);
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
            raw.setErrorMessage(null);
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
