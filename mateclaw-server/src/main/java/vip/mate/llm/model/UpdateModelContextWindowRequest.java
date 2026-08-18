package vip.mate.llm.model;

import lombok.Data;

/**
 * Body of {@code PUT /api/v1/models/{providerId}/models/context-window}.
 */
@Data
public class UpdateModelContextWindowRequest {

    /** Model identifier within the provider, i.e. {@code mate_model_config.model_name}. */
    private String modelId;

    /**
     * Input window in tokens. Null or non-positive clears the override and
     * hands budgeting back to the built-in window table / global default.
     */
    private Integer maxInputTokens;
}
