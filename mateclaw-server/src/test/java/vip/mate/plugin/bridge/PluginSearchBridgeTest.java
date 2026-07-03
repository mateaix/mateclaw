package vip.mate.plugin.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.api.search.PluginSearchQuery;
import vip.mate.plugin.api.search.PluginSearchResult;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.search.SearchQuery;
import vip.mate.tool.search.SearchResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginSearchBridge} adapts the self-contained plugin SPI
 * ({@code PluginSearchProvider}) to the platform's {@code SearchProvider}
 * without leaking server types into plugin land.
 */
class PluginSearchBridgeTest {

    @Test
    @DisplayName("query fields pass through and results are converted with the plugin's providerId")
    void convertsQueryAndResults() {
        AtomicReference<PluginSearchQuery> received = new AtomicReference<>();
        PluginSearchProvider plugin = new PluginSearchProvider() {
            @Override public String id() { return "my-search"; }
            @Override public String label() { return "My Search"; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<PluginSearchResult> search(PluginSearchQuery query) {
                received.set(query);
                return List.of(new PluginSearchResult(
                        "T1", "https://example.com/a", "snippet-1", "example.com", "2026-07-01"));
            }
        };

        PluginSearchBridge bridge = new PluginSearchBridge(plugin);
        List<SearchResult> results = bridge.search(
                new SearchQuery("kw", "week", "zh-CN", 3), new SystemSettingsDTO());

        assertEquals("kw", received.get().query());
        assertEquals("week", received.get().freshness());
        assertEquals("zh-CN", received.get().language());
        assertEquals(3, received.get().count());

        assertEquals(1, results.size());
        SearchResult r = results.get(0);
        assertEquals("T1", r.getTitle());
        assertEquals("https://example.com/a", r.getUrl());
        assertEquals("snippet-1", r.getSnippet());
        assertEquals("example.com", r.getSource());
        assertEquals("2026-07-01", r.getDate());
        assertEquals("my-search", r.getProviderId());
    }

    @Test
    @DisplayName("count is clamped via SearchQuery.resolvedCount before reaching the plugin")
    void countIsClamped() {
        AtomicReference<PluginSearchQuery> received = new AtomicReference<>();
        PluginSearchBridge bridge = new PluginSearchBridge(stub(q -> {
            received.set(q);
            return List.of();
        }));

        bridge.search(new SearchQuery("kw", null, null, 99), new SystemSettingsDTO());
        assertEquals(10, received.get().count()); // MAX_COUNT

        bridge.search(new SearchQuery("kw", null, null, null), new SystemSettingsDTO());
        assertEquals(5, received.get().count()); // DEFAULT_COUNT
    }

    @Test
    @DisplayName("delegates id/label/order/credential and maps isAvailable() ignoring the DTO")
    void delegatesMetadata() {
        PluginSearchBridge bridge = new PluginSearchBridge(stub(q -> List.of()));
        assertEquals("stub-search", bridge.id());
        assertEquals("Stub Search", bridge.label());
        assertTrue(bridge.requiresCredential());
        assertEquals(500, bridge.autoDetectOrder());
        assertTrue(bridge.isAvailable(new SystemSettingsDTO()));
    }

    @Test
    @DisplayName("a null result list from a sloppy plugin is normalised to empty")
    void nullResultListNormalised() {
        PluginSearchBridge bridge = new PluginSearchBridge(stub(q -> null));
        List<SearchResult> results = bridge.search(SearchQuery.of("kw"), new SystemSettingsDTO());
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("plugin exceptions propagate so WebSearchService's fallback chain can react")
    void exceptionsPropagate() {
        PluginSearchBridge bridge = new PluginSearchBridge(stub(q -> {
            throw new IllegalStateException("plugin boom");
        }));
        assertThrows(IllegalStateException.class,
                () -> bridge.search(SearchQuery.of("kw"), new SystemSettingsDTO()));
    }

    @Test
    @DisplayName("metadata is snapshotted at construction — plugin code never runs on sort/catalog reads")
    void metadataSnapshottedAtConstruction() {
        java.util.concurrent.atomic.AtomicInteger metadataCalls = new java.util.concurrent.atomic.AtomicInteger();
        PluginSearchProvider delegate = new PluginSearchProvider() {
            @Override public String id() { metadataCalls.incrementAndGet(); return "snap-search"; }
            @Override public String label() { metadataCalls.incrementAndGet(); return "Snap Search"; }
            @Override public boolean requiresCredential() { metadataCalls.incrementAndGet(); return true; }
            @Override public int autoDetectOrder() { metadataCalls.incrementAndGet(); return 500; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<PluginSearchResult> search(PluginSearchQuery query) { return List.of(); }
        };

        PluginSearchBridge bridge = new PluginSearchBridge(delegate);
        int callsAfterConstruction = metadataCalls.get();

        // Repeated reads (what resolve()'s sort comparator and the catalog do) must
        // serve the snapshot, not re-enter plugin code.
        for (int i = 0; i < 3; i++) {
            assertEquals("snap-search", bridge.id());
            assertEquals("Snap Search", bridge.label());
            assertTrue(bridge.requiresCredential());
            assertEquals(500, bridge.autoDetectOrder());
        }
        assertEquals(callsAfterConstruction, metadataCalls.get(),
                "metadata getters must not invoke plugin code after construction");
    }

    @Test
    @DisplayName("a throwing isAvailable() degrades to unavailable instead of breaking resolve()")
    void throwingIsAvailableDegradesToFalse() {
        PluginSearchProvider delegate = new PluginSearchProvider() {
            @Override public String id() { return "broken-search"; }
            @Override public String label() { return "Broken Search"; }
            @Override public boolean isAvailable() { throw new IllegalStateException("availability boom"); }
            @Override public List<PluginSearchResult> search(PluginSearchQuery query) { return List.of(); }
        };

        PluginSearchBridge bridge = new PluginSearchBridge(delegate);

        assertFalse(bridge.isAvailable(new SystemSettingsDTO()),
                "isAvailable runs inside resolve() on every web_search call with no per-provider guard — a plugin exception must degrade to false, not propagate");
    }

    // ---- helpers ----

    private interface SearchFn {
        List<PluginSearchResult> apply(PluginSearchQuery q);
    }

    private static PluginSearchProvider stub(SearchFn fn) {
        return new PluginSearchProvider() {
            @Override public String id() { return "stub-search"; }
            @Override public String label() { return "Stub Search"; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<PluginSearchResult> search(PluginSearchQuery query) {
                return fn.apply(query);
            }
        };
    }
}
