package vip.mate.llm.chatmodel;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.anthropic.oauth.ClaudeCodeApiHeaders;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.anthropic.oauth.ClaudeCodeVersionDetector;
import vip.mate.llm.model.ModelProtocol;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Header-construction + token-fetch coverage for the Claude Code OAuth chat
 * model builder. Building a real {@link AnthropicApi} doesn't make a network
 * call (Spring AI defers all I/O to {@code chatCompletionEntity}), so these
 * tests can exercise the full assembly path without mocking the API client.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeCodeChatModelBuilderTest {

    @Mock
    private AnthropicChatModelBuilder anthropicBuilder;

    @Mock
    private ClaudeCodeOAuthService oauthService;

    private ClaudeCodeApiHeaders apiHeaders;

    private ClaudeCodeChatModelBuilder builder;

    @BeforeEach
    void setUp() {
        // Real ApiHeaders with a stub version detector — the version string
        // shows up verbatim in User-Agent assertions.
        ClaudeCodeVersionDetector detector = new ClaudeCodeVersionDetector() {
            @Override
            public String get() { return "2.1.114"; }
        };
        apiHeaders = new ClaudeCodeApiHeaders(detector);

        builder = new ClaudeCodeChatModelBuilder(
                anthropicBuilder,
                oauthService,
                apiHeaders,
                providerOf(RestClient::builder),
                providerOf(WebClient::builder),
                providerOf(() -> ObservationRegistry.NOOP),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("supportedProtocol returns ANTHROPIC_CLAUDE_CODE")
    void supportedProtocol() {
        assertEquals(ModelProtocol.ANTHROPIC_CLAUDE_CODE, builder.supportedProtocol());
    }

    @Test
    @DisplayName("buildOauthAnthropicApi accepts a token and produces a non-null AnthropicApi")
    void buildOauthAnthropicApi_returnsClient() {
        // Sanity check: the NoopApiKey path passes Spring AI's notNull assertion
        // and the OAuth headers attach without throwing. If this test ever
        // fails, the most likely cause is a Spring AI upgrade tightening the
        // ApiKey contract — see ClaudeCodeChatModelBuilder javadoc.
        AnthropicApi api = builder.buildOauthAnthropicApi("sk-ant-oat01-test-token");
        assertNotNull(api);
    }

    @Test
    @DisplayName("build delegates to oauthService and reuses anthropicBuilder.buildAnthropicOptions")
    void build_invokesOauthAndReusesOptions() {
        when(oauthService.getValidToken()).thenReturn("tok-123");
        // anthropicBuilder.buildAnthropicOptions returns a real options object —
        // we don't need a strict comparison, just that it gets invoked once and
        // its result is fed through.
        when(anthropicBuilder.buildAnthropicOptions(any()))
                .thenReturn(org.springframework.ai.anthropic.AnthropicChatOptions.builder().build());

        var result = builder.build(new vip.mate.llm.model.ModelConfigEntity(), null,
                RetryTemplate.defaultInstance());
        assertNotNull(result);
        verify(oauthService, times(1)).getValidToken();
        verify(anthropicBuilder, times(1)).buildAnthropicOptions(any());
    }

    @Test
    @DisplayName("build propagates OAuth errors without calling buildAnthropicOptions")
    void build_propagatesOauthErrors() {
        // Simulates "no Claude Code on disk" — caller surface is the same
        // MateClawException so the global handler can format the i18n message.
        when(oauthService.getValidToken()).thenThrow(new MateClawException(
                "err.anthropic.no_claude_code", "no creds"));

        assertThrows(MateClawException.class,
                () -> builder.build(new vip.mate.llm.model.ModelConfigEntity(), null, null));
        // anthropicBuilder shouldn't have been touched — short-circuit before
        // it would have wasted a buildAnthropicOptions call.
        verify(anthropicBuilder, never()).buildAnthropicOptions(any());
    }

    /* ----- ObjectProvider test helper ----- */

    /** Minimal {@link ObjectProvider} that defers to a {@link Supplier} for {@code getIfAvailable}. */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(Supplier<T> supplier) {
        ObjectProvider<T> mock = mock(ObjectProvider.class);
        // Use lenient — not every test triggers a getIfAvailable call (e.g.
        // the supportedProtocol test takes a short path), and the strict
        // default would fail with UnnecessaryStubbingException.
        lenient().when(mock.getIfAvailable(any(Supplier.class))).thenAnswer(inv -> {
            Supplier<T> fallback = inv.getArgument(0);
            T v = supplier.get();
            return v != null ? v : fallback.get();
        });
        return mock;
    }
}
