package vip.mate.llm.service;

import vip.mate.exception.MateClawException;
import vip.mate.llm.chatmodel.ProviderGenerateKwargs;
import vip.mate.llm.model.ModelConfigEntity;
import java.util.Map;

/** Write-time checks for parameters the runtime actually consumes. Unknown vendor extensions remain opaque. */
final class ModelParameterValidator {
    private static final int MIN_INPUT_WINDOW = 1024;
    private static final int MAX_INPUT_WINDOW = 20_000_000;
    private ModelParameterValidator() {}

    static void validateModel(ModelConfigEntity model) {
        sampling(model.getTemperature(), model.getTopP());
        if (model.getMaxTokens() != null) outputTokens(model.getMaxTokens());
        if (model.getMaxInputTokens() != null) model.setMaxInputTokens(normalizeInputWindow(model.getMaxInputTokens()));
        // Non-positive requestTimeoutSeconds intentionally inherits the global timeout.
    }

    static int normalizeInputWindow(Integer value) {
        if (value == null || value <= 0) return 0;
        if (value < MIN_INPUT_WINDOW || value > MAX_INPUT_WINDOW)
            throw invalid("context_window_out_of_range", "上下文窗口需要在 1024 ~ 20000000 tokens 之间");
        return value;
    }

    static void validateGenerateKwargs(Map<String, Object> kwargs) {
        if (kwargs == null) return;
        for (String wrapper : new String[]{"chatOptions", "chat_options"}) {
            Object value = kwargs.get(wrapper);
            if (value != null && !(value instanceof Map<?, ?>))
                throw invalid("generate_kwargs_invalid", "chatOptions 和 chat_options 必须是 JSON 对象");
        }
        // Use the same precedence, aliases and nested lookup as the builders.
        sampling(ProviderGenerateKwargs.findOptionValue(kwargs,"temperature"),
                ProviderGenerateKwargs.findOptionValue(kwargs,"topP"));
        Object max = ProviderGenerateKwargs.findOptionValue(kwargs,"maxTokens");
        Object completion = ProviderGenerateKwargs.findOptionValue(kwargs,"maxCompletionTokens");
        if (max != null) outputTokens(max);
        if (completion != null) outputTokens(completion);
    }

    private static void sampling(Object temperature, Object topP) {
        // Vendors differ on the upper temperature limit; do not invent a global cap.
        if (temperature != null && floating(temperature,"temperature_invalid") < 0)
            throw invalid("temperature_invalid", "温度必须是有限的非负数");
        if (topP != null) {
            double value = floating(topP,"top_p_invalid");
            if (value < 0 || value > 1) throw invalid("top_p_invalid", "top-p 必须是 0 到 1 之间的有限数值");
        }
    }

    private static double floating(Object value, String key) {
        try {
            double number = value instanceof Number n ? n.doubleValue()
                    : value instanceof String text ? Double.parseDouble(text.trim()) : Double.NaN;
            if (Double.isFinite(number)) return number;
        } catch (NumberFormatException ignored) { }
        throw invalid(key, key.equals("temperature_invalid") ? "温度必须是有限的非负数" : "top-p 必须是 0 到 1 之间的有限数值");
    }

    private static void outputTokens(Object value) {
        try {
            // Match the existing Integer.parseInt support for numeric strings.
            int number;
            if (value instanceof String text) number = Integer.parseInt(text.trim());
            else if (value instanceof Number numeric) number = new java.math.BigDecimal(numeric.toString()).intValueExact();
            else throw new NumberFormatException();
            if (number >= 1) return;
        } catch (NumberFormatException | ArithmeticException ignored) { }
        throw invalid("max_tokens_invalid", "输出 token 上限必须是 1 到 2147483647 之间的整数");
    }

    private static MateClawException invalid(String key, String fallback) {
        return new MateClawException("err.llm." + key, 400, fallback);
    }
}
