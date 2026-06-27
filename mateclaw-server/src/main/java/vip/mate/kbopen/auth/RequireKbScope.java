package vip.mate.kbopen.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the minimum scope required by a KB Open API endpoint.
 *
 * <p>Processed by {@code KbScopeInterceptor} which checks, in order:
 * <ol>
 *   <li>A {@link KbApiKeyContext} exists on the request (filter ran).</li>
 *   <li>The context's scopes include the annotation value or {@code kb:*}.</li>
 *   <li>The path's {@code kbId} variable is in the context's bound KB set.</li>
 * </ol>
 *
 * <p>This mirrors the existing {@code @RequireWorkspaceRole} +
 * {@code WorkspaceAccessInterceptor} pattern, centralizing authorization so
 * it is not hand-written per endpoint (A1 — avoids repeating the #438/#439
 * Wiki IDOR pattern on the outward-facing API).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireKbScope {

    /** Required scope, e.g. {@code "kb:search"}, {@code "kb:read"}. */
    String value();
}
