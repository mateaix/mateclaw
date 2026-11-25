package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.llm.cache.AnthropicCacheOptionsFactory;
import vip.mate.llm.cache.CacheProperties;
import vip.mate.llm.model.ModelConfigEntity;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicSamplingMigrationTest {
    private final AnthropicChatModelBuilder builder = new AnthropicChatModelBuilder(
            null, null, null, null, new AnthropicCacheOptionsFactory(new CacheProperties()));

    @AfterEach void clearThinking() { ThinkingLevelHolder.clear(); }

    @Test void omitsLegacySamplingForAllAnthropicModels() {
        ModelConfigEntity config = new ModelConfigEntity();
        config.setModelName("claude-sonnet-4-5");
        config.setTemperature(0.7);
        config.setTopP(0.9);
        var options = builder.buildAnthropicOptions(config);
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNull(options.getTopK());
    }

    @Test void thinkingBudgetDoesNotReintroduceSampling() {
        ThinkingLevelHolder.set("low");
        ModelConfigEntity config = new ModelConfigEntity();
        config.setModelName("claude-sonnet-4-5");
        var options = builder.buildAnthropicOptions(config);
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertTrue(options.getMaxTokens() >= 8192);
    }
}
