package vip.mate.acp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.io.File;
import java.util.Locale;

/**
 * Shared runtime helpers for ACP code paths.
 *
 * <p>Two responsibilities, both motivated by upstream ACP servers
 * (e.g. {@code @zed-industries/claude-agent-acp}) being strict about
 * inputs and noisy in failure modes:
 *
 * <ul>
 *   <li>{@link #resolveCwd} — pick a non-blank cwd for {@code session/new}.
 *       Zed's ACP Zod schema marks {@code cwd} as a required string and
 *       returns {@code -32602 Invalid params} when it's missing. We
 *       prefer the endpoint's bound workspace {@code base_path} (per-
 *       workspace context) and fall back to the JVM working directory
 *       only as a last resort. Never returns null/blank.</li>
 *
 *   <li>{@link #translateAuthError} — turn upstream JSON-RPC noise like
 *       {@code "API Error: 403 {...forbidden...}"} into an actionable
 *       hint that names the env var the user actually has to set in
 *       Settings ▸ ACP Endpoints (e.g. {@code ANTHROPIC_API_KEY} for
 *       claude-code, {@code OPENAI_API_KEY} for codex). Returns
 *       {@code null} when the error doesn't smell like an auth failure.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcpRuntimeSupport {

    private final WorkspaceService workspaceService;

    /**
     * Resolution order (first non-blank wins):
     * <ol>
     *   <li>Caller-provided hint (skill manifest's {@code acp.cwd},
     *       wrapper tool {@code cwd} arg, or explicit override).</li>
     *   <li>Workspace {@code base_path} when the endpoint is bound to a
     *       workspace and the workspace declares one.</li>
     *   <li>{@code System.getProperty("user.dir")} — the JVM working
     *       directory at server launch. Reasonable for a single-user
     *       desktop install, but exposes the server's launch dir to the
     *       upstream agent, which is why it's last.</li>
     * </ol>
     */
    public String resolveCwd(AcpEndpointEntity endpoint, String callerHint) {
        if (callerHint != null && !callerHint.isBlank()) {
            return callerHint;
        }
        if (endpoint != null && endpoint.getWorkspaceId() != null) {
            try {
                WorkspaceEntity ws = workspaceService.getById(endpoint.getWorkspaceId());
                if (ws != null && ws.getBasePath() != null && !ws.getBasePath().isBlank()) {
                    File f = new File(ws.getBasePath());
                    if (f.isDirectory()) return f.getAbsolutePath();
                }
            } catch (Exception e) {
                log.debug("Workspace lookup failed for ACP cwd default (id={}): {}",
                        endpoint.getWorkspaceId(), e.getMessage());
            }
        }
        return System.getProperty("user.dir", ".");
    }

    /**
     * Detect upstream auth errors and emit an actionable hint string.
     * Returns null when the message doesn't match — caller should keep
     * the original error as-is.
     *
     * <p>Heuristic: looks for HTTP-like 401/403 markers OR the words
     * {@code forbidden / unauthorized / not allowed / api key / token}
     * in the original message (case-insensitive). The patterns are loose
     * on purpose — different ACP CLIs phrase auth errors differently
     * and the cost of a false positive (a slightly more verbose error
     * banner) is much smaller than a false negative (user staring at a
     * raw JSON-RPC blob).
     *
     * <p>Special case: a claude-code endpoint returning {@code 403
     * "Request not allowed"} is almost always the keychain-hijack
     * scenario rather than a wrong API key. The third-party
     * {@code @zed-industries/claude-agent-acp} package wraps
     * {@code @anthropic-ai/claude-agent-sdk}, whose auth dispatcher
     * checks the macOS keychain ({@code Claude Code-credentials}) /
     * {@code ~/.claude/credentials.json} BEFORE the
     * {@code ANTHROPIC_API_KEY} env var. So a host that's done
     * {@code claude login} silently shadows whatever API key the user
     * configured in the endpoint env, and Anthropic's API rejects the
     * subscription OAuth token (first-party-only) with the very
     * specific {@code "Request not allowed"} error string. We detect
     * that exact combination and surface the keychain-clearing remedy
     * instead of the generic "set ANTHROPIC_API_KEY" hint, which
     * doesn't apply here.
     */
    public String translateAuthError(AcpEndpointEntity endpoint, String originalMessage) {
        if (originalMessage == null) return null;
        String lower = originalMessage.toLowerCase(Locale.ROOT);
        boolean looksLikeAuth =
                lower.contains("403")
                || lower.contains("401")
                || lower.contains("forbidden")
                || lower.contains("unauthorized")
                || lower.contains("not allowed")
                || lower.contains("invalid api key")
                || lower.contains("invalid token")
                || lower.contains("authenticate");
        if (!looksLikeAuth) return null;

        String name = endpoint != null && endpoint.getName() != null ? endpoint.getName() : "(unknown)";
        String slug = lower(name);
        String command = endpoint != null ? lower(endpoint.getCommand()) : "";

        // Keychain-hijack detection — must come before the generic env-
        // missing branch because both would superficially match.
        boolean keychainHijack = lower.contains("request not allowed")
                && (slug.contains("claude") || command.contains("claude-agent-acp"));
        if (keychainHijack) {
            StringBuilder sb = new StringBuilder();
            sb.append("ACP endpoint '").append(name).append("' upstream auth failed with ");
            sb.append("'Request not allowed' — almost always means the host CLI's OAuth ");
            sb.append("credentials are hijacking the SDK auth path. ");
            sb.append("The Claude Agent SDK reads ~/.claude/ / macOS keychain BEFORE the ");
            sb.append("ANTHROPIC_API_KEY env var, so the API key you configured here is ");
            sb.append("never sent — Anthropic rejects the subscription OAuth token because ");
            sb.append("third-party processes aren't allowed to use it. ");
            sb.append("To fix: ");
            sb.append("(macOS) run `claude logout`, or `security delete-generic-password ");
            sb.append("-s \"Claude Code-credentials\"`; ");
            sb.append("(Linux / Windows) delete ~/.claude/credentials.json. ");
            sb.append("Then click Test connection again. Original: ").append(originalMessage);
            return sb.toString();
        }

        String envVar = expectedAuthEnvVar(endpoint);
        StringBuilder sb = new StringBuilder();
        sb.append("ACP endpoint '").append(name).append("' upstream auth failed. ");
        sb.append("Most likely the endpoint env has no API key. ");
        sb.append("Edit Settings ▸ ACP Endpoints → ").append(name).append(" → env, ");
        if (envVar != null) {
            sb.append("add `{\"").append(envVar).append("\":\"...\"}`");
        } else {
            sb.append("add the appropriate API key for this CLI");
        }
        sb.append(". Note: claude-code / codex / qwen-code refuse OAuth tokens from their host CLIs, ");
        sb.append("so a real API key is required. Original: ").append(originalMessage);
        return sb.toString();
    }

    /**
     * Best-effort guess of the API key env var the upstream CLI expects.
     * Returns null when we don't recognise the endpoint — caller emits a
     * generic "appropriate API key" hint instead.
     */
    public String expectedAuthEnvVar(AcpEndpointEntity endpoint) {
        if (endpoint == null) return null;
        String name = lower(endpoint.getName());
        String command = lower(endpoint.getCommand());
        // Match by name first (slug is the stable identifier); fall back
        // to command keywords for user-defined rows.
        if (name.contains("claude") || command.contains("claude-agent-acp") || command.contains("anthropic")) {
            return "ANTHROPIC_API_KEY";
        }
        if (name.contains("codex") || command.contains("codex") || command.contains("openai")) {
            return "OPENAI_API_KEY";
        }
        if (name.contains("qwen") || command.contains("qwen") || command.contains("dashscope")) {
            return "DASHSCOPE_API_KEY";
        }
        if (name.contains("gemini") || command.contains("gemini") || command.contains("google-genai")) {
            return "GOOGLE_API_KEY";
        }
        // opencode multi-model — it reads OPENAI_API_KEY / ANTHROPIC_API_KEY /
        // GOOGLE_API_KEY etc. depending on the configured provider. Surface
        // the most common one; the user can set any of them in env_json.
        if (name.contains("opencode") || command.contains("opencode")) {
            return "OPENAI_API_KEY";
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
