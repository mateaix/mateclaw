package vip.mate.tool.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugin-provider mutability of {@link SearchProviderRegistry}:
 * plugin JARs register/unregister providers at runtime; the registry must merge
 * them with the Spring-injected built-ins and reject id conflicts.
 */
class SearchProviderRegistryPluginTest {

    /** Minimal stub standing in for both built-in and plugin-bridged providers. */
    private static SearchProvider stub(String id, int order, boolean credentialed, boolean available) {
        return new SearchProvider() {
            @Override public String id() { return id; }
            @Override public String label() { return id; }
            @Override public boolean requiresCredential() { return credentialed; }
            @Override public int autoDetectOrder() { return order; }
            @Override public boolean isAvailable(SystemSettingsDTO config) { return available; }
            @Override public List<SearchResult> search(String query, SystemSettingsDTO config) { return List.of(); }
        };
    }

    private static SearchProviderRegistry registryWithBuiltins() {
        // Mirrors the real built-in landscape: one credentialed, one keyless.
        return new SearchProviderRegistry(List.of(
                stub("serper", 300, true, false),      // credentialed but NOT configured
                stub("duckduckgo", 100, false, true)   // keyless, available
        ));
    }

    @Test
    @DisplayName("registered plugin provider shows up in allSorted, ordered by autoDetectOrder")
    void pluginProviderAppearsInMergedSortedView() {
        SearchProviderRegistry registry = registryWithBuiltins();
        SearchProvider plugin = stub("my-search", 500, true, true);

        registry.registerPluginProvider(plugin);

        List<SearchProvider> all = registry.allSorted();
        assertEquals(3, all.size());
        assertEquals("duckduckgo", all.get(0).id()); // order 100
        assertEquals("serper", all.get(1).id());     // order 300
        assertSame(plugin, all.get(2));              // order 500
    }

    @Test
    @DisplayName("getById finds plugin providers")
    void getByIdFindsPluginProvider() {
        SearchProviderRegistry registry = registryWithBuiltins();
        SearchProvider plugin = stub("my-search", 500, true, true);
        registry.registerPluginProvider(plugin);

        assertSame(plugin, registry.getById("my-search"));
    }

    @Test
    @DisplayName("plugin id clashing with a built-in id is rejected")
    void builtinIdConflictRejected() {
        SearchProviderRegistry registry = registryWithBuiltins();

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub("serper", 500, true, true)));
    }

    @Test
    @DisplayName("plugin id clashing with an already-registered plugin id is rejected")
    void pluginIdConflictRejected() {
        SearchProviderRegistry registry = registryWithBuiltins();
        registry.registerPluginProvider(stub("my-search", 500, true, true));

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub("my-search", 501, true, true)));
    }

    @Test
    @DisplayName("blank or null plugin id is rejected")
    void blankIdRejected() {
        SearchProviderRegistry registry = registryWithBuiltins();

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub("  ", 500, true, true)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub(null, 500, true, true)));
    }

    @Test
    @DisplayName("id with leading/trailing whitespace is rejected, not trimmed")
    void paddedIdRejected() {
        SearchProviderRegistry registry = registryWithBuiltins();

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub(" my-search", 500, true, true)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub("my-search ", 500, true, true)));
    }

    @Test
    @DisplayName("case-variant of a built-in id is rejected (no visual spoofing)")
    void caseVariantOfBuiltinRejected() {
        SearchProviderRegistry registry = registryWithBuiltins();

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub("Serper", 500, true, true)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub("DUCKDUCKGO", 500, true, true)));
    }

    @Test
    @DisplayName("case-variant of an already-registered plugin id is rejected")
    void caseVariantOfPluginIdRejected() {
        SearchProviderRegistry registry = registryWithBuiltins();
        registry.registerPluginProvider(stub("my-search", 500, true, true));

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub("My-Search", 501, true, true)));
    }

    @Test
    @DisplayName("isPluginProvider distinguishes built-in ids from plugin-registered ids")
    void isPluginProviderDistinguishesSource() {
        SearchProviderRegistry registry = registryWithBuiltins();
        registry.registerPluginProvider(stub("my-search", 500, true, true));

        assertTrue(registry.isPluginProvider("my-search"));
        assertFalse(registry.isPluginProvider("serper"));
        assertFalse(registry.isPluginProvider("duckduckgo"));
        assertFalse(registry.isPluginProvider("does-not-exist"));
    }

    @Test
    @DisplayName("resolve honours an explicitly configured plugin provider")
    void resolvePicksConfiguredPluginProvider() {
        SearchProviderRegistry registry = registryWithBuiltins();
        SearchProvider plugin = stub("my-search", 500, true, true);
        registry.registerPluginProvider(plugin);

        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSearchProvider("my-search");

        SearchProviderRegistry.ResolvedProvider resolved = registry.resolve(config);
        assertSame(plugin, resolved.provider());
        assertEquals("configured", resolved.source());
    }

    @Test
    @DisplayName("resolve auto-detects an available credentialed plugin provider")
    void resolveAutoDetectsPluginProvider() {
        SearchProviderRegistry registry = registryWithBuiltins();
        SearchProvider plugin = stub("my-search", 500, true, true);
        registry.registerPluginProvider(plugin);

        // No explicit provider configured; serper (credentialed) is unavailable,
        // so auto-detect must reach the plugin provider before keyless fallback.
        SearchProviderRegistry.ResolvedProvider resolved = registry.resolve(new SystemSettingsDTO());
        assertSame(plugin, resolved.provider());
        assertEquals("auto-detect", resolved.source());
    }

    @Test
    @DisplayName("after unregister, an explicitly configured plugin id falls back to auto-detect")
    void unregisteredConfiguredProviderFallsBackToAutoDetect() {
        SearchProviderRegistry registry = registryWithBuiltins();
        registry.registerPluginProvider(stub("my-search", 500, true, true));
        registry.unregisterPluginProvider("my-search");

        assertNull(registry.getById("my-search"));

        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSearchProvider("my-search");
        SearchProviderRegistry.ResolvedProvider resolved = registry.resolve(config);
        // Plugin gone; keyless duckduckgo is the only available provider left.
        assertEquals("duckduckgo", resolved.provider().id());
        assertEquals("keyless-fallback", resolved.source());
    }
}
