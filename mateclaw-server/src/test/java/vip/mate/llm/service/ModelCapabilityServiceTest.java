package vip.mate.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.llm.service.ModelCapabilityService.Modality;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pinpoint regression tests for {@link ModelCapabilityService}.
 *
 * <p>Per-model granularity is the whole point — the prior hardcoded
 * {@code n.contains("glm") && n.contains("v")} matcher (issue #44) collapsed
 * {@code glm-4v} and {@code glm-4v-plus} into the same bucket even though only
 * the latter accepts video. The cases below pin that boundary so a future
 * "let's just add another contains() rule" cleanup can't bring the bug back.
 */
class ModelCapabilityServiceTest {

    private final ModelCapabilityService service = new ModelCapabilityService();

    // ---------- Heuristic table: per-model granularity ----------

    @Test
    @DisplayName("glm-4v-plus → VIDEO; glm-4v → no VIDEO (issue #44 root cause)")
    void glm4v_videoCapabilityDiffers() {
        assertTrue(service.supports("glm-4v-plus", null, Modality.VIDEO),
                "glm-4v-plus is multimodal incl. video");
        assertFalse(service.supports("glm-4v", null, Modality.VIDEO),
                "plain glm-4v is image-only — must NOT pass through video Media");
        assertFalse(service.supports("glm-4v-flash", null, Modality.VIDEO),
                "glm-4v-flash is image-only");
        // All three still support vision
        assertTrue(service.supports("glm-4v-plus", null, Modality.VISION));
        assertTrue(service.supports("glm-4v", null, Modality.VISION));
        assertTrue(service.supports("glm-4v-flash", null, Modality.VISION));
    }

    @Test
    @DisplayName("glm-5v-turbo / glm-4.5v / glm-4.1v lines all support VIDEO")
    void glmNewGenerations_supportVideo() {
        // glm-5v-turbo regression: original heuristic table only had glm-4v lineage,
        // so a user uploading a video to glm-5v-turbo got a "model unsupported" notice
        // even though Zhipu's 5V line is built for video understanding.
        assertTrue(service.supports("glm-5v-turbo", null, Modality.VIDEO),
                "glm-5v-turbo is Zhipu's video-understanding model — must accept video");
        assertTrue(service.supports("glm-5v-flash", null, Modality.VIDEO));
        assertTrue(service.supports("glm-5v", null, Modality.VIDEO));
        assertTrue(service.supports("glm-4.5v", null, Modality.VIDEO));
        assertTrue(service.supports("glm-4.1v-thinking-flashx", null, Modality.VIDEO));
    }

    @Test
    @DisplayName("Longest-prefix-wins: glm-4v-plus does NOT degrade to glm-4v entry")
    void longestPrefixWins() {
        // If matcher used shortest-or-first, "glm-4v-plus" might match the "glm-4v" entry
        // first and lose its VIDEO modality. Pin the iteration order independence.
        EnumSet<Modality> caps = service.resolve("glm-4v-plus", null);
        assertTrue(caps.contains(Modality.VIDEO), "longest prefix glm-4v-plus must win");
    }

    @Test
    @DisplayName("Qwen-VL family: max → VIDEO, plus → image-only")
    void qwenVl_familyDiffers() {
        assertTrue(service.supports("qwen-vl-max", null, Modality.VIDEO));
        assertFalse(service.supports("qwen-vl-plus", null, Modality.VIDEO));
        assertTrue(service.supports("qwen-vl-plus", null, Modality.VISION));
    }

    @Test
    @DisplayName("Qwen omni line accepts vision + video + audio")
    void qwenOmni_fullyMultimodal() {
        EnumSet<Modality> caps = service.resolve("qwen3-omni", null);
        assertTrue(caps.contains(Modality.VISION));
        assertTrue(caps.contains(Modality.VIDEO));
        assertTrue(caps.contains(Modality.AUDIO));
    }

    @Test
    @DisplayName("OpenAI: vision yes across the line, but native video NO (API limitation)")
    void openai_neverNativeVideo() {
        // The Chat Completions / Responses APIs do not accept video files for any
        // OpenAI model as of 2026-04. Granting VIDEO would cause patchVideoMediaContent
        // to send video_url, and OpenAI would 400. Pin this so a future "marketing-led"
        // table edit can't silently re-introduce the failure mode.
        assertTrue(service.supports("gpt-5", null, Modality.VISION));
        assertTrue(service.supports("gpt-4.1", null, Modality.VISION));
        assertTrue(service.supports("gpt-4o", null, Modality.VISION));
        assertTrue(service.supports("gpt-4o-mini", null, Modality.VISION));
        assertFalse(service.supports("gpt-5", null, Modality.VIDEO));
        assertFalse(service.supports("gpt-4.1", null, Modality.VIDEO));
        assertFalse(service.supports("gpt-4o", null, Modality.VIDEO));
        assertFalse(service.supports("gpt-4o-mini", null, Modality.VIDEO));
    }

    @Test
    @DisplayName("DeepSeek V4 / V4-Pro → VIDEO; V3 (text-only) gets nothing")
    void deepseekV4_supportsVideo() {
        // DeepSeek V4 (Apr 2026) introduced native multimodal incl. video to the line.
        // V3 and earlier remain text-only and must NOT match the V4 entry.
        assertTrue(service.supports("deepseek-v4", null, Modality.VIDEO));
        assertTrue(service.supports("deepseek-v4-pro", null, Modality.VIDEO));
        assertTrue(service.supports("deepseek-v4-flash", null, Modality.VIDEO));
        assertFalse(service.supports("deepseek-v3", null, Modality.VIDEO),
                "V3 must NOT inherit V4 capabilities — text-only base differs from V4 entirely");
        assertFalse(service.supports("deepseek-v3.2", null, Modality.VIDEO));
        assertFalse(service.supports("deepseek-r1", null, Modality.VIDEO));
    }

    @Test
    @DisplayName("Qwen3-VL (all sizes) and Qwen3.5-Omni support VIDEO")
    void qwen3Generation_supportsVideo() {
        assertTrue(service.supports("qwen3-vl-8b-instruct", null, Modality.VIDEO));
        assertTrue(service.supports("qwen3-vl-235b-a22b", null, Modality.VIDEO));
        assertTrue(service.supports("qwen3.5-omni", null, Modality.VIDEO));
        assertTrue(service.supports("qwen3.5-omni", null, Modality.AUDIO));
    }

    @Test
    @DisplayName("Moonshot Kimi K2.6 → VIDEO; K2.5 → image only")
    void kimiK26_supportsVideo() {
        assertTrue(service.supports("kimi-k2.6", null, Modality.VIDEO));
        assertFalse(service.supports("kimi-k2.5", null, Modality.VIDEO));
        assertTrue(service.supports("kimi-k2.5", null, Modality.VISION));
    }

    @Test
    @DisplayName("ByteDance Doubao Seed 2.0 supports VIDEO")
    void doubaoSeed2_supportsVideo() {
        assertTrue(service.supports("doubao-seed-2.0-pro", null, Modality.VIDEO));
        assertTrue(service.supports("doubao-seed-2.0", null, Modality.VIDEO));
    }

    @Test
    @DisplayName("Gemini 2.5 (pro/flash/flash-lite) is fully multimodal")
    void gemini25_fullyMultimodal() {
        assertTrue(service.supports("gemini-2.5-pro", null, Modality.VIDEO));
        assertTrue(service.supports("gemini-2.5-flash", null, Modality.VIDEO));
        assertTrue(service.supports("gemini-2.5-flash-lite", null, Modality.VIDEO));
        assertTrue(service.supports("gemini-2.5-flash", null, Modality.AUDIO));
    }

    @Test
    @DisplayName("Claude family: vision yes, native video no")
    void claude_visionOnly() {
        assertTrue(service.supports("claude-3.7-sonnet", null, Modality.VISION));
        assertTrue(service.supports("claude-opus-4-5", null, Modality.VISION));
        assertFalse(service.supports("claude-3.7-sonnet", null, Modality.VIDEO),
                "Claude does not natively ingest video frames");
    }

    @Test
    @DisplayName("Unknown model name: only TEXT, no vision/video/audio")
    void unknownModel_textOnly() {
        EnumSet<Modality> caps = service.resolve("totally-made-up-model-9000", null);
        assertEquals(EnumSet.of(Modality.TEXT), caps,
                "unknown model must default to text-only — failsafe for issue #44 silent skip");
    }

    @Test
    @DisplayName("Null/blank model name resolves cleanly to TEXT only")
    void nullModelName_safe() {
        assertEquals(EnumSet.of(Modality.TEXT), service.resolve(null, null));
        assertEquals(EnumSet.of(Modality.TEXT), service.resolve("", null));
        assertEquals(EnumSet.of(Modality.TEXT), service.resolve("   ", null));
    }

    @Test
    @DisplayName("Case-insensitive model name match")
    void caseInsensitiveMatch() {
        assertTrue(service.supports("GLM-4V-PLUS", null, Modality.VIDEO));
        assertTrue(service.supports("Gpt-4o", null, Modality.VISION),
                "case-insensitive match still resolves the entry; OpenAI grants vision (not video)");
    }

    @Test
    @DisplayName("Llama 4 Scout / Maverick support VIDEO; Llama 3 does not")
    void llama4_supportsVideo() {
        assertTrue(service.supports("llama-4-scout", null, Modality.VIDEO));
        assertTrue(service.supports("llama-4-maverick", null, Modality.VIDEO));
        assertFalse(service.supports("llama-3.3-70b", null, Modality.VIDEO));
    }

    @Test
    @DisplayName("Mistral / Pixtral / Grok / Hunyuan vision: image yes, video no")
    void imageOnlyVendors() {
        assertTrue(service.supports("pixtral-12b", null, Modality.VISION));
        assertFalse(service.supports("pixtral-12b", null, Modality.VIDEO));
        assertTrue(service.supports("mistral-small-4", null, Modality.VISION));
        assertFalse(service.supports("mistral-small-4", null, Modality.VIDEO));
        assertTrue(service.supports("grok-3", null, Modality.VISION));
        assertFalse(service.supports("grok-3", null, Modality.VIDEO),
                "Grok Imagine is video generation, not input — pin this to prevent confusion");
        assertTrue(service.supports("hunyuan-vision", null, Modality.VISION));
        assertTrue(service.supports("hunyuan-large-vision", null, Modality.VISION));
    }

    @Test
    @DisplayName("MiniMax-VL is vision-only (Hailuo / video-01 are generation, not input)")
    void minimaxVl_visionOnly() {
        assertTrue(service.supports("minimax-vl-01", null, Modality.VISION));
        assertFalse(service.supports("minimax-vl-01", null, Modality.VIDEO),
                "MiniMax video models generate video, they don't ingest it");
    }

    // ---------- DB modalities override (user opt-in) ----------

    @Test
    @DisplayName("DB modalities JSON overrides heuristics — user can grant video to image-only model")
    void dbOverride_grantsCapability() {
        // User declares glm-4v supports video (e.g. they tested a custom endpoint that does).
        // Override wins. TEXT always implicit.
        EnumSet<Modality> caps = service.resolve("glm-4v", "[\"vision\",\"video\"]");
        assertTrue(caps.contains(Modality.VIDEO),
                "DB override must take precedence — heuristic alone says no video");
    }

    @Test
    @DisplayName("DB modalities JSON overrides heuristics — user can revoke capability")
    void dbOverride_revokesCapability() {
        // User declares gpt-4o as vision-only (e.g. their proxy strips video).
        EnumSet<Modality> caps = service.resolve("gpt-4o", "[\"vision\"]");
        assertFalse(caps.contains(Modality.VIDEO),
                "Empty modalities array means user explicitly opted out of video for this model");
    }

    @Test
    @DisplayName("DB JSON case-insensitive on modality names")
    void dbOverride_caseInsensitive() {
        assertTrue(service.supports("anything", "[\"VIDEO\",\"Vision\"]", Modality.VIDEO));
        assertTrue(service.supports("anything", "[\"VIDEO\",\"Vision\"]", Modality.VISION));
    }

    @Test
    @DisplayName("Invalid JSON falls back to heuristics, does not throw")
    void dbOverride_invalidJson_fallsBack() {
        EnumSet<Modality> caps = service.resolve("glm-4v-plus", "this is not json");
        assertTrue(caps.contains(Modality.VIDEO),
                "When DB JSON is malformed, fall back to heuristics so service stays available");
    }

    @Test
    @DisplayName("Unknown modality string in JSON is logged and ignored, others still apply")
    void dbOverride_unknownModalityIgnored() {
        EnumSet<Modality> caps = service.resolve("anything", "[\"vision\",\"telepathy\"]");
        assertTrue(caps.contains(Modality.VISION));
        // unknown one silently skipped, no exception
    }

    @Test
    @DisplayName("TEXT is always implicit, even with empty DB declaration")
    void textAlwaysImplicit() {
        assertTrue(service.resolve("anything", "[]").contains(Modality.TEXT));
        assertTrue(service.resolve("anything", null).contains(Modality.TEXT));
        assertTrue(service.resolve(null, null).contains(Modality.TEXT));
    }
}
