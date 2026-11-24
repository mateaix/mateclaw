package vip.mate.wiki.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.service.WikiProcessingService;

import java.util.concurrent.Semaphore;

/**
 * Wiki 处理事件监听器
 * <p>
 * RFC-012 Change 1：
 * <ul>
 *   <li>事件到达后立即提交到 wiki 自己的虚拟线程执行器，不占用全局 @Async 线程池（max=16）；</li>
 *   <li>通过信号量限制同时处理的材料数 ({@link WikiProperties#getMaxParallelRawMaterials()})，
 *       避免大批量上传瞬间触发 LLM 限流。</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class WikiProcessingListener {

    private final WikiProcessingService processingService;
    private final Semaphore rawMaterialSemaphore;

    public WikiProcessingListener(WikiProcessingService processingService, WikiProperties properties) {
        this.processingService = processingService;
        int parallel = Math.max(1, properties.getMaxParallelRawMaterials());
        this.rawMaterialSemaphore = new Semaphore(parallel);
        log.info("[Wiki] Listener initialized with rawMaterialSemaphore permits={}", parallel);
    }

    // Upload/reprocess publishes inside the material transaction. A worker
    // started before commit can miss the row or see its previous terminal
    // state, skip it, and leave the committed material pending forever (#692).
    // Directory scans also publish without a transaction and must still run.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWikiProcessing(WikiProcessingEvent event) {
        log.info("[Wiki] Processing event received: rawId={}, kbId={}", event.getRawMaterialId(), event.getKbId());
        // 立即把实际处理派发到 wiki 的虚拟线程池，释放事件发布者线程和共享的 @Async 池
        WikiProcessingService.WIKI_EXECUTOR.submit(() -> runWithSemaphore(event.getRawMaterialId()));
    }

    private void runWithSemaphore(Long rawId) {
        try {
            rawMaterialSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Wiki] Interrupted while waiting for processing slot, rawId={}", rawId);
            return;
        }
        try {
            processingService.processRawMaterial(rawId);
        } catch (Exception e) {
            log.error("[Wiki] Async processing failed for rawId={}: {}", rawId, e.getMessage(), e);
        } finally {
            rawMaterialSemaphore.release();
        }
    }
}
