package vip.mate.wiki.pipeline;

import org.junit.jupiter.api.Test;
import vip.mate.wiki.event.WikiPageCreatedEvent;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiPipelineTriggerListener}: it forwards the event to
 * the trigger service and never lets a trigger failure escape (so a broken
 * pipeline cannot disturb ingest).
 */
class WikiPipelineTriggerListenerTest {

    @Test
    void forwardsEventToTriggerService() {
        WikiPipelineTriggerService trigger = mock(WikiPipelineTriggerService.class);
        when(trigger.onPageTypeCount(7L, "episode")).thenReturn(1);

        new WikiPipelineTriggerListener(trigger).onPageCreated(new WikiPageCreatedEvent(7L, "episode", 100L));

        verify(trigger).onPageTypeCount(7L, "episode");
    }

    @Test
    void swallowsTriggerFailure() {
        WikiPipelineTriggerService trigger = mock(WikiPipelineTriggerService.class);
        doThrow(new RuntimeException("boom")).when(trigger).onPageTypeCount(7L, "episode");

        // Must not throw — ingest must be unaffected by a pipeline failure.
        new WikiPipelineTriggerListener(trigger).onPageCreated(new WikiPageCreatedEvent(7L, "episode", 100L));
    }
}
