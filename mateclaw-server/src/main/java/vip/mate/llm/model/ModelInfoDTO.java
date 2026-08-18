package vip.mate.llm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfoDTO {
    private String id;
    private String name;

    /**
     * Discovery probe result. true = passed runtime-protocol ping test,
     * false = ping failed (listed by provider but unusable at runtime,
     * e.g. DashScope compatible-mode may list models the native SDK rejects).
     * null = not probed (probe disabled or still pending).
     */
    private Boolean probeOk;

    /** Reason text when probeOk=false (short, suitable for UI badge tooltip) */
    private String probeError;

    /**
     * RFC-049 PR-1-UI (narrow): whether this model's {@code ModelFamily} accepts the
     * OpenAI {@code reasoning_effort} parameter specifically. True <em>only</em> for
     * the OpenAI reasoning family (gpt-5, o1, o3, o4 variants). Retained for callers
     * that need to know parameter-level compatibility; the UI "deep thinking" toggle
     * should use {@link #supportsThinking} instead.
     */
    private boolean supportsReasoningEffort;

    /**
     * RFC-049 PR-1-UI (broad): whether this model supports <em>any</em> form of
     * deep thinking — either OpenAI-style via {@code reasoning_effort}, provider-
     * native (Kimi K2.x, DeepSeek-Reasoner, qwen-thinking), or Anthropic extended
     * thinking (Claude family). This is what the UI "deep thinking" toggle reads.
     *
     * <p>Derived from the model name so every construction site stays consistent.
     */
    private boolean supportsThinking;

    /**
     * Explicit per-model input window from {@code mate_model_config}; null when
     * the operator has not set one (stored as 0). This is what the management
     * UI's input binds to — an empty field means "let the server decide".
     */
    private Integer maxInputTokens;

    /**
     * The window context budgeting would use if a turn ran right now, computed
     * without any probe traffic: explicit config, else the built-in window
     * table, else the global default. Display only.
     */
    private Integer effectiveMaxInputTokens;

    /**
     * Where {@link #effectiveMaxInputTokens} came from — {@code configured},
     * {@code catalog} or {@code default} — so the UI can say why a number is
     * what it is instead of presenting a guess as configuration.
     */
    private String maxInputTokensSource;

    public ModelInfoDTO(String id, String name) {
        this.id = id;
        this.name = name;
        this.supportsReasoningEffort = ModelFamily.detect(id).supportsReasoningEffort();
        this.supportsThinking = computeSupportsThinking(id);
    }

    /**
     * Thinking capability at the product level (what the UI toggle should reflect):
     * any model family that has a thinking mode, regardless of how it is triggered
     * (parameter vs. model-native vs. Anthropic extended thinking).
     */
    private static boolean computeSupportsThinking(String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        ModelFamily family = ModelFamily.detect(modelName);
        if (family.isThinking()) return true;  // OPENAI_REASONING / KIMI_THINKING / DEEPSEEK_REASONER / GENERIC_THINKING
        // Anthropic Claude supports extended thinking via AnthropicChatOptions.thinking;
        // ModelFamily doesn't model non-OpenAI-compatible providers so match by name.
        return modelName.toLowerCase().contains("claude");
    }
}
