package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import vip.mate.wiki.event.WikiFactPageUpdatedEvent;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiStalePropagationListener}: it forwards a fact-page
 * update to the dependency engine and never lets a failure escape (so a broken
 * propagation cannot disturb ingest).
 */
class WikiStalePropagationListenerTest {

    @Test
    void forwardsToMarkDependentsStale() {
        WikiDependencyService dep = mock(WikiDependencyService.class);
        when(dep.markDependentsStale(7L, 100L, "r")).thenReturn(2);

        new WikiStalePropagationListener(dep)
                .onFactPageUpdated(new WikiFactPageUpdatedEvent(7L, 100L, "r"));

        verify(dep).markDependentsStale(7L, 100L, "r");
    }

    @Test
    void swallowsFailure() {
        WikiDependencyService dep = mock(WikiDependencyService.class);
        doThrow(new RuntimeException("boom")).when(dep).markDependentsStale(7L, 100L, "r");

        // Must not throw.
        new WikiStalePropagationListener(dep)
                .onFactPageUpdated(new WikiFactPageUpdatedEvent(7L, 100L, "r"));
    }
}
