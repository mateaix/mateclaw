package vip.mate.plugin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.mate.channel.ChannelManager;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginException;
import vip.mate.plugin.api.PluginManifest;
import vip.mate.plugin.model.PluginEntity;
import vip.mate.plugin.model.PluginInfo;
import vip.mate.plugin.repository.PluginMapper;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.search.SearchProviderRegistry;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Plugin lifecycle manager.
 * <p>
 * Discovers, validates, loads, and manages plugins from JAR files.
 * Supports three discovery paths with priority: workspace > user-global > classpath.
 * Plugins are loaded on application startup and can be enabled/disabled at runtime.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginManager {

    /** Current platform version for minPlatformVersion compatibility check */
    private static final String PLATFORM_VERSION = "1.1.0";

    private final PluginProperties pluginProperties;
    private final PluginMapper pluginMapper;
    private final ToolRegistry toolRegistry;
    private final ChannelManager channelManager;
    private final MemoryManager memoryManager;
    private final ModelProviderService modelProviderService;
    private final SearchProviderRegistry searchProviderRegistry;
    private final Optional<WorkspaceService> workspaceService;

    private final Map<String, LoadedPlugin> plugins = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Load all plugins on application startup.
     * Scans three paths in priority order: workspace > user-global.
     * Higher priority plugins shadow lower priority ones with the same name.
     * {@code @Async} — 文件系统扫描和 JAR 类加载不阻塞主启动线程。
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    @Order(250)
    public void loadAllPlugins() {
        if (!pluginProperties.isEnabled()) {
            log.info("Plugin system is disabled");
            return;
        }

        // Collect plugin JARs from all discovery paths in priority order
        List<Path> discoveredJars = discoverPluginJars();

        int loaded = 0;
        int skipped = 0;
        int failed = 0;

        for (Path jar : discoveredJars) {
            try {
                // Read manifest to check name before loading
                PluginManifest manifest = readManifest(jar);
                if (plugins.containsKey(manifest.getName())) {
                    log.info("Plugin {} already loaded from higher-priority path, skipping: {}",
                            manifest.getName(), jar);
                    skipped++;
                    continue;
                }
                loadPlugin(jar);
                loaded++;
            } catch (Exception e) {
                failed++;
                log.error("Failed to load plugin from {}: {}", jar.getFileName(), e.getMessage(), e);
                recordError(jar, e);
            }
        }

        log.info("Plugin loading complete: {} loaded, {} skipped (duplicate), {} failed", loaded, skipped, failed);
    }

    /**
     * Discover plugin JARs from all paths in priority order.
     * Workspace plugins (highest priority) come first, then user-global.
     */
    private List<Path> discoverPluginJars() {
        List<Path> jars = new ArrayList<>();

        // 1. Workspace-level plugins (highest priority)
        workspaceService.ifPresent(ws -> {
            try {
                WorkspaceEntity defaultWs = ws.getBySlug(WorkspaceService.DEFAULT_SLUG);
                if (defaultWs != null && defaultWs.getBasePath() != null) {
                    Path workspacePluginDir = Paths.get(defaultWs.getBasePath(), "plugins");
                    if (Files.isDirectory(workspacePluginDir)) {
                        scanJars(workspacePluginDir, jars, "workspace");
                    }
                }
            } catch (Exception e) {
                log.debug("Workspace plugin scan skipped: {}", e.getMessage());
            }
        });

        // 2. User-global plugins
        Path userDir = Paths.get(pluginProperties.getUserDir());
        try {
            Files.createDirectories(userDir);
            scanJars(userDir, jars, "user-global");
        } catch (IOException e) {
            log.warn("Failed to scan user plugin directory {}: {}", userDir, e.getMessage());
        }

        log.info("Discovered {} plugin JAR(s) across all paths", jars.size());
        return jars;
    }

    private void scanJars(Path dir, List<Path> target, String source) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> found = stream
                    .filter(p -> p.toString().endsWith(".jar"))
                    .toList();
            if (!found.isEmpty()) {
                log.info("Found {} plugin JAR(s) in {} ({})", found.size(), dir, source);
                target.addAll(found);
            }
        } catch (IOException e) {
            log.warn("Failed to list {} plugin directory {}: {}", source, dir, e.getMessage());
        }
    }

    /**
     * Load a single plugin from a JAR file.
     * Ensures ClassLoader cleanup on any failure, and rolls back registrations
     * if onLoad/onEnable throws.
     */
    public void loadPlugin(Path jarPath) throws Exception {
        log.info("Loading plugin from: {}", jarPath.getFileName());

        // 1. Read and validate manifest
        PluginManifest manifest = readManifest(jarPath);
        manifest.validate();

        // Check platform version compatibility
        if (!manifest.isCompatibleWith(PLATFORM_VERSION)) {
            throw new PluginException("Plugin " + manifest.getName() + " requires platform version " +
                    manifest.getMinPlatformVersion() + " but current is " + PLATFORM_VERSION);
        }

        String pluginName = manifest.getName();

        // 2. Check if already loaded
        if (plugins.containsKey(pluginName)) {
            log.warn("Plugin {} already loaded, skipping: {}", pluginName, jarPath);
            return;
        }

        // 3. Check DB for disabled state
        PluginEntity existing = findByName(pluginName);
        if (existing != null && !Boolean.TRUE.equals(existing.getEnabled())) {
            log.info("Plugin {} is disabled in database, skipping", pluginName);
            upsertEntity(manifest, jarPath.toString(), "DISABLED", null);
            return;
        }

        // 4. Create ClassLoader — all subsequent failures must close it
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                getClass().getClassLoader()
        );

        try {
            // 5. Instantiate entrypoint
            Class<?> entryClass = classLoader.loadClass(manifest.getEntrypoint());
            if (!MateClawPlugin.class.isAssignableFrom(entryClass)) {
                throw new PluginException("Entrypoint class " + manifest.getEntrypoint() +
                        " does not implement MateClawPlugin");
            }
            MateClawPlugin plugin = (MateClawPlugin) entryClass.getDeclaredConstructor().newInstance();

            // 6. Create context and loaded plugin
            String configJson = existing != null ? existing.getConfigJson() : null;
            LoadedPlugin loadedPlugin = new LoadedPlugin(manifest, plugin, classLoader);

            PluginContextImpl context = new PluginContextImpl(
                    loadedPlugin, manifest,
                    toolRegistry, channelManager, memoryManager, modelProviderService,
                    searchProviderRegistry,
                    configJson
            );
            loadedPlugin.setContext(context);

            // 7. Call lifecycle methods — rollback registrations on failure
            try {
                plugin.onLoad(context);
                plugin.onEnable();
            } catch (Exception e) {
                log.error("Plugin {} lifecycle failed, rolling back registrations: {}", pluginName, e.getMessage());
                rollbackRegistrations(loadedPlugin);
                throw e;
            }

            // 8. Register successfully
            plugins.put(pluginName, loadedPlugin);
            upsertEntity(manifest, jarPath.toString(), "ENABLED", null);

            log.info("Plugin loaded: {} v{} (type={}, tools={}, channels={})",
                    pluginName, manifest.getVersion(), manifest.getType(),
                    loadedPlugin.getRegisteredTools().size(),
                    loadedPlugin.getRegisteredChannels().size());

        } catch (Exception e) {
            // ClassLoader cleanup on any failure
            try {
                classLoader.close();
            } catch (IOException closeEx) {
                log.warn("Failed to close ClassLoader for {}: {}", jarPath.getFileName(), closeEx.getMessage());
            }
            throw e;
        }
    }

    /**
     * Rollback all registrations made by a plugin during failed onLoad/onEnable.
     */
    private void rollbackRegistrations(LoadedPlugin loaded) {
        for (String toolName : loaded.getRegisteredTools()) {
            try { toolRegistry.unregisterPluginTool(toolName); } catch (Exception e) { /* best effort */ }
        }
        if (!loaded.getRegisteredChannels().isEmpty()) {
            try { channelManager.unregisterPluginChannel(loaded.getManifest().getName()); } catch (Exception e) { /* best effort */ }
        }
        if (loaded.getRegisteredMemoryProvider() != null) {
            try { memoryManager.unregisterPluginProvider(loaded.getRegisteredMemoryProvider()); } catch (Exception e) { /* best effort */ }
        }
        if (loaded.getRegisteredProvider() != null) {
            try { modelProviderService.unregisterPluginChatModel(loaded.getRegisteredProvider()); } catch (Exception e) { /* best effort */ }
        }
        for (String searchId : loaded.getRegisteredSearchProviders()) {
            try { searchProviderRegistry.unregisterPluginProvider(searchId); } catch (Exception e) { /* best effort */ }
        }
    }

    /**
     * Disable a plugin by name.
     */
    public void disablePlugin(String name) {
        LoadedPlugin loaded = plugins.get(name);
        if (loaded == null) {
            throw new PluginException("Plugin not found or not running: " + name);
        }

        // Call onDisable
        try {
            loaded.getPlugin().onDisable();
        } catch (Exception e) {
            log.warn("Plugin {} onDisable() threw exception: {}", name, e.getMessage());
        }

        // Unregister all capabilities
        int toolsRemoved = 0;
        for (String toolName : loaded.getRegisteredTools()) {
            toolRegistry.unregisterPluginTool(toolName);
            toolsRemoved++;
        }
        channelManager.unregisterPluginChannel(name);
        int channelsRemoved = loaded.getRegisteredChannels().size();

        String memoryRemoved = null;
        if (loaded.getRegisteredMemoryProvider() != null) {
            memoryManager.unregisterPluginProvider(loaded.getRegisteredMemoryProvider());
            memoryRemoved = loaded.getRegisteredMemoryProvider();
        }

        String providerRemoved = null;
        if (loaded.getRegisteredProvider() != null) {
            modelProviderService.unregisterPluginChatModel(loaded.getRegisteredProvider());
            providerRemoved = loaded.getRegisteredProvider();
        }

        for (String searchId : loaded.getRegisteredSearchProviders()) {
            searchProviderRegistry.unregisterPluginProvider(searchId);
        }
        int searchRemoved = loaded.getRegisteredSearchProviders().size();

        loaded.setEnabled(false);
        plugins.remove(name);
        updateStatus(name, false, "DISABLED", null);

        log.info("Plugin disabled: {} (tools={}, channels={}, provider={}, memory={}, search={})",
                name, toolsRemoved, channelsRemoved,
                providerRemoved != null ? providerRemoved : "none",
                memoryRemoved != null ? memoryRemoved : "none",
                searchRemoved);
    }

    /**
     * Enable a previously disabled plugin.
     * Validates JAR still exists before re-loading.
     */
    public void enablePlugin(String name) {
        PluginEntity entity = findByName(name);
        if (entity == null) {
            throw new PluginException("Plugin not found in database: " + name);
        }
        if (entity.getJarPath() == null) {
            throw new PluginException("Plugin JAR path unknown for: " + name);
        }

        // Validate JAR still exists on disk
        Path jarPath = Paths.get(entity.getJarPath());
        if (!Files.exists(jarPath)) {
            updateStatus(name, false, "ERROR", "JAR file not found: " + entity.getJarPath());
            throw new PluginException("Plugin JAR file no longer exists: " + entity.getJarPath());
        }

        updateStatus(name, true, "LOADING", null);

        try {
            loadPlugin(jarPath);
        } catch (Exception e) {
            updateStatus(name, false, "ERROR", e.getMessage());
            throw new PluginException("Failed to re-enable plugin " + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * List all known plugins (loaded + DB-only).
     * Secret config values are redacted in the response.
     */
    @SuppressWarnings("unchecked")
    public List<PluginInfo> listPlugins() {
        Map<String, PluginInfo> result = new LinkedHashMap<>();

        // In-memory loaded plugins
        for (Map.Entry<String, LoadedPlugin> entry : plugins.entrySet()) {
            LoadedPlugin loaded = entry.getValue();
            PluginManifest m = loaded.getManifest();
            result.put(entry.getKey(), PluginInfo.builder()
                    .name(m.getName())
                    .version(m.getVersion())
                    .type(m.getType())
                    .displayName(m.getDisplayName())
                    .description(m.getDescription())
                    .author(m.getAuthor())
                    .enabled(loaded.isEnabled())
                    .status("ENABLED")
                    .registeredTools(List.copyOf(loaded.getRegisteredTools()))
                    .registeredChannels(List.copyOf(loaded.getRegisteredChannels()))
                    .registeredProvider(loaded.getRegisteredProvider())
                    .registeredMemoryProvider(loaded.getRegisteredMemoryProvider())
                    .registeredSearchProviders(List.copyOf(loaded.getRegisteredSearchProviders()))
                    .configSchema(buildConfigSchema(m))
                    .currentConfig(buildRedactedConfig(loaded))
                    .build());
        }

        // DB-only entries (disabled plugins not in memory)
        List<PluginEntity> dbPlugins = pluginMapper.selectList(new LambdaQueryWrapper<>());
        for (PluginEntity entity : dbPlugins) {
            if (!result.containsKey(entity.getName())) {
                result.put(entity.getName(), PluginInfo.builder()
                        .name(entity.getName())
                        .version(entity.getVersion())
                        .type(entity.getPluginType())
                        .displayName(entity.getDisplayName())
                        .description(entity.getDescription())
                        .author(entity.getAuthor())
                        .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                        .status(entity.getStatus())
                        .errorMessage(entity.getErrorMessage())
                        .jarPath(entity.getJarPath())
                        .registeredTools(List.of())
                        .registeredChannels(List.of())
                        .registeredSearchProviders(List.of())
                        .build());
            }
        }

        return new ArrayList<>(result.values());
    }

    /**
     * Get a single plugin's info by name.
     */
    public PluginInfo getPlugin(String name) {
        return listPlugins().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new PluginException("Plugin not found: " + name));
    }

    /**
     * Update a plugin's configuration.
     */
    public void updateConfig(String name, Map<String, Object> config) {
        PluginEntity entity = findByName(name);
        if (entity == null) {
            throw new PluginException("Plugin not found: " + name);
        }

        // Validate config keys against manifest if plugin is loaded
        LoadedPlugin loaded = plugins.get(name);
        if (loaded != null && loaded.getManifest().getConfig() != null) {
            Map<String, PluginManifest.ConfigField> schema = loaded.getManifest().getConfig();
            for (String key : config.keySet()) {
                if (!schema.containsKey(key)) {
                    log.warn("Plugin {} config: unknown key '{}' (not in manifest schema)", name, key);
                }
            }
            // Check required fields
            for (Map.Entry<String, PluginManifest.ConfigField> schemaEntry : schema.entrySet()) {
                if (schemaEntry.getValue().isRequired() && !config.containsKey(schemaEntry.getKey())) {
                    throw new PluginException("Missing required config field: " + schemaEntry.getKey());
                }
            }
        }

        try {
            entity.setConfigJson(objectMapper.writeValueAsString(config));
            pluginMapper.updateById(entity);
            log.info("Plugin config updated: {}", name);
        } catch (PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new PluginException("Failed to update config for " + name + ": " + e.getMessage(), e);
        }
    }

    // ==================== Config Schema & Redaction ====================

    /**
     * Build config schema map from manifest for frontend display.
     */
    private Map<String, Object> buildConfigSchema(PluginManifest manifest) {
        if (manifest.getConfig() == null || manifest.getConfig().isEmpty()) {
            return null;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        manifest.getConfig().forEach((key, field) -> {
            Map<String, Object> fieldInfo = new LinkedHashMap<>();
            fieldInfo.put("type", field.getType());
            fieldInfo.put("required", field.isRequired());
            fieldInfo.put("secret", field.isSecret());
            fieldInfo.put("description", field.getDescription());
            schema.put(key, fieldInfo);
        });
        return schema;
    }

    /**
     * Build config map with secret values redacted.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRedactedConfig(LoadedPlugin loaded) {
        PluginEntity entity = findByName(loaded.getManifest().getName());
        if (entity == null || entity.getConfigJson() == null || entity.getConfigJson().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> config = objectMapper.readValue(entity.getConfigJson(), Map.class);
            // Redact secret fields
            Map<String, PluginManifest.ConfigField> schema = loaded.getManifest().getConfig();
            if (schema != null) {
                for (Map.Entry<String, PluginManifest.ConfigField> schemaEntry : schema.entrySet()) {
                    if (schemaEntry.getValue().isSecret() && config.containsKey(schemaEntry.getKey())) {
                        Object val = config.get(schemaEntry.getKey());
                        if (val != null && !val.toString().isBlank()) {
                            config.put(schemaEntry.getKey(), "****");
                        }
                    }
                }
            }
            return config;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Internal Methods ====================

    private PluginManifest readManifest(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jarFile.getEntry("mateclaw-plugin.json");
            if (entry == null) {
                throw new PluginException("No mateclaw-plugin.json found in " + jarPath);
            }
            try (InputStream is = jarFile.getInputStream(entry)) {
                return objectMapper.readValue(is, PluginManifest.class);
            }
        }
    }

    private PluginEntity findByName(String name) {
        return pluginMapper.selectOne(new LambdaQueryWrapper<PluginEntity>()
                .eq(PluginEntity::getName, name));
    }

    private void upsertEntity(PluginManifest manifest, String jarPath, String status, String error) {
        PluginEntity existing = findByName(manifest.getName());
        if (existing != null) {
            existing.setVersion(manifest.getVersion());
            existing.setPluginType(manifest.getType());
            existing.setDisplayName(manifest.getDisplayName());
            existing.setDescription(manifest.getDescription());
            existing.setAuthor(manifest.getAuthor());
            existing.setEntrypoint(manifest.getEntrypoint());
            existing.setJarPath(jarPath);
            existing.setStatus(status);
            existing.setErrorMessage(error);
            existing.setEnabled(!"DISABLED".equals(status) && !"ERROR".equals(status));
            pluginMapper.updateById(existing);
        } else {
            PluginEntity entity = new PluginEntity();
            entity.setName(manifest.getName());
            entity.setVersion(manifest.getVersion());
            entity.setPluginType(manifest.getType());
            entity.setDisplayName(manifest.getDisplayName());
            entity.setDescription(manifest.getDescription());
            entity.setAuthor(manifest.getAuthor());
            entity.setEntrypoint(manifest.getEntrypoint());
            entity.setJarPath(jarPath);
            entity.setStatus(status);
            entity.setErrorMessage(error);
            entity.setEnabled(!"DISABLED".equals(status) && !"ERROR".equals(status));
            entity.setCreateTime(LocalDateTime.now());
            entity.setUpdateTime(LocalDateTime.now());
            entity.setConfigJson("{}");
            pluginMapper.insert(entity);
        }
    }

    private void updateStatus(String name, boolean enabled, String status, String error) {
        PluginEntity entity = findByName(name);
        if (entity != null) {
            entity.setEnabled(enabled);
            entity.setStatus(status);
            entity.setErrorMessage(error);
            pluginMapper.updateById(entity);
        }
    }

    private void recordError(Path jarPath, Exception e) {
        try {
            PluginManifest manifest = readManifest(jarPath);
            upsertEntity(manifest, jarPath.toString(), "ERROR", e.getMessage());
        } catch (Exception ex) {
            log.warn("Cannot record plugin error (manifest unreadable): {} — {}", jarPath.getFileName(), ex.getMessage());
        }
    }
}
