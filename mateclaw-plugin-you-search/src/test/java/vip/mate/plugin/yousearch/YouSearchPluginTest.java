package vip.mate.plugin.yousearch;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.plugin.api.PluginContext;
import vip.mate.plugin.api.PluginException;
import vip.mate.plugin.api.channel.PluginChannelAdapter;
import vip.mate.plugin.api.memory.PluginMemoryProvider;
import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.api.search.PluginSearchQuery;
import vip.mate.plugin.api.search.PluginSearchResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YouSearchPluginTest {

    @Test
    void onLoad_readsConfigAndRegistersProvider() {
        Map<String, Object> config = new HashMap<>();
        config.put("apiKey", "secret");
        config.put("timeoutMs", 8000);

        AtomicReference<PluginSearchProvider> registered = new AtomicReference<>();
        PluginContext ctx = new StubContext(config, registered);

        YouSearchPlugin plugin = new YouSearchPlugin();
        plugin.onLoad(ctx);
        plugin.onEnable();

        PluginSearchProvider p = registered.get();
        assertThat(p).isNotNull();
        assertThat(p.id()).isEqualTo("you");
        assertThat(p.label()).isEqualTo("You.com");
        assertThat(p.requiresCredential()).isTrue();
        assertThat(p.isAvailable()).isTrue(); // apiKey set

        plugin.onDisable();
    }

    @Test
    void onLoad_withMissingApiKey_stillRegistersButUnavailable() {
        // No apiKey configured — plugin should register but report unavailable
        // rather than throwing, so it can be enabled later via config.
        Map<String, Object> config = new HashMap<>(); // empty
        AtomicReference<PluginSearchProvider> registered = new AtomicReference<>();
        PluginContext ctx = new StubContext(config, registered);

        YouSearchPlugin plugin = new YouSearchPlugin();
        plugin.onLoad(ctx);

        PluginSearchProvider p = registered.get();
        assertThat(p).isNotNull();
        assertThat(p.isAvailable()).isFalse();
    }

    @Test
    void onLoad_appliesDefaultTimeout() {
        // Only apiKey set — timeoutMs should default (no exception, available).
        Map<String, Object> config = new HashMap<>();
        config.put("apiKey", "secret");

        AtomicReference<PluginSearchProvider> registered = new AtomicReference<>();
        PluginContext ctx = new StubContext(config, registered);

        YouSearchPlugin plugin = new YouSearchPlugin();
        plugin.onLoad(ctx);

        assertThat(registered.get().isAvailable()).isTrue();
    }

    @Test
    void onLoad_throwsWhenContextRejectsClashingId() {
        // Simulate the platform's unique-id constraint by throwing from
        // registerSearchProvider.
        Map<String, Object> config = new HashMap<>();
        config.put("apiKey", "secret");
        AtomicReference<PluginSearchProvider> registered = new AtomicReference<>();
        PluginContext ctx = new StubContext(config, registered) {
            @Override
            public void registerSearchProvider(PluginSearchProvider provider) {
                throw new PluginException("provider id already taken: you");
            }
        };

        YouSearchPlugin plugin = new YouSearchPlugin();
        assertThatThrownBy(() -> plugin.onLoad(ctx))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    void provider_mapsQueryFieldsOntoApiCall() {
        // Wire the provider against a stub client to verify the SPI query
        // is translated into the client call — freshness/language/count pass
        // through unchanged (same vocabulary on both sides).
        RecordingClient client = new RecordingClient();
        YouSearchConfig config = new YouSearchConfig("k", null, null, null, 15000);
        YouSearchProvider provider = new YouSearchProvider(config, client);

        List<PluginSearchResult> out = provider.search(
                new PluginSearchQuery("spring ai release", "month", "zh-CN", 5));

        assertThat(client.query).isEqualTo("spring ai release");
        assertThat(client.count).isEqualTo(5);
        assertThat(client.freshness).isEqualTo("month");
        assertThat(client.language).isEqualTo("zh-CN");

        assertThat(out).hasSize(1);
        PluginSearchResult r = out.get(0);
        assertThat(r.title()).isEqualTo("t");
        assertThat(r.url()).isEqualTo("u");
        assertThat(r.snippet()).isEqualTo("s");
        assertThat(r.source()).isEqualTo("src");
        assertThat(r.date()).isEqualTo("d");
    }

    /**
     * Stub client that records the last call and returns one canned hit.
     */
    private static final class RecordingClient extends YouSearchClient {
        String query;
        Integer count;
        String freshness;
        String language;

        RecordingClient() {
            super(new YouSearchConfig("k", "http://127.0.0.1:1", null, null, 100));
        }

        @Override
        List<YouSearchHit> search(String query, Integer count, String freshness, String language) {
            this.query = query;
            this.count = count;
            this.freshness = freshness;
            this.language = language;
            return List.of(new YouSearchHit("t", "u", "s", "src", "d"));
        }
    }

    /**
     * Minimal PluginContext stub: only getConfig / registerSearchProvider /
     * getLogger are exercised by YouSearchPlugin; everything else throws.
     */
    static class StubContext implements PluginContext {
        private final Map<String, Object> config;
        private final AtomicReference<PluginSearchProvider> registered;

        StubContext(Map<String, Object> config, AtomicReference<PluginSearchProvider> registered) {
            this.config = config;
            this.registered = registered;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getConfig(String key, Class<T> type) {
            Object v = config.get(key);
            if (v == null) return null;
            if (type.isInstance(v)) return (T) v;
            // Best-effort scalar coercion for Integer/Boolean from String/Number
            if (type == Integer.class && v instanceof Number n) return (T) (Integer) n.intValue();
            if (type == Boolean.class && v instanceof Boolean b) return (T) b;
            return null;
        }

        @Override
        public Logger getLogger() {
            return LoggerFactory.getLogger("test.YouSearchPlugin");
        }

        @Override
        public void registerSearchProvider(PluginSearchProvider provider) {
            registered.set(provider);
        }

        // The remaining methods are not used by YouSearchPlugin; stub them out.

        @Override public void registerTool(ToolCallback tool) { throw new UnsupportedOperationException(); }
        @Override public void registerTool(ToolCallback tool, Supplier<Boolean> availabilityCheck) { throw new UnsupportedOperationException(); }
        @Override public void registerProvider(String providerId, ChatModel chatModel) { throw new UnsupportedOperationException(); }
        @Override public void registerChannel(PluginChannelAdapter channel) { throw new UnsupportedOperationException(); }
        @Override public void registerMemoryProvider(PluginMemoryProvider provider) { throw new UnsupportedOperationException(); }
    }
}
