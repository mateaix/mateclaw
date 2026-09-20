package vip.mate.execution.evidence;

import org.junit.jupiter.api.Test;
import vip.mate.execution.evidence.service.ExecutionEvidenceLifecycle;
import vip.mate.execution.evidence.service.ExecutionEvidenceStore;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionEvidenceLifecycleTest {
    @Test void drainsSeveralBatchesAndStopsWhenCaughtUp() {
        var store = mock(ExecutionEvidenceStore.class);
        when(store.purgeExpiredMetadata(any(), eq(100))).thenReturn(100, 100, 1);
        new ExecutionEvidenceLifecycle(store, new ExecutionEvidenceProperties()).cleanup();
        verify(store, times(3)).purgeExpiredMetadata(any(), eq(100));
    }

    @Test void cleanupRemainsBoundedAndContinuesOnTheNextTick() {
        var store = mock(ExecutionEvidenceStore.class);
        var properties = new ExecutionEvidenceProperties(); properties.setCleanupMaxBatches(2);
        when(store.purgeExpiredMetadata(any(), eq(100))).thenReturn(100, 100, 1);
        var lifecycle = new ExecutionEvidenceLifecycle(store, properties);
        lifecycle.cleanup();
        verify(store, times(2)).purgeExpiredMetadata(any(), eq(100));
        lifecycle.cleanup();
        verify(store, times(3)).purgeExpiredMetadata(any(), eq(100));
    }

    @Test void conversationDeletionErasesCopiedContent() {
        var store = mock(ExecutionEvidenceStore.class);
        new ExecutionEvidenceLifecycle(store, new ExecutionEvidenceProperties())
                .onConversationDeleted(new ConversationDeletedEvent("conv"));
        verify(store).purgeConversation("conv");
    }
}
