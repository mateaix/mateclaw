package vip.mate.execution.evidence.service;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;
import vip.mate.execution.evidence.ExecutionEvidenceProperties;

import java.time.Instant;

/** Metadata retention is independent of source-file retention and respects conversation deletion. */
@Component
public class ExecutionEvidenceLifecycle {
    private final ExecutionEvidenceStore store;

    private final ExecutionEvidenceProperties properties;

    public ExecutionEvidenceLifecycle(ExecutionEvidenceStore store, ExecutionEvidenceProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @EventListener
    public void onConversationDeleted(ConversationDeletedEvent event) {
        store.purgeConversation(event.conversationId());
    }

    @Scheduled(fixedDelayString = "${mateclaw.execution-evidence.cleanup-interval-ms:60000}")
    public void cleanup() {
        Instant now = Instant.now();
        for (int batch = 0; batch < properties.getCleanupMaxBatches(); batch++) {
            if (store.purgeExpiredMetadata(now, 100) < 100) break;
        }
    }
}
