package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link OpenAiModelsPath}: the single source of truth for the
 * OpenAI-compatible models-listing path, shared by discovery
 * ({@code ModelDiscoveryService}) and the failover liveness probe
 * ({@code OpenAiCompatibleListModelsProbe}). A regression here desyncs the two —
 * a provider could be discoverable yet marked unhealthy, or vice versa.
 */
class OpenAiModelsPathTest {

    // ---- default path (no override) ----

    @Test
    @DisplayName("API-root base URL → /v1/models (OpenAI / DeepSeek / Kimi)")
    void apiRootBaseGetsV1Models() {
        assertEquals("/v1/models", OpenAiModelsPath.resolve("https://api.openai.com", null));
        assertEquals("/v1/models", OpenAiModelsPath.resolve("https://api.deepseek.com", Map.of()));
        assertEquals("/v1/models", OpenAiModelsPath.resolve("https://api.moonshot.cn", null));
    }

    @Test
    @DisplayName("base URL ending in /v{N} → only /models (LM Studio /v1, Zhipu /v4, Ark /api/v3)")
    void versionedBaseDropsV1() {
        assertEquals("/models", OpenAiModelsPath.resolve("http://localhost:1234/v1", null));
        assertEquals("/models", OpenAiModelsPath.resolve("https://open.bigmodel.cn/api/paas/v4", null));
        assertEquals("/models", OpenAiModelsPath.resolve("https://ark.cn-beijing.volces.com/api/v3", null));
    }

    @Test
    @DisplayName("/vN mid-path (not a suffix) → /v1/models")
    void midPathVersionDoesNotMatch() {
        assertEquals("/v1/models", OpenAiModelsPath.resolve("https://api.example.com/v1/proxy", null));
    }

    @Test
    @DisplayName("null / blank base URL falls back to /v1/models")
    void nullOrBlankBase() {
        assertEquals("/v1/models", OpenAiModelsPath.resolve(null, null));
        assertEquals("/v1/models", OpenAiModelsPath.resolve("", null));
    }

    // ---- modelsPath override ----

    @Test
    @DisplayName("explicit modelsPath wins over any default, even for a versioned base")
    void explicitOverrideWins() {
        assertEquals("/openai/v1/models",
                OpenAiModelsPath.resolve("https://gw.internal", Map.of("modelsPath", "/openai/v1/models")));
        assertEquals("/custom/models",
                OpenAiModelsPath.resolve("https://ark.example.com/api/v3", Map.of("modelsPath", "/custom/models")));
    }

    @Test
    @DisplayName("modelsPath without a leading slash is normalized to one")
    void overrideGetsLeadingSlash() {
        assertEquals("/api/models",
                OpenAiModelsPath.resolve("https://gw.internal", Map.of("modelsPath", "api/models")));
    }

    @Test
    @DisplayName("blank modelsPath is ignored and falls back to the default")
    void blankOverrideIgnored() {
        assertEquals("/v1/models",
                OpenAiModelsPath.resolve("https://api.example.com", Map.of("modelsPath", "   ")));
    }
}
