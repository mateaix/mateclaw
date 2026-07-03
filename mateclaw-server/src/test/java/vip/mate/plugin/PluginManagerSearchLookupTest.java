package vip.mate.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelManager;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginContext;
import vip.mate.plugin.api.PluginManifest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * PluginManager#getPluginNameForSearchProvider: reverse-lookup which loaded
 * plugin registered a given search provider id, used by the settings catalog
 * endpoint to show "managed by plugin X" (issue #477).
 */
class PluginManagerSearchLookupTest {

    private PluginManager manager() {
        // Constructor param order MUST match PluginManager's field declaration order
        // (Lombok @RequiredArgsConstructor): pluginProperties, pluginMapper, toolRegistry,
        // channelManager, memoryManager, modelProviderService, searchProviderRegistry, workspaceService.
        return new PluginManager(
                mock(PluginProperties.class),
                mock(PluginMapper.class),
                mock(ToolRegistry.class),
                mock(ChannelManager.class),
                mock(MemoryManager.class),
                mock(ModelProviderService.class),
                new SearchProviderRegistry(List.of()),
                Optional.<WorkspaceService>empty());
    }

    private LoadedPlugin loadedPluginWithSearchIds(String name, String... searchIds) {
        PluginManifest manifest = new PluginManifest();
        manifest.setName(name);
        manifest.setVersion("1.0.0");
        manifest.setType("search");
        manifest.setEntrypoint("x.Y");
        MateClawPlugin plugin = new MateClawPlugin() {
            @Override public void onLoad(PluginContext ctx) { }
            @Override public void onEnable() { }
            @Override public void onDisable() { }
        };
        LoadedPlugin loaded = new LoadedPlugin(manifest, plugin,
                new URLClassLoader(new URL[0], getClass().getClassLoader()));
        loaded.getRegisteredSearchProviders().addAll(List.of(searchIds));
        return loaded;
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
    @DisplayName("finds the plugin name that registered the given search provider id")
    void findsOwningPlugin() throws Exception {
        PluginManager manager = manager();
        seedPlugins(manager, loadedPluginWithSearchIds("plugin-a", "my-search"));

        assertEquals("plugin-a", manager.getPluginNameForSearchProvider("my-search"));
    }

    @Test
    @DisplayName("returns null when no loaded plugin registered that id")
    void returnsNullWhenNotFound() throws Exception {
        PluginManager manager = manager();
        seedPlugins(manager, loadedPluginWithSearchIds("plugin-a", "other-search"));

        assertNull(manager.getPluginNameForSearchProvider("my-search"));
    }

    @Test
    @DisplayName("returns null for a built-in id no plugin ever registered")
    void returnsNullForBuiltinId() throws Exception {
        PluginManager manager = manager();

        assertNull(manager.getPluginNameForSearchProvider("serper"));
    }
}
