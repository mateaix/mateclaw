package vip.mate.plugin.bridge;

import vip.mate.memory.spi.MemoryProvider;
import vip.mate.plugin.api.memory.PluginMemoryProvider;

import java.util.Collections;
import java.util.List;

/**
 * Bridge that wraps a plugin's {@link PluginMemoryProvider} into the platform's
 * internal {@link MemoryProvider} interface.
 *
 * @author MateClaw Team
 */
public class PluginMemoryBridge implements MemoryProvider {

    private final PluginMemoryProvider delegate;

    public PluginMemoryBridge(PluginMemoryProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public int order() {
        return delegate.order();
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public String systemPromptBlock(Long agentId) {
        return delegate.systemPromptBlock(agentId);
    }

    @Override
    public String prefetch(Long agentId, String userQuery) {
        return delegate.prefetch(agentId, userQuery);
    }

    @Override
    public String prefetch(Long agentId, String userQuery, String ownerKey) {
        // 透传 ownerKey 到插件 provider；插件若未 override 三参版会走 default 退化到两参
        return delegate.prefetch(agentId, userQuery, ownerKey);
    }

    @Override
    public void syncTurn(Long agentId, String conversationId,
                         String userMessage, String assistantReply) {
        delegate.syncTurn(agentId, conversationId, userMessage, assistantReply);
    }

    @Override
    public List<Object> getToolBeans() {
        List<Object> beans = delegate.getToolBeans();
        return beans != null ? beans : Collections.emptyList();
    }

    @Override
    public void onSessionEnd(Long agentId, String conversationId) {
        delegate.onSessionEnd(agentId, conversationId);
    }
}
