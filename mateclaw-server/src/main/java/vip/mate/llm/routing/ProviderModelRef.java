package vip.mate.llm.routing;

/**
 * One entry in an agent's preferred-model chain: a provider plus an optional
 * specific chat model.
 *
 * <p>{@code modelId == null} means "use the provider's default chat model" —
 * backward compatible with provider-only preferences. The same
 * {@code providerId} may appear in multiple entries, each pinning a different
 * model, so an agent can express a chain like {@code A/modelX → A/modelY →
 * B/modelZ}.
 *
 * @param providerId provider id (matches {@code mate_model_provider.provider_id})
 * @param modelId    pinned model id (matches {@code mate_model_config.id}), or
 *                   {@code null} for the provider's default chat model
 */
public record ProviderModelRef(String providerId, Long modelId) {
}
