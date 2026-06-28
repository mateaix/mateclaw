package vip.mate.kbopen.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Intercepts methods annotated with {@link RequireKbScope} and enforces:
 * <ol>
 *   <li>Scope check — the request's {@link KbApiKeyContext} must grant the
 *       required scope (or {@code kb:*}).</li>
 *   <li>KB ownership — the {@code kbId} path variable must be in the
 *       context's bound KB set (R3: empty set = zero access).</li>
 * </ol>
 *
 * <p>Modeled after {@code WorkspaceAccessInterceptor}. Both checks return 403
 * on failure; the request is rejected before the Controller method runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbScopeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireKbScope annotation = handlerMethod.getMethodAnnotation(RequireKbScope.class);
        if (annotation == null) {
            return true;
        }

        KbApiKeyContext context = (KbApiKeyContext) request.getAttribute(KbApiKeyContext.ATTR);
        if (context == null) {
            // Filter should have already rejected this, but fail-closed just in case.
            sendForbidden(response, "Authentication required");
            return false;
        }

        // Layer 2: scope check
        String requiredScope = annotation.value();
        if (!context.hasScope(requiredScope)) {
            log.warn("[KbOpenApi] Scope denied: keyId={} required={} has={}",
                    context.keyId(), requiredScope, context.scopes());
            sendForbidden(response, "Insufficient scope: requires " + requiredScope);
            return false;
        }

        // Layer 3: KB ownership — extract kbId from path variable
        Long kbId = extractKbId(request);
        if (kbId == null) {
            // No kbId in path — let the controller handle it (e.g. taxonomy may not need one).
            return true;
        }
        if (!context.canAccessKb(kbId)) {
            log.warn("[KbOpenApi] KB access denied: keyId={} kbId={} boundKbs={}",
                    context.keyId(), kbId, context.kbIds());
            sendForbidden(response, "Knowledge base not bound to this API key");
            return false;
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private Long extractKbId(HttpServletRequest request) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map)) {
            return null;
        }
        Object rawKbId = ((Map<String, String>) attr).get("kbId");
        if (rawKbId == null) {
            return null;
        }
        try {
            return Long.parseLong(rawKbId.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sendForbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"msg\":\"" + message + "\",\"data\":null}");
    }
}
