package vip.mate.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.channel.ChannelManager;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginContext;
import vip.mate.plugin.api.PluginException;
import vip.mate.plugin.api.PluginManifest;
import vip.mate.plugin.model.PluginEntity;
import vip.mate.plugin.repository.PluginMapper;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.search.SearchProviderRegistry;
import vip.mate.workspace.core.service.WorkspaceService;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PluginManager#updateConfig must MERGE the incoming partial config onto the existing
 * stored config, not overwrite it wholesale — the new Plugins.vue config dialog
 * intentionally omits unchanged secret fields (it never receives plaintext secrets
 * back from the backend to resubmit them), so "omitted" must mean "keep the old
 * value", not "delete it".
 */
class PluginManagerUpdateConfigTest {

    private static final String PLUGIN_NAME = "plugin-a";
    private static final String ORIGINAL_CONFIG_JSON =
            "{\"baseUrl\":\"https://example.com\",\"apiKey\":\"secret123\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PluginMapper pluginMapper;

    private PluginManager manager() {
        // Constructor param order MUST match PluginManager's field declaration order
        // (Lombok @RequiredArgsConstructor): pluginProperties, pluginMapper, toolRegistry,
        // channelManager, memoryManager, modelProviderService, searchProviderRegistry, workspaceService.
        pluginMapper = mock(PluginMapper.class);
        return new PluginManager(
                mock(PluginProperties.class),
                pluginMapper,
                mock(ToolRegistry.class),
                mock(ChannelManager.class),
                mock(MemoryManager.class),
                mock(ModelProviderService.class),
                new SearchProviderRegistry(List.of()),
                Optional.<WorkspaceService>empty());
    }

    private PluginEntity fixtureEntity(String configJson) {
        PluginEntity entity = new PluginEntity();
        entity.setName(PLUGIN_NAME);
        entity.setConfigJson(configJson);
        entity.setEnabled(true);
        return entity;
    }

    private LoadedPlugin loadedPluginWithRequiredField(String name, String requiredKey) {
        PluginManifest manifest = new PluginManifest();
        manifest.setName(name);
        manifest.setVersion("1.0.0");
        manifest.setType("search");
        manifest.setEntrypoint("x.Y");

        PluginManifest.ConfigField field = new PluginManifest.ConfigField();
        field.setType("string");
        field.setRequired(true);
        field.setSecret(true);
        manifest.setConfig(Map.of(requiredKey, field));

        MateClawPlugin plugin = new MateClawPlugin() {
            @Override public void onLoad(PluginContext ctx) { }
            @Override public void onEnable() { }
            @Override public void onDisable() { }
        };
        return new LoadedPlugin(manifest, plugin,
                new URLClassLoader(new URL[0], getClass().getClassLoader()));
    }

    @SuppressWarnings("unchecked")
    private void seedPlugins(PluginManager manager, LoadedPlugin... loaded) throws Exception {
        Field f = PluginManager.class.getDeclaredField("plugins");
        f.setAccessible(true);
        Map<String, LoadedPlugin> map = (Map<String, LoadedPlugin>) f.get(manager);
        for (LoadedPlugin l : loaded) {
            map.put(l.getManifest().getName(), l);
        }
    }

    @Test
    @DisplayName("merges new values over existing config, preserving omitted keys")
    void mergesNewValuesOverExistingConfig() throws Exception {
        PluginManager manager = manager();
        when(pluginMapper.selectOne(any())).thenReturn(fixtureEntity(ORIGINAL_CONFIG_JSON));

        manager.updateConfig(PLUGIN_NAME, Map.of("baseUrl", "https://new.example.com"));

        ArgumentCaptor<PluginEntity> captor = ArgumentCaptor.forClass(PluginEntity.class);
        org.mockito.Mockito.verify(pluginMapper).updateById(captor.capture());

        Map<String, Object> persisted = objectMapper.readValue(captor.getValue().getConfigJson(), Map.class);
        assertEquals("https://new.example.com", persisted.get("baseUrl"));
        assertEquals("secret123", persisted.get("apiKey"), "omitted secret must be retained, not deleted");
    }

    @Test
    @DisplayName("overwrites a key when explicitly provided, keeping other stored keys untouched")
    void overwritesAKeyWhenExplicitlyProvided() throws Exception {
        PluginManager manager = manager();
        when(pluginMapper.selectOne(any())).thenReturn(fixtureEntity(ORIGINAL_CONFIG_JSON));

        manager.updateConfig(PLUGIN_NAME, Map.of("apiKey", "newSecret456"));

        ArgumentCaptor<PluginEntity> captor = ArgumentCaptor.forClass(PluginEntity.class);
        org.mockito.Mockito.verify(pluginMapper).updateById(captor.capture());

        Map<String, Object> persisted = objectMapper.readValue(captor.getValue().getConfigJson(), Map.class);
        assertEquals("newSecret456", persisted.get("apiKey"));
        assertEquals("https://example.com", persisted.get("baseUrl"));
    }

    @Test
    @DisplayName("required-field check passes when omitted but already present in stored config")
    void requiredFieldCheckPassesWhenOmittedButAlreadyStoredFromBefore() throws Exception {
        PluginManager manager = manager();
        seedPlugins(manager, loadedPluginWithRequiredField(PLUGIN_NAME, "apiKey"));
        when(pluginMapper.selectOne(any())).thenReturn(fixtureEntity(ORIGINAL_CONFIG_JSON));

        assertDoesNotThrow(() ->
                manager.updateConfig(PLUGIN_NAME, Map.of("baseUrl", "https://only-this-changed.com")));
    }

    @Test
    @DisplayName("required-field check still fails when the field has never been configured")
    void requiredFieldCheckStillFailsWhenNeverConfigured() throws Exception {
        PluginManager manager = manager();
        seedPlugins(manager, loadedPluginWithRequiredField(PLUGIN_NAME, "apiKey"));
        when(pluginMapper.selectOne(any())).thenReturn(fixtureEntity("{}"));

        assertThrows(PluginException.class, () ->
                manager.updateConfig(PLUGIN_NAME, Map.of("baseUrl", "https://only-this-changed.com")));
    }
}
