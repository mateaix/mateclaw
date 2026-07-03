package vip.mate.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.channel.ChannelManager;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.plugin.api.PluginContext;
import vip.mate.plugin.api.PluginException;
import vip.mate.plugin.api.PluginManifest;
import vip.mate.plugin.api.channel.PluginChannelAdapter;
import vip.mate.plugin.api.memory.PluginMemoryProvider;
import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.bridge.PluginChannelBridge;
import vip.mate.plugin.bridge.PluginMemoryBridge;
import vip.mate.plugin.bridge.PluginSearchBridge;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.search.SearchProviderRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Platform-side implementation of {@link PluginContext}.
 * Bridges plugin registrations to the corresponding platform services.
 *
 * @author MateClaw Team
 */
public class PluginContextImpl implements PluginContext {

    private final LoadedPlugin loadedPlugin;
    private final PluginManifest manifest;
    private final ToolRegistry toolRegistry;
    private final ChannelManager channelManager;
    private final MemoryManager memoryManager;
    private final ModelProviderService modelProviderService;
    private final SearchProviderRegistry searchProviderRegistry;
    private final Map<String, Object> configMap;
    private final Logger logger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PluginContextImpl(LoadedPlugin loadedPlugin,
                             PluginManifest manifest,
                             ToolRegistry toolRegistry,
                             ChannelManager channelManager,
                             MemoryManager memoryManager,
                             ModelProviderService modelProviderService,
                             SearchProviderRegistry searchProviderRegistry,
                             String configJson) {
        this.loadedPlugin = loadedPlugin;
        this.manifest = manifest;
        this.toolRegistry = toolRegistry;
        this.channelManager = channelManager;
        this.memoryManager = memoryManager;
        this.modelProviderService = modelProviderService;
        this.searchProviderRegistry = searchProviderRegistry;
        this.logger = LoggerFactory.getLogger("plugin." + manifest.getName());
        this.configMap = parseConfig(configJson);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(configJson, Map.class);
        } catch (Exception e) {
            logger.warn("Failed to parse plugin config JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public void registerTool(ToolCallback tool) {
        registerTool(tool, () -> true);
    }

    @Override
    public void registerTool(ToolCallback tool, Supplier<Boolean> availabilityCheck) {
        toolRegistry.registerPluginTool(tool, availabilityCheck);
        loadedPlugin.getRegisteredTools().add(tool.getToolDefinition().name());
    }

    @Override
    public void registerProvider(String providerId, ChatModel chatModel) {
        modelProviderService.registerPluginChatModel(providerId, chatModel);
        loadedPlugin.setRegisteredProvider(providerId);
    }

    @Override
    public void registerChannel(PluginChannelAdapter channel) {
        PluginChannelBridge bridge = new PluginChannelBridge(channel);
        channelManager.registerPluginChannel(manifest.getName(), bridge);
        loadedPlugin.getRegisteredChannels().add(channel.getChannelType());
    }

    @Override
    public void registerMemoryProvider(PluginMemoryProvider provider) {
        if (memoryManager.hasExternalProvider()) {
            throw new PluginException(
                    "Only one external memory provider allowed. Current: " +
                            memoryManager.getExternalProviderName());
        }
        PluginMemoryBridge bridge = new PluginMemoryBridge(provider);
        memoryManager.registerPluginProvider(bridge);
        loadedPlugin.setRegisteredMemoryProvider(provider.id());
    }

    @Override
    public void registerSearchProvider(PluginSearchProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new PluginException("Search provider id must not be blank");
        }
        try {
            searchProviderRegistry.registerPluginProvider(new PluginSearchBridge(provider));
        } catch (IllegalArgumentException e) {
            throw new PluginException(e.getMessage(), e);
        }
        loadedPlugin.getRegisteredSearchProviders().add(provider.id());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String key, Class<T> type) {
        Object value = configMap.get(key);
        if (value == null) return null;
        if (type.isInstance(value)) return (T) value;
        return objectMapper.convertValue(value, type);
    }

    @Override
    public Logger getLogger() {
        return logger;
    }
}
