package vip.mate.system.model;

/**
 * One row in the search-provider catalog exposed to the settings UI (issue #477).
 *
 * @param id                 provider id (matches {@code SearchProvider.id()})
 * @param label              display label
 * @param builtin            {@code true} for the four shipped providers, {@code false} for plugin-registered ones
 * @param requiresCredential whether the provider needs an API key/credential
 * @param available          whether it's currently usable under the active config
 * @param pluginName         owning plugin's manifest name for plugin-registered providers;
 *                           {@code null} for builtin providers, and also possibly {@code null}
 *                           for a plugin provider if the owning plugin was unregistered
 *                           concurrently with this lookup
 */
public record SearchProviderCatalogEntry(
        String id,
        String label,
        boolean builtin,
        boolean requiresCredential,
        boolean available,
        String pluginName
) {
}
