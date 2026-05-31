package vip.mate.memory.spi;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.spi.decorator.MetricsMemoryProvider;
import vip.mate.memory.spi.decorator.RetryableMemoryProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Memory manager — orchestrates all registered MemoryProvider instances.
 * <p>
 * Single integration point for the agent system. Delegates system prompt assembly,
 * per-turn prefetch, post-turn sync, and tool collection to registered providers.
 * <p>
 * Failures in one provider never block others (fault isolation).
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class MemoryManager {

    private static final Pattern FENCE_TAG_RE = Pattern.compile("</?(memory-context)>", Pattern.CASE_INSENSITIVE);

    private final List<MemoryProvider> providers;

    /** External plugin memory provider (single-select constraint) */
    private volatile MemoryProvider externalPluginProvider = null;

    public MemoryManager(List<MemoryProvider> allProviders, MemoryProperties properties,
                         org.springframework.beans.factory.ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        Set<String> disabled = properties.getDisabledProviders();
        List<MemoryProvider> filtered = allProviders.stream()
                .filter(MemoryProvider::isAvailable)
                .filter(p -> !disabled.contains(p.id()))
                .sorted(Comparator.comparingInt(MemoryProvider::order))
                .collect(Collectors.toList());

        // Assemble decorator chain based on flags
        this.providers = filtered.stream()
                .map(p -> wrapWithDecorators(p, properties, meterRegistry))
                .collect(Collectors.toList());

        if (!disabled.isEmpty()) {
            log.info("[MemoryManager] Disabled providers: {}", disabled);
        }
        String decorators = "";
        if (properties.getProviderRetryAttempts() > 1) decorators += "+retry(" + properties.getProviderRetryAttempts() + ")";
        if (properties.isProviderMetricsEnabled()) decorators += "+metrics";
        log.info("[MemoryManager] Active providers ({}): {} {}",
                this.providers.size(),
                filtered.stream().map(MemoryProvider::id).collect(Collectors.joining(", ")),
                decorators);
    }

    private MemoryProvider wrapWithDecorators(MemoryProvider provider, MemoryProperties properties,
                                              MeterRegistry meterRegistry) {
        MemoryProvider result = provider;
        if (properties.getProviderRetryAttempts() > 1) {
            result = new RetryableMemoryProvider(result, properties.getProviderRetryAttempts());
        }
        if (properties.isProviderMetricsEnabled() && meterRegistry != null) {
            result = new MetricsMemoryProvider(result, meterRegistry);
        }
        return result;
    }

    // ==================== System Prompt ====================

    /**
     * Collect system prompt blocks from all providers.
     * Called once at agent build time (snapshot frozen for session).
     */
    public String buildSystemPromptBlock(Long agentId) {
        List<String> blocks = new ArrayList<>();
        for (MemoryProvider provider : providers) {
            try {
                String block = provider.systemPromptBlock(agentId);
                if (block != null && !block.isBlank()) {
                    blocks.add(block);
                }
            } catch (Exception e) {
                log.warn("[MemoryManager] Provider '{}' systemPromptBlock() failed: {}",
                        provider.id(), e.getMessage());
            }
        }
        return String.join("\n\n", blocks);
    }

    // ==================== Prefetch / Recall ====================

    /**
     * Pre-turn: collect prefetch context from all providers, wrapped in a
     * &lt;memory-context&gt; fence to prevent the model from treating recalled
     * context as new user discourse.
     */
    public String prefetchAll(Long agentId, String userQuery) {
        List<String> parts = new ArrayList<>();
        for (MemoryProvider provider : providers) {
            try {
                String result = provider.prefetch(agentId, userQuery);
                if (result != null && !result.isBlank()) {
                    parts.add(sanitizeContext(result));
                }
            } catch (Exception e) {
                log.debug("[MemoryManager] Provider '{}' prefetch failed (non-fatal): {}",
                        provider.id(), e.getMessage());
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        String merged = String.join("\n\n", parts);
        return buildMemoryContextBlock(merged);
    }

    // ==================== Sync ====================

    /**
     * Post-turn: sync completed turn to all providers (should be called async).
     */
    public void syncAll(Long agentId, String conversationId,
                        String userMessage, String assistantReply) {
        for (MemoryProvider provider : providers) {
            try {
                provider.syncTurn(agentId, conversationId, userMessage, assistantReply);
            } catch (Exception e) {
                log.warn("[MemoryManager] Provider '{}' syncTurn failed: {}",
                        provider.id(), e.getMessage());
            }
        }
    }

    // ==================== Tools ====================

    /**
     * Collect tool beans from all providers for registration with ToolRegistry.
     */
    public List<Object> collectToolBeans() {
        List<Object> beans = new ArrayList<>();
        for (MemoryProvider provider : providers) {
            try {
                List<Object> providerBeans = provider.getToolBeans();
                if (providerBeans != null) {
                    beans.addAll(providerBeans);
                }
            } catch (Exception e) {
                log.warn("[MemoryManager] Provider '{}' getToolBeans() failed: {}",
                        provider.id(), e.getMessage());
            }
        }
        return beans;
    }

    // ==================== Lifecycle Hooks ====================

    public void onSessionEnd(Long agentId, String conversationId) {
        for (MemoryProvider provider : providers) {
            try {
                provider.onSessionEnd(agentId, conversationId);
            } catch (Exception e) {
                log.debug("[MemoryManager] Provider '{}' onSessionEnd failed: {}",
                        provider.id(), e.getMessage());
            }
        }
    }

    public String onPreCompress(Long agentId, List<?> messages) {
        List<String> parts = new ArrayList<>();
        for (MemoryProvider provider : providers) {
            try {
                String result = provider.onPreCompress(agentId, messages);
                if (result != null && !result.isBlank()) {
                    parts.add(result);
                }
            } catch (Exception e) {
                log.debug("[MemoryManager] Provider '{}' onPreCompress failed: {}",
                        provider.id(), e.getMessage());
            }
        }
        return String.join("\n\n", parts);
    }

    // ==================== Context Fencing ====================

    /**
     * Strip fence-escape sequences from provider output to prevent
     * providers from breaking out of the memory-context block.
     */
    private String sanitizeContext(String text) {
        return FENCE_TAG_RE.matcher(text).replaceAll("");
    }

    /**
     * Wrap prefetched memory in a fenced block with system note.
     * Injected at API-call time only, never persisted.
     */
    private String buildMemoryContextBlock(String rawContext) {
        return "<memory-context>\n"
                + "The following is what you already know about this user and their "
                + "work, recalled from your own long-term memory. Use it directly as "
                + "established fact when answering — this is your knowledge, not the "
                + "user speaking. If something the user asks about is not covered here, "
                + "say you do not have it in memory rather than guessing. If entries "
                + "conflict, prefer the most recently updated one; if they refer to "
                + "different projects, ask which one the user means.\n\n"
                + rawContext + "\n"
                + "</memory-context>";
    }

    // ==================== Plugin Provider Registration ====================

    /**
     * Register an external plugin memory provider.
     * Only one external provider is allowed at a time (single-select constraint).
     *
     * @param provider the memory provider to register
     * @throws vip.mate.plugin.api.PluginException if an external provider is already registered
     */
    public synchronized void registerPluginProvider(MemoryProvider provider) {
        if (externalPluginProvider != null) {
            throw new vip.mate.plugin.api.PluginException(
                    "Only one external memory provider allowed. Current: " + externalPluginProvider.id());
        }
        if (!provider.isAvailable()) {
            log.warn("[MemoryManager] Plugin provider '{}' is not available, skipping", provider.id());
            return;
        }
        externalPluginProvider = provider;
        providers.add(provider);
        providers.sort(Comparator.comparingInt(MemoryProvider::order));
        log.info("[MemoryManager] Plugin provider registered: {}", provider.id());
    }

    /**
     * Unregister the external plugin memory provider.
     */
    public synchronized void unregisterPluginProvider(String providerId) {
        if (externalPluginProvider != null && externalPluginProvider.id().equals(providerId)) {
            providers.removeIf(p -> p.id().equals(providerId));
            externalPluginProvider = null;
            log.info("[MemoryManager] Plugin provider unregistered: {}", providerId);
        }
    }

    /**
     * Whether an external plugin memory provider is registered.
     */
    public boolean hasExternalProvider() {
        return externalPluginProvider != null;
    }

    /**
     * Get the external plugin memory provider's ID.
     */
    public String getExternalProviderName() {
        return externalPluginProvider != null ? externalPluginProvider.id() : null;
    }

    // ==================== Accessors ====================

    public List<MemoryProvider> getProviders() {
        return List.copyOf(providers);
    }

    public List<String> getProviderIds() {
        return providers.stream().map(MemoryProvider::id).toList();
    }
}
