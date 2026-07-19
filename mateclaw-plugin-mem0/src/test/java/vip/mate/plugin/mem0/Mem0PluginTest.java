package vip.mate.plugin.mem0;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mate.plugin.api.PluginContext;
import vip.mate.plugin.api.PluginException;
import vip.mate.plugin.api.memory.PluginMemoryProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mem0PluginTest {

    @Test
    void onLoad_readsConfigAndRegistersProvider() {
        Map<String, Object> config = new HashMap<>();
        config.put("baseUrl", "http://localhost:8080");
        config.put("apiKey", "secret");
        config.put("searchEnabled", true);
        config.put("syncEnabled", false);
        config.put("maxResults", 7);
        config.put("timeoutMs", 5000);

        AtomicReference<PluginMemoryProvider> registered = new AtomicReference<>();
        PluginContext ctx = new StubContext(config, registered);

        Mem0Plugin plugin = new Mem0Plugin();
        plugin.onLoad(ctx);
        plugin.onEnable();

        PluginMemoryProvider p = registered.get();
        assertThat(p).isNotNull();
        assertThat(p.id()).isEqualTo("mem0");
        assertThat(p.isAvailable()).isTrue(); // baseUrl set + searchEnabled true

        plugin.onDisable();
    }

    @Test
    void onLoad_withMissingBaseUrl_stillRegistersButUnavailable() {
        // No baseUrl configured — plugin should register but report unavailable
        // rather than throwing.
        Map<String, Object> config = new HashMap<>(); // empty
        AtomicReference<PluginMemoryProvider> registered = new AtomicReference<>();
        PluginContext ctx = new StubContext(config, registered);

        Mem0Plugin plugin = new Mem0Plugin();
        plugin.onLoad(ctx);

        PluginMemoryProvider p = registered.get();
        assertThat(p).isNotNull();
        assertThat(p.isAvailable()).isFalse();
    }

    @Test
    void onLoad_appliesDefaultsToOptionalConfig() {
        // Only baseUrl set — searchEnabled/syncEnabled/maxResults/timeoutMs
        // should default.
        Map<String, Object> config = new HashMap<>();
        config.put("baseUrl", "http://localhost:8080");

        AtomicReference<PluginMemoryProvider> registered = new AtomicReference<>();
        PluginContext ctx = new StubContext(config, registered);

        Mem0Plugin plugin = new Mem0Plugin();
        plugin.onLoad(ctx);

        // Verify defaults indirectly: searchEnabled and syncEnabled both default
        // to true → isAvailable() must be true.
        assertThat(registered.get().isAvailable()).isTrue();
    }

    @Test
    void onLoad_throwsWhenContextRejectsSecondProvider() {
        // Simulate the platform's single-select constraint by throwing from
        // registerMemoryProvider.
        Map<String, Object> config = new HashMap<>();
        config.put("baseUrl", "http://localhost:8080");
        AtomicReference<PluginMemoryProvider> registered = new AtomicReference<>();
        PluginContext ctx = new StubContext(config, registered) {
            @Override
            public void registerMemoryProvider(PluginMemoryProvider provider) {
                throw new PluginException("Only one external memory provider allowed");
            }
        };

        Mem0Plugin plugin = new Mem0Plugin();
        assertThatThrownBy(() -> plugin.onLoad(ctx))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Only one");
    }

    /**
     * Minimal PluginContext stub: only getConfig / registerMemoryProvider /
     * getLogger are exercised by Mem0Plugin; everything else throws.
     */
    static class StubContext implements PluginContext {
        private final Map<String, Object> config;
        private final AtomicReference<PluginMemoryProvider> registered;

        StubContext(Map<String, Object> config, AtomicReference<PluginMemoryProvider> registered) {
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
            return LoggerFactory.getLogger("test.Mem0Plugin");
        }

        @Override
        public void registerMemoryProvider(PluginMemoryProvider provider) {
            registered.set(provider);
        }

        // The remaining methods are not used by Mem0Plugin; stub them out.

        @Override public void registerTool(org.springframework.ai.tool.ToolCallback tool) { throw new UnsupportedOperationException(); }
        @Override public void registerTool(org.springframework.ai.tool.ToolCallback tool, java.util.function.Supplier<Boolean> availabilityCheck) { throw new UnsupportedOperationException(); }
        @Override public void registerProvider(String providerId, org.springframework.ai.chat.model.ChatModel chatModel) { throw new UnsupportedOperationException(); }
        @Override public void registerChannel(vip.mate.plugin.api.channel.PluginChannelAdapter channel) { throw new UnsupportedOperationException(); }
        @Override public void registerSearchProvider(vip.mate.plugin.api.search.PluginSearchProvider provider) { throw new UnsupportedOperationException(); }
    }
}
