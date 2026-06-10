package vip.mate.llm.anthropic.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RFC-062: produces the HTTP header set Anthropic expects on OAuth-authenticated
 * Messages-API requests.
 *
 * <p>OAuth requests get extra beta headers + a User-Agent that masquerades as
 * Claude Code. Without these, Anthropic's infrastructure intermittently 500s.
 *
 * <h2>Header reference</h2>
 *
 * <table>
 *   <caption>Header set sent on OAuth requests</caption>
 *   <tr><th>Header</th><th>Value</th><th>Why</th></tr>
 *   <tr><td>{@code Authorization}</td><td>{@code Bearer <accessToken>}</td><td>OAuth path uses Bearer; non-OAuth uses {@code x-api-key}</td></tr>
 *   <tr><td>{@code User-Agent}</td><td>{@code claude-cli/<version>}</td><td>bare form — suffix triggers anti-abuse fingerprint, see {@link #userAgent()}</td></tr>
 *   <tr><td>{@code x-app}</td><td>{@code cli}</td><td>Claude Code identity flag</td></tr>
 *   <tr><td>{@code anthropic-beta}</td><td>(comma-joined list — see {@link #allBetas()})</td><td>OAuth-only + common feature betas</td></tr>
 * </table>
 */
@Component
@RequiredArgsConstructor
public class ClaudeCodeApiHeaders {

    /** Beta headers required for any OAuth request. Anthropic's infra
     *  intermittently 500s OAuth traffic without them. */
    static final List<String> OAUTH_ONLY_BETAS = List.of(
            "claude-code-20250219",
            "oauth-2025-04-20"
    );

    /** Common beta headers for enhanced features. GA on Claude 4.6+ but kept
     *  for &lt;= 4.5 compat — the headers are accepted as no-ops on newer
     *  models so it's safe to send always. */
    static final List<String> COMMON_BETAS = List.of(
            "interleaved-thinking-2025-05-14",
            "fine-grained-tool-streaming-2025-05-14"
    );

    private final ClaudeCodeVersionDetector versionDetector;

    /**
     * Comma-joined beta header list to send in {@code anthropic-beta}.
     * <p>Order is common betas first, OAuth-only betas appended.
     */
    public String allBetas() {
        return String.join(",",
                concat(COMMON_BETAS, OAUTH_ONLY_BETAS));
    }

    /**
     * User-Agent string Anthropic OAuth infrastructure expects.
     * Format: {@code claude-cli/<version>} — bare, no suffix.
     *
     * <p><b>History note:</b> we previously appended {@code (external, cli)}
     * to the User-Agent. That turned out to be wrong: Anthropic's anti-abuse
     * gate uses the suffix to fingerprint third-party clients and rate-limits
     * them harder. Real Claude Code (Electron + Node + official Anthropic JS
     * SDK) emits the bare {@code claude-cli/<v>} form. Verified by reproducing
     * 429 with the suffix and {@code anthropic-ratelimit-*} headers absent —
     * the diagnostic signature of the anti-abuse path.
     */
    public String userAgent() {
        return "claude-cli/" + versionDetector.get();
    }

    /** {@code x-app} header value. Constant. */
    public String xApp() {
        return "cli";
    }

    /** {@code Authorization} header value for the given access token. */
    public String bearerAuth(String accessToken) {
        return "Bearer " + accessToken;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        java.util.ArrayList<T> out = new java.util.ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }
}
