package vip.mate.tool.browser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolGuardAuditLogEntity;
import vip.mate.tool.guard.repository.ToolGuardAuditLogMapper;

import java.net.URI;
import java.util.List;

/**
 * Privacy guard for browser sessions attached to a user's own logged-in browser.
 *
 * <p>When the browser tool connects to a Chrome the user is running themselves
 * (via the DevTools Protocol, process not spawned by us), that browser may have
 * banking, webmail or internal-admin tabs open with live sessions. Reading such
 * a page in full — screenshot, arbitrary JS eval, or a text/accessibility dump —
 * would funnel private content into the model and possibly into persisted
 * transcripts. This guard classifies the current page and refuses those
 * content-reading actions on pages that look sensitive, while leaving plain
 * navigation untouched. It never applies to headless or self-spawned browsers.
 *
 * <p>Classification precedence: configured trusted hosts (always safe) →
 * configured sensitive hosts → a built-in keyword heuristic over host + path.
 * Every block is overridable by adding the host to
 * {@code mateclaw.browser.privacy.trusted-hosts}.
 */
@Slf4j
@Component
public class BrowserPrivacyGuard {

    /** Keyword signals that a page handles money, identity, or privileged access. */
    private static final List<String> SENSITIVE_SIGNALS = List.of(
            "bank", "banking", "pay", "payment", "wallet", "checkout", "billing", "invoice",
            "mail", "webmail", "signin", "login", "logon", "account", "admin", "console",
            "secure", "oauth", "authorize", "password", "passport", "identity");

    private final BrowserProperties properties;
    private final ToolGuardAuditLogMapper auditMapper;

    public BrowserPrivacyGuard(BrowserProperties properties, ToolGuardAuditLogMapper auditMapper) {
        this.properties = properties;
        this.auditMapper = auditMapper;
    }

    /**
     * Decide whether a content-reading action must be refused. Returns a
     * human-readable block reason, or {@code null} when the action may proceed.
     *
     * @param userManagedBrowser true only when attached to a Chrome the user runs themselves
     * @param url                the current page URL
     * @param action             the action being attempted (screenshot / eval / snapshot)
     */
    public String blockReason(boolean userManagedBrowser, String url, String action) {
        if (!properties.getPrivacy().isEnabled() || !userManagedBrowser) {
            return null;
        }
        if (!isSensitive(url)) {
            return null;
        }
        return "Refusing action=" + action + " on what looks like a sensitive page (" + safeHost(url)
                + ") inside your own logged-in browser, to avoid exposing private content to the"
                + " model. Plain navigation is still allowed. If this page is safe to read, add its"
                + " host to mateclaw.browser.privacy.trusted-hosts.";
    }

    /** True when the URL matches a configured/heuristic sensitive signal and is not trusted. */
    public boolean isSensitive(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String host;
        String path;
        try {
            URI uri = URI.create(url);
            host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host.isEmpty()) {
            return false;
        }
        if (hostMatches(host, properties.getPrivacy().getTrustedHosts())) {
            return false;
        }
        if (hostMatches(host, properties.getPrivacy().getSensitiveHosts())) {
            return true;
        }
        String hostAndPath = host + path;
        for (String sig : SENSITIVE_SIGNALS) {
            if (hostAndPath.contains(sig)) {
                return true;
            }
        }
        return false;
    }

    /** Record a blocked read into the shared tool-guard audit log so it shows up in the audit panel. */
    public void audit(String conversationId, String action, String url, String reason) {
        try {
            ToolGuardAuditLogEntity entity = new ToolGuardAuditLogEntity();
            entity.setConversationId(conversationId);
            entity.setToolName("browser_use");
            entity.setDecision(GuardDecision.BLOCK.name());
            entity.setMaxSeverity(GuardSeverity.HIGH.name());
            entity.setToolParamsJson("{\"action\":\"" + action + "\",\"host\":\"" + safeHost(url) + "\"}");
            entity.setFindingsJson("[{\"type\":\"sensitive-page\",\"reason\":\""
                    + reason.replace("\"", "'") + "\"}]");
            auditMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[BrowserPrivacyGuard] Failed to record audit entry: {}", e.getMessage());
        }
    }

    private static boolean hostMatches(String host, List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        for (String p : patterns) {
            if (p == null || p.isBlank()) {
                continue;
            }
            String pat = p.trim().toLowerCase();
            if (host.equals(pat) || host.endsWith("." + pat)) {
                return true;
            }
        }
        return false;
    }

    private static String safeHost(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "unknown" : h;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
