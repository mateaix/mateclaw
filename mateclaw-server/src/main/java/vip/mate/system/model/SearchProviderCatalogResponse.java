package vip.mate.system.model;

import java.util.List;

/**
 * Response payload for {@code GET /api/v1/settings/search-providers}.
 *
 * @param providers     all registered providers (builtin + plugin), sorted by autoDetectOrder
 * @param resolvedId    the id of the provider that would actually be used right now; {@code null} if none available
 * @param resolvedSource why it was picked: "configured" / "auto-detect" / "keyless-fallback"; {@code null} when resolvedId is null
 */
public record SearchProviderCatalogResponse(
        List<SearchProviderCatalogEntry> providers,
        String resolvedId,
        String resolvedSource
) {
}
