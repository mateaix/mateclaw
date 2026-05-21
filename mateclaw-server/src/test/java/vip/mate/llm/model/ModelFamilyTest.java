package vip.mate.llm.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pinpoint regression tests for {@link ModelFamily#detect(String)}.
 *
 * <p>Each new model family added here should pin its detect rule so accidental
 * code-style cleanups (e.g. reordering branches in {@code detect()}) can't
 * silently route a thinking model to {@link ModelFamily#STANDARD} and break
 * reasoning_effort propagation.
 */
class ModelFamilyTest {

    @Test
    @DisplayName("DeepSeek V4 (flash + pro) → DEEPSEEK_V4_REASONING with reasoning_effort enabled")
    void deepSeekV4_reasoning() {
        // Critical assertion: V4 differs from v3.2 deepseek-reasoner — V4 ACCEPTS
        // the reasoning_effort field, while v3.2 doesn't (DeepSeek API rejects it).
        // Routing V4 to DEEPSEEK_REASONER would suppress the field and forfeit
        // openclaw's documented thinking control.
        assertEquals(ModelFamily.DEEPSEEK_V4_REASONING, ModelFamily.detect("deepseek-v4-flash"));
        assertEquals(ModelFamily.DEEPSEEK_V4_REASONING, ModelFamily.detect("deepseek-v4-pro"));
        assertTrue(ModelFamily.DEEPSEEK_V4_REASONING.supportsReasoningEffort(),
                "V4 must accept reasoning_effort (key differentiator from v3.2 reasoner)");
        assertTrue(ModelFamily.DEEPSEEK_V4_REASONING.isThinking(),
                "V4 is a thinking family — DeepSeekV4ThinkingDecorator gates on this");
        assertFalse(ModelFamily.DEEPSEEK_V4_REASONING.fixedTemperatureOne(),
                "V4 allows configurable temperature (unlike v3.2 reasoner)");
    }

    @Test
    @DisplayName("Legacy deepseek-reasoner stays in DEEPSEEK_REASONER family (does not catch V4 rule)")
    void deepSeekReasoner_unchanged() {
        // Defensive: if the V4 detect rule were too broad (e.g. startsWith "deepseek-")
        // it would catch deepseek-reasoner too and break that model's working config.
        assertEquals(ModelFamily.DEEPSEEK_REASONER, ModelFamily.detect("deepseek-reasoner"));
        assertFalse(ModelFamily.DEEPSEEK_REASONER.supportsReasoningEffort(),
                "v3.2 reasoner must NOT advertise reasoning_effort support");
    }

    @Test
    @DisplayName("deepseek-chat stays STANDARD")
    void deepSeekChat_standard() {
        // Smoke check: non-reasoning DeepSeek model unaffected.
        assertEquals(ModelFamily.STANDARD, ModelFamily.detect("deepseek-chat"));
    }

    @Test
    @DisplayName("Case + whitespace tolerance — uppercased / padded model name routes the same")
    void detect_caseInsensitive() {
        assertEquals(ModelFamily.DEEPSEEK_V4_REASONING, ModelFamily.detect("DeepSeek-V4-Flash"));
        assertEquals(ModelFamily.DEEPSEEK_V4_REASONING, ModelFamily.detect("  deepseek-v4-pro  "));
    }

    @Test
    @DisplayName("Null / blank model name → STANDARD (no NPE)")
    void detect_nullSafe() {
        assertEquals(ModelFamily.STANDARD, ModelFamily.detect(null));
        assertEquals(ModelFamily.STANDARD, ModelFamily.detect(""));
        assertEquals(ModelFamily.STANDARD, ModelFamily.detect("   "));
    }

    @Test
    @DisplayName("Xiaomi MiMo models → MIMO_THINKING (reasoning_content relay required)")
    void mimo_thinking() {
        assertEquals(ModelFamily.MIMO_THINKING, ModelFamily.detect("mimo-v2-flash"));
        assertEquals(ModelFamily.MIMO_THINKING, ModelFamily.detect("MiMo-VL-7B-RL"));
        assertTrue(ModelFamily.MIMO_THINKING.isThinking(),
                "Mimo must be flagged as thinking so reasoning_content is patched");
        assertFalse(ModelFamily.MIMO_THINKING.supportsReasoningEffort(),
                "Mimo does not accept the reasoning_effort parameter");
    }
}
