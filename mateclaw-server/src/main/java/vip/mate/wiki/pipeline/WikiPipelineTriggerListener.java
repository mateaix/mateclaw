package vip.mate.wiki.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.mate.wiki.event.WikiPageCreatedEvent;

/**
 * Evaluates count-threshold pipeline triggers asynchronously when a page is
 * created during ingest. Runs off the ingest thread so a fired pipeline (which
 * may call a model) never blocks ingest; the page is already committed when the
 * event fires, so the count is accurate, and the run dedup key keeps repeated
 * evaluation idempotent.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class WikiPipelineTriggerListener {

    private final WikiPipelineTriggerService triggerService;

    public WikiPipelineTriggerListener(WikiPipelineTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @Async
    @EventListener
    public void onPageCreated(WikiPageCreatedEvent event) {
        try {
            triggerService.onPageTypeCount(event.kbId(), event.pageType());
            triggerService.onPageCreated(event.kbId(), event.pageType(), event.pageId());
        } catch (Exception e) {
            log.warn("[WikiPipeline] trigger evaluation failed for kb={} pageType={}: {}",
                    event.kbId(), event.pageType(), e.getMessage());
        }
    }
}
