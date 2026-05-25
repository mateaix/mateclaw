package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import vip.mate.llm.anthropic.oauth.ClaudeCodeApiHeaders;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

/**
 * Strategy implementation for {@link ModelProtocol#ANTHROPIC_CLAUDE_CODE}.
 *
 * <p>Sends Anthropic Messages API requests authenticated with the user's
 * Claude Code OAuth subscription token instead of an API key — letting users
 * with a Claude Pro/Max plan run MateClaw against their existing entitlement.
 *
 * <h2>How OAuth changes the wire format</h2>
 * <ol>
 *   <li>{@code Authorization: Bearer <oauth-token>} replaces {@code x-api-key}.
 *       Spring AI's {@link AnthropicApi} only sets {@code x-api-key} when the
 *       supplied {@code ApiKey.getValue()} returns a non-blank string, so we
 *       pass a {@link NoopApiKey} to satisfy the non-null assertion without
 *       leaking a key header.</li>
 *   <li>{@code anthropic-beta} must include {@code claude-code-20250219} and
 *       {@code oauth-2025-04-20} or Anthropic's edge intermittently 500s.
 *       We push these via {@link AnthropicApi.Builder#anthropicBetaFeatures}
 *       so Spring AI's existing header-merging logic still applies.</li>
 *   <li>{@code User-Agent: claude-cli/<ver>} (bare — no suffix) and
 *       {@code x-app: cli} masquerade as the Claude Code CLI. Suffix variants
 *       like {@code (external, cli)} are anti-abuse fingerprints; see
 *       {@link ClaudeCodeApiHeaders#userAgent()}.</li>
 * </ol>
 *
 * <h2>Token lifecycle</h2>
 * <p>Each {@link #build} call asks {@link ClaudeCodeOAuthService} for a valid
 * access token. The service auto-refreshes when within 60s of expiry and
 * persists the fresh credential back to whichever source (Keychain / JSON
 * file) it originally read from. The constructed {@link AnthropicApi} pins
 * the token at build time — for a multi-hour session this is fine because
 * tokens last hours and Spring AI's call-site retry covers the rare case
 * where a token rolls mid-call (next request rebuilds with a fresh token).
 */
@Slf4j
@Component
public class ClaudeCodeChatModelBuilder implements ChatModelBuilder {

    private final AnthropicChatModelBuilder anthropicBuilder;
    private final ClaudeCodeOAuthService oauthService;
    private final ClaudeCodeApiHeaders apiHeaders;
    private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;
    private final ObjectMapper objectMapper;

    public ClaudeCodeChatModelBuilder(
            AnthropicChatModelBuilder anthropicBuilder,
            ClaudeCodeOAuthService oauthService,
            ClaudeCodeApiHeaders apiHeaders,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectMapper objectMapper) {
        this.anthropicBuilder = anthropicBuilder;
        this.oauthService = oauthService;
        this.apiHeaders = apiHeaders;
        this.restClientBuilderProvider = restClientBuilderProvider;
        this.webClientBuilderProvider = webClientBuilderProvider;
        this.observationRegistryProvider = observationRegistryProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.ANTHROPIC_CLAUDE_CODE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        // 1) Pull a fresh access token (auto-refreshes when near expiry; throws
        //    err.anthropic.no_claude_code or err.anthropic.token_expired_no_refresh
        //    so the UI / global handler can present an actionable message).
        String accessToken = oauthService.getValidToken();

        // 2) Build the Anthropic API client wired with OAuth headers.
        AnthropicApi api = buildOauthAnthropicApi(accessToken, model.getRequestTimeoutSeconds());

        // 3) Reuse the canonical Anthropic options builder — same Claude 4.7
        //    sampling-params handling, thinking-budget mapping, prompt cache.
        AnthropicChatOptions options = anthropicBuilder.buildAnthropicOptions(model);

        AnthropicChatModel raw = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .retryTemplate(retry)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();

        // 4) Wrap with the OAuth identity decorator. Anthropic's edge rate-limits /
        //    5xxs requests that don't claim Claude Code identity in the system
        //    prompt — symptom: 429 rate_limit_error with body "Error" on quiet
        //    accounts. See ClaudeCodeIdentityChatModelDecorator javadoc.
        return new ClaudeCodeIdentityChatModelDecorator(raw);
    }

    /**
     * Construct an {@link AnthropicApi} whose underlying RestClient + WebClient
     * are pre-stamped with OAuth-mode headers. Package-private so unit tests
     * can verify header composition without spinning up a chat model.
     */
    AnthropicApi buildOauthAnthropicApi(String accessToken) {
        return buildOauthAnthropicApi(accessToken, null);
    }

    /**
     * Overload — same OAuth-stamped Anthropic client, with a
     * per-model read-timeout override threaded through to the underlying
     * RestClient + WebClient timeouts.
     */
    AnthropicApi buildOauthAnthropicApi(String accessToken, Integer readTimeoutOverride) {
        String authHeader = apiHeaders.bearerAuth(accessToken);
        String userAgent = apiHeaders.userAgent();
        String xApp = apiHeaders.xApp();
        String betas = apiHeaders.allBetas();

        // Real Claude Code is an Electron + Node app that uses the official
        // Anthropic JS SDK. The SDK auto-sets `accept: application/json` and
        // `anthropic-dangerous-direct-browser-access: true` on every request.
        // Spring AI's Java client doesn't, so Anthropic's edge fingerprint
        // sees the missing headers and treats the traffic as suspicious —
        // rate-limited harder than spec'd.
        RestClient.Builder restClientBuilder = AnthropicChatModelBuilder.applyHttpTimeouts(
                        restClientBuilderProvider.getIfAvailable(RestClient::builder), readTimeoutOverride)
                .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .defaultHeader("anthropic-dangerous-direct-browser-access", "true")
                .defaultHeader("x-app", xApp)
                // Rewrite system string → array before the request hits the wire.
                // Anthropic's OAuth anti-abuse gate requires system to be an array;
                // see ClaudeCodeSystemArrayInterceptor for the full explanation.
                .requestInterceptor(new ClaudeCodeSystemArrayInterceptor(objectMapper))
                // Diagnostic: log Anthropic's rate-limit headers on 429 so we
                // can tell apart "5h Pro quota exhausted" (tokens-remaining=0,
                // retry-after huge) from "anti-abuse gate" (tokens-remaining
                // large, retry-after small) from "burst limit hit" without
                // staring at SDK internals.
                .requestInterceptor(new RateLimitDiagnosticInterceptor());

        WebClient.Builder webClientBuilder = AnthropicChatModelBuilder.applyHttpTimeoutsToWebClient(
                        webClientBuilderProvider.getIfAvailable(WebClient::builder), readTimeoutOverride)
                .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .defaultHeader("anthropic-dangerous-direct-browser-access", "true")
                .defaultHeader("x-app", xApp)
                // Rewrite system string → array (streaming path counterpart).
                .filter(new ClaudeCodeSystemArrayExchangeFilter(objectMapper))
                .filter(new RateLimitDiagnosticExchangeFilter());

        // NoopApiKey.getValue() returns "" → Spring AI's addDefaultHeadersIfMissing
        // skips x-api-key. The Builder.build() Assert.notNull on apiKey still
        // passes because the object is non-null.
        return AnthropicApi.builder()
                .apiKey(new NoopApiKey())
                .anthropicBetaFeatures(betas)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();
    }
}
