package vip.mate.kbopen.auth;

import java.util.Set;

/**
 * Authentication context injected by {@code KbOpenApiAuthFilter} into each
 * request's attributes. Carries the resolved API key identity (keyId,
 * workspace, bound KBs, scopes) so that Controller-layer authorization
 * (via {@code @RequireKbScope}) can check access without re-querying the DB.
 *
 * <p>This is intentionally <strong>not</strong> a Spring Security
 * {@code Authentication} — KB API Keys do not represent a user identity and
 * must not inherit user-level permissions. The filter writes to a request
 * attribute instead of the {@code SecurityContextHolder}.
 */
public record KbApiKeyContext(
        Long keyId,
        Long workspaceId,
        Set<Long> kbIds,
        Set<String> scopes,
        int rateLimitPerMin
) {

    /** Request attribute key under which the context is stored. */
    public static final String ATTR = "kbOpenApiKeyContext";

    /** Check whether the key grants the given scope (or the wildcard). */
    public boolean hasScope(String scope) {
        return scopes.contains("kb:*") || scopes.contains(scope);
    }

    /**
     * Check whether the key is authorized to access the given KB. An empty
     * {@code kbIds} set means zero access (R3).
     */
    public boolean canAccessKb(Long kbId) {
        return kbId != null && kbIds.contains(kbId);
    }
}
