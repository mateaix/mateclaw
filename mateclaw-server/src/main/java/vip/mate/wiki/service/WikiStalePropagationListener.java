package vip.mate.wiki.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.mate.wiki.event.WikiFactPageUpdatedEvent;

/**
 * Propagates staleness asynchronously when a fact-layer page is updated: every
 * experience page depending on it is marked stale. Runs off the ingest thread
 * so a fan-out over many dependents never blocks ingest; the fact page is
 * already committed when the event fires. Marking is idempotent, so repeated
 * events for the same page are harmless.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class WikiStalePropagationListener {

    private final WikiDependencyService dependencyService;

    public WikiStalePropagationListener(WikiDependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    @Async
    @EventListener
    public void onFactPageUpdated(WikiFactPageUpdatedEvent event) {
        try {
            dependencyService.markDependentsStale(event.kbId(), event.factPageId(), event.reason());
        } catch (Exception e) {
            log.warn("[WikiStale] propagation failed for fact page {}: {}", event.factPageId(), e.getMessage());
        }
    }
}
