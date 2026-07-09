package vip.mate.system.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.plugin.PluginManager;
import vip.mate.system.model.SearchProviderCatalogResponse;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.repository.SystemSettingMapper;
import vip.mate.tool.search.SearchProvider;
import vip.mate.tool.search.SearchProviderRegistry;
import vip.mate.tool.search.SearchResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SystemSettingService#getSearchProviderCatalog: aggregates SearchProviderRegistry
 * (builtin + plugin providers) with PluginManager (owning-plugin lookup) into the
 * catalog payload the settings UI renders.
 */
@ExtendWith(MockitoExtension.class)
class SystemSettingServiceCatalogTest {

    @Mock private SystemSettingMapper mapper;
    @Mock private PluginManager pluginManager;

    private SystemSettingService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                SystemSettingEntity.class);
    }

    private static SearchProvider stub(String id, int order, boolean credentialed, boolean available) {
        return new SearchProvider() {
            @Override public String id() { return id; }
            @Override public String label() { return id + "-label"; }
            @Override public boolean requiresCredential() { return credentialed; }
            @Override public int autoDetectOrder() { return order; }
            @Override public boolean isAvailable(SystemSettingsDTO config) { return available; }
            @Override public List<SearchResult> search(String query, SystemSettingsDTO config) { return List.of(); }
        };
    }

    @BeforeEach
    void setUp() {
        when(mapper.selectOne(any())).thenReturn(null); // no DB rows -> defaults used by getSearchSettings()
    }

    @Test
    @DisplayName("marks builtin providers as builtin=true with no pluginName")
    void builtinEntry() {
        SearchProviderRegistry registry = new SearchProviderRegistry(List.of(stub("serper", 300, true, false)));
        service = new SystemSettingService(mapper, registry, pluginManager);

        SearchProviderCatalogResponse catalog = service.getSearchProviderCatalog();

        assertEquals(1, catalog.providers().size());
        var entry = catalog.providers().get(0);
        assertEquals("serper", entry.id());
        assertTrue(entry.builtin());
        assertNull(entry.pluginName());
        assertFalse(entry.available()); // not configured
    }

    @Test
    @DisplayName("marks plugin-registered providers as builtin=false with the owning pluginName")
    void pluginEntry() {
        SearchProviderRegistry registry = new SearchProviderRegistry(List.of());
        registry.registerPluginProvider(stub("my-search", 500, true, true));
        when(pluginManager.getPluginNameForSearchProvider("my-search")).thenReturn("my-plugin");
        service = new SystemSettingService(mapper, registry, pluginManager);

        SearchProviderCatalogResponse catalog = service.getSearchProviderCatalog();

        var entry = catalog.providers().get(0);
        assertEquals("my-search", entry.id());
        assertFalse(entry.builtin());
        assertEquals("my-plugin", entry.pluginName());
        assertTrue(entry.available());
    }

    @Test
    @DisplayName("mixed catalog: builtin and plugin providers both appear, correctly labeled and ordered")
    void mixedBuiltinAndPluginCatalog() {
        SearchProviderRegistry registry = new SearchProviderRegistry(List.of(
                stub("serper", 300, true, true),
                stub("duckduckgo", 100, false, true)));
        registry.registerPluginProvider(stub("my-search", 200, true, true));
        when(pluginManager.getPluginNameForSearchProvider("my-search")).thenReturn("my-plugin");
        service = new SystemSettingService(mapper, registry, pluginManager);

        SearchProviderCatalogResponse catalog = service.getSearchProviderCatalog();

        assertEquals(3, catalog.providers().size());
        // Sorted by autoDetectOrder ascending: duckduckgo(100), my-search(200), serper(300)
        assertEquals("duckduckgo", catalog.providers().get(0).id());
        assertTrue(catalog.providers().get(0).builtin());
        assertNull(catalog.providers().get(0).pluginName());

        assertEquals("my-search", catalog.providers().get(1).id());
        assertFalse(catalog.providers().get(1).builtin());
        assertEquals("my-plugin", catalog.providers().get(1).pluginName());

        assertEquals("serper", catalog.providers().get(2).id());
        assertTrue(catalog.providers().get(2).builtin());
        assertNull(catalog.providers().get(2).pluginName());
    }

    @Test
    @DisplayName("surfaces the resolved provider id and source alongside the catalog")
    void resolvedSurfaced() {
        SearchProviderRegistry registry = new SearchProviderRegistry(List.of(stub("duckduckgo", 100, false, true)));
        service = new SystemSettingService(mapper, registry, pluginManager);

        SearchProviderCatalogResponse catalog = service.getSearchProviderCatalog();

        assertEquals("duckduckgo", catalog.resolvedId());
        assertEquals("keyless-fallback", catalog.resolvedSource());
    }

    @Test
    @DisplayName("resolvedId/resolvedSource are null when no provider is available at all")
    void resolvedNullWhenNothingAvailable() {
        SearchProviderRegistry registry = new SearchProviderRegistry(List.of(stub("serper", 300, true, false)));
        service = new SystemSettingService(mapper, registry, pluginManager);

        SearchProviderCatalogResponse catalog = service.getSearchProviderCatalog();

        assertNull(catalog.resolvedId());
        assertNull(catalog.resolvedSource());
    }
}
