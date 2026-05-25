package vip.mate.llm.anthropic.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;

import java.util.Optional;

/**
 * RFC-062: top-level orchestrator for Claude Code OAuth.
 *
 * <p>Combines {@link ClaudeCodeCredentialsReader},
 * {@link ClaudeCodeTokenRefresher}, and {@link ClaudeCodeCredentialsWriter}
 * to expose a single {@link #getValidToken()} entry point that
 * {@code ClaudeCodeChatModelBuilder} (PR-2) will call on every request.
 *
 * <h2>Behavior</h2>
 * <ol>
 *   <li>Read whichever local source exists (Keychain on macOS, JSON file
 *       elsewhere).</li>
 *   <li>If the access token is still valid (with a 1-minute safety buffer),
 *       return it directly — no network call.</li>
 *   <li>Otherwise refresh via Anthropic's token endpoints and persist the
 *       fresh credential back to the same source.</li>
 *   <li>If no refresh token is available (managed key / corrupted file),
 *       raise {@code err.anthropic.token_expired_no_refresh} so the UI can
 *       prompt re-login.</li>
 * </ol>
 *
 * <p>This service does NOT handle the OAuth login flow itself — that is
 * RFC-062 PR-4. Until then, MateClaw piggybacks on whatever credentials the
 * user already has on disk from their installed Claude Code client.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeCodeOAuthService {

    /** Refresh 1 minute before the access token actually expires. */
    static final long REFRESH_BUFFER_MS = 60_000L;

    private final ClaudeCodeCredentialsReader reader;
    private final ClaudeCodeTokenRefresher refresher;
    private final ClaudeCodeCredentialsWriter writer;

    /**
     * Returns a valid (un-expired) access token, refreshing if necessary.
     *
     * @throws MateClawException with key {@code err.anthropic.no_claude_code}
     *         if no local credentials are present;
     *         {@code err.anthropic.token_expired_no_refresh} if the token is
     *         expired and cannot be refreshed.
     */
    public String getValidToken() {
        ClaudeCodeCredentials creds = reader.read()
                .orElseThrow(() -> new MateClawException("err.anthropic.no_claude_code",
                        "Claude Code 凭据未找到。请安装 Claude Code 客户端并用 Pro/Max 账号登录。"));

        if (creds.isValid(REFRESH_BUFFER_MS)) {
            return creds.accessToken();
        }

        if (!creds.canRefresh()) {
            throw new MateClawException("err.anthropic.token_expired_no_refresh",
                    "Claude Code token 已过期且无法刷新。请打开 Claude Code 客户端重新登录后再试。");
        }

        log.info("[ClaudeCodeOAuth] access_token expired or near expiry — refreshing (source={})",
                creds.source());
        ClaudeCodeCredentials refreshed = refresher.refresh(creds.refreshToken());

        // Pin destination to the original source so the writer knows where to persist.
        ClaudeCodeCredentials persistable = new ClaudeCodeCredentials(
                refreshed.accessToken(),
                refreshed.refreshToken(),
                refreshed.expiresAtMs(),
                creds.source());
        boolean written = writer.write(creds.accessToken(), persistable);
        if (!written) {
            log.warn("[ClaudeCodeOAuth] token refresh succeeded but persistence failed; "
                    + "using in-memory token for this request");
        }
        return refreshed.accessToken();
    }

    /**
     * Quick check used by the UI / status endpoints — does NOT trigger a
     * refresh.
     *
     * @return true if a non-blank access token exists on disk and isn't yet
     *         past expiry.
     */
    public boolean isLoggedIn() {
        return reader.read().map(c -> c.isValid(0L)).orElse(false);
    }

    /**
     * Returns metadata for the {@code /api/v1/llm/anthropic/oauth/status}
     * endpoint (PR-3) without exposing the token itself.
     */
    public OAuthStatus getStatus() {
        Optional<ClaudeCodeCredentials> opt = reader.read();
        if (opt.isEmpty()) {
            return new OAuthStatus(false, false, 0L, null);
        }
        ClaudeCodeCredentials c = opt.get();
        boolean expired = c.expiresAtMs() > 0L && System.currentTimeMillis() >= c.expiresAtMs();
        return new OAuthStatus(true, expired, c.expiresAtMs(), c.source());
    }

    /**
     * Plain DTO surfaced to the management UI.
     *
     * @param connected   true when local credentials exist
     * @param expired     true when {@link ClaudeCodeCredentials#expiresAtMs()}
     *                    is set and already past
     * @param expiresAtMs raw expiry timestamp (0 means "no expiry recorded")
     * @param source      where the credentials came from; null when not connected
     */
    public record OAuthStatus(
            boolean connected,
            boolean expired,
            long expiresAtMs,
            ClaudeCodeCredentials.Source source
    ) {}
}
