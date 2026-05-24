package vip.mate.llm.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService.OAuthStatus;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

/**
 * RFC-062 PR-3: management-UI surface for the Claude Code OAuth provider.
 *
 * <p>Unlike the OpenAI ChatGPT OAuth flow, Claude Code OAuth piggybacks on
 * the user's locally-installed Claude Code client — credentials live in the
 * macOS Keychain or {@code ~/.claude/.credentials.json}, not in
 * {@code mate_model_provider}. So this controller is read-only:
 *
 * <ul>
 *   <li>{@code GET /status} — re-reads from disk and reports
 *       connected / expired / expiry timestamp / source.</li>
 *   <li>{@code POST /reload} — alias of status, semantically signals
 *       "I just logged in via Claude Code, please re-detect" so the UI can
 *       refresh state without polling. Also triggers an auto-refresh of the
 *       access token when it's near expiry, so the user can verify end-to-end
 *       token validity from the management page.</li>
 * </ul>
 *
 * <p>The PKCE-based in-app login flow (without requiring local Claude Code)
 * lands in PR-4 and will add {@code /authorize} + {@code /callback-paste}
 * endpoints here.
 */
@Slf4j
@Tag(name = "Anthropic Claude Code OAuth")
@RestController
@RequestMapping("/api/v1/oauth/anthropic")
@RequiredArgsConstructor
public class ClaudeCodeOAuthController {

    private final ClaudeCodeOAuthService oauthService;

    @Operation(summary = "Read current Claude Code OAuth credential status from local disk")
    @GetMapping("/status")
    @RequireGlobalAdmin
    public R<OAuthStatus> status() {
        return R.ok(oauthService.getStatus());
    }

    /**
     * Re-detect credentials and force a refresh-if-near-expiry. Returns the
     * post-action status so the UI can update without a follow-up GET.
     *
     * <p>If no credentials exist or refresh fails, the underlying exception is
     * caught here and surfaced as {@code connected=false} status — the UI
     * shouldn't show a red banner just because the user hasn't logged into
     * Claude Code yet.
     */
    @Operation(summary = "Force re-detect credentials and refresh if near expiry")
    @PostMapping("/reload")
    @RequireGlobalAdmin
    public R<OAuthStatus> reload() {
        try {
            oauthService.getValidToken();
        } catch (Exception e) {
            // Expected when not logged in or refresh upstream is down.
            // Status response carries enough detail (connected/expired/source)
            // for the UI to decide what to render.
            log.debug("[ClaudeCodeOAuth] reload encountered: {}", e.getMessage());
        }
        return R.ok(oauthService.getStatus());
    }
}
