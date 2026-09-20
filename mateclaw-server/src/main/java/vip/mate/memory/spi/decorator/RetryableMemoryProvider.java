package vip.mate.memory.spi.decorator;

import lombok.extern.slf4j.Slf4j;
import vip.mate.memory.spi.MemoryProvider;

/**
 * Decorator that retries failed prefetch/syncTurn calls with exponential backoff.
 *
 * @author MateClaw Team
 */
@Slf4j
public class RetryableMemoryProvider extends MemoryProviderDecorator {

    private final int maxAttempts;

    public RetryableMemoryProvider(MemoryProvider delegate, int maxAttempts) {
        super(delegate);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public String prefetch(Long agentId, String userQuery) {
        return prefetch(agentId, userQuery, null);
    }

    @Override
    public String prefetch(Long agentId, String userQuery, String ownerKey) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return delegate.prefetch(agentId, userQuery, ownerKey);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.debug("[Retry] prefetch attempt {}/{} failed for provider={}: {}",
                            attempt, maxAttempts, delegate.id(), e.getMessage());
                    sleep(attempt);
                }
            }
        }
        log.warn("[Retry] prefetch exhausted {} attempts for provider={}: {}",
                maxAttempts, delegate.id(), lastException != null ? lastException.getMessage() : "");
        throw new IllegalStateException("Provider prefetch exhausted retries: " + delegate.id(), lastException);
    }

    @Override
    public void syncTurn(Long agentId, String conversationId, String userMessage, String assistantReply) {
        syncTurn(agentId, conversationId, userMessage, assistantReply, null);
    }

    @Override
    public void syncTurn(Long agentId, String conversationId, String userMessage, String assistantReply, String ownerKey) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                delegate.syncTurn(agentId, conversationId, userMessage, assistantReply, ownerKey);
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.debug("[Retry] syncTurn attempt {}/{} failed for provider={}: {}",
                            attempt, maxAttempts, delegate.id(), e.getMessage());
                    sleep(attempt);
                }
            }
        }
        log.warn("[Retry] syncTurn exhausted {} attempts for provider={}: {}",
                maxAttempts, delegate.id(), lastException != null ? lastException.getMessage() : "");
        throw new IllegalStateException("Provider sync exhausted retries: " + delegate.id(), lastException);
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep((long) Math.pow(2, attempt - 1) * 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider retry interrupted: " + delegate.id(), e);
        }
    }
}
