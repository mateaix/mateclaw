package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import vip.mate.llm.model.ModelFamily;

import java.util.Map;

/**
 * Resolves the OpenAI-style {@code reasoning_effort} request parameter for a
 * model, given its name, the provider's generate kwargs and its {@link ModelFamily}.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>Families that do not accept {@code reasoning_effort} always resolve to
 *       {@code null}; an explicit kwargs override on such a model is dropped with
 *       a warning, because sending the field would 400 on DeepSeek / Kimi-style
 *       providers.</li>
 *   <li>For accepting families, an explicit {@code reasoningEffort} in the
 *       provider's generate kwargs wins.</li>
 *   <li>Otherwise a thinking-capable family gets a default of {@code "medium"}.</li>
 * </ul>
 *
 * <p>Pure function with no Spring dependencies, so it is shared by the
 * OpenAI-compatible chat model builder and the agent graph builder (which passes
 * the resolved value to its reasoning nodes).
 */
@Slf4j
public final class ReasoningEffortResolver {

    private ReasoningEffortResolver() {}

    /**
     * Resolve the effective {@code reasoning_effort} value, or {@code null} when
     * the model must not carry one.
     */
    public static String resolveReasoningEffort(String modelName, Map<String, Object> kwargs, ModelFamily family) {
        // Only families that actually accept reasoning_effort may receive it.
        // Otherwise a provider-level `reasoningEffort` override would leak to
        // deepseek-chat / kimi-k2 / deepseek-reasoner etc., triggering a
        // "reasoning_content missing" 400.
        if (!family.supportsReasoningEffort()) {
            Object overridden = ProviderGenerateKwargs.findOptionValue(kwargs, "reasoningEffort");
            if (overridden != null) {
                log.warn("Dropping reasoningEffort='{}' from generateKwargs — model '{}' (family={}) "
                                + "does not accept reasoning_effort. For DeepSeek thinking use "
                                + "extra_body.thinking; for Kimi thinking the model activates it natively.",
                        overridden, modelName, family);
            }
            return null;
        }
        // An explicit generateKwargs override always wins (within accepting families).
        Object value = ProviderGenerateKwargs.findOptionValue(kwargs, "reasoningEffort");
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        // Only thinking-capable families get a default reasoning effort.
        if (family.isThinking()) {
            return "medium";
        }
        return null;
    }
}
