package vip.mate.llm.model;

/**
 * Credential-free projection of a configured provider: just enough to render a
 * picker and store the chosen id.
 * <p>
 * The full {@link ProviderInfoDTO} carries connection settings (base URL, the
 * masked key, request kwargs, liveness diagnostics) and is therefore only
 * served to global admins. Binding an agent to a preferred provider is a
 * workspace-member action, so the member has to be able to read the list of
 * choices — this DTO is what that read returns.
 *
 * @param id   provider id, the value persisted on the agent binding
 * @param name display name shown in the picker
 */
public record ProviderOptionDTO(String id, String name) {
}
