package vip.mate.memory.spi.decorator;

import vip.mate.memory.spi.MemoryProvider;

import java.util.Collections;
import java.util.List;

/**
 * Base decorator for MemoryProvider. All methods delegate to the wrapped provider.
 * Subclass and override specific methods to add behavior (retry, metrics, etc.).
 *
 * @author MateClaw Team
 */
public abstract class MemoryProviderDecorator implements MemoryProvider {

    protected final MemoryProvider delegate;

    protected MemoryProviderDecorator(MemoryProvider delegate) {
        this.delegate = delegate;
    }

    @Override public String id() { return delegate.id(); }
    @Override public int order() { return delegate.order(); }
    @Override public boolean isAvailable() { return delegate.isAvailable(); }
    @Override public String systemPromptBlock(Long agentId) { return delegate.systemPromptBlock(agentId); }
    @Override public String prefetch(Long agentId, String userQuery) { return delegate.prefetch(agentId, userQuery); }
    @Override public String prefetch(Long agentId, String userQuery, String ownerKey) { return delegate.prefetch(agentId, userQuery, ownerKey); }
    @Override public void syncTurn(Long agentId, String conversationId, String userMessage, String assistantReply) {
        delegate.syncTurn(agentId, conversationId, userMessage, assistantReply);
    }
    @Override public List<Object> getToolBeans() { return delegate.getToolBeans(); }
    @Override public void onSessionEnd(Long agentId, String conversationId) { delegate.onSessionEnd(agentId, conversationId); }
    @Override public String onPreCompress(Long agentId, List<?> messages) { return delegate.onPreCompress(agentId, messages); }
    @Override public void onMemoryWrite(Long agentId, String target, String action, String content) {
        delegate.onMemoryWrite(agentId, target, action, content);
    }
    @Override public void warmup(Long agentId) { delegate.warmup(agentId); }
    @Override public void evict(Long agentId) { delegate.evict(agentId); }
}
