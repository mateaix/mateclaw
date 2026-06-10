package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification of {@link OpenAiRequestRewriter#sanitizeReasoningEffortForProvider}
 * and {@link OpenAiRequestRewriter#isReasoningEffortWhitelistedProvider}.
 *
 * <p>The sanitizer is provider-first with default-deny: only providerId in
 * {@code {openai, azure-openai}} is allowed to carry {@code reasoning_effort}.
 * All other providers (including unknown ones) must strip regardless of what
 * {@code request.model()} says, because the model name may have leaked from a
 * failover primary.
 */
class ReasoningEffortSanitizerTest {

    private static ModelProviderEntity provider(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        return p;
    }

    /**
     * Construct a minimal {@link OpenAiApi.ChatCompletionRequest} via its record canonical
     * constructor with only {@code messages}, {@code model}, and {@code reasoningEffort} set
     * — everything else is null. {@code ChatCompletionRequest} in Spring AI 1.1.4 has no
     * public builder.
     */
    private static OpenAiApi.ChatCompletionRequest request(String model, String reasoningEffort) {
        return new OpenAiApi.ChatCompletionRequest(
                List.of(),          // messages
                model,              // model
                null,               // store
                null,               // metadata
                null,               // frequencyPenalty
                null,               // logitBias
                null,               // logprobs
                null,               // topLogprobs
                null,               // maxTokens
                null,               // maxCompletionTokens
                null,               // n
                null,               // outputModalities
                null,               // audioParameters
                null,               // presencePenalty
                null,               // responseFormat
                null,               // seed
                null,               // serviceTier
                null,               // stop
                null,               // stream
                null,               // streamOptions
                null,               // temperature
                null,               // topP
                null,               // tools
                null,               // toolChoice
                null,               // parallelToolCalls
                null,               // user
                reasoningEffort,    // reasoningEffort
                null,               // webSearchOptions
                null,               // verbosity
                null,               // promptCacheKey
                null,               // safetyIdentifier
                null                // extraBody
        );
    }

    @Test
    @DisplayName("Whitelist: openai is allowed")
    void whitelist_openai() {
        assertTrue(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("openai")));
    }

    @Test
    @DisplayName("Whitelist: azure-openai is allowed")
    void whitelist_azureOpenai() {
        assertTrue(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("azure-openai")));
    }

    @Test
    @DisplayName("Whitelist: case-insensitive (Azure-OpenAI)")
    void whitelist_caseInsensitive() {
        assertTrue(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("Azure-OpenAI")));
    }

    @Test
    @DisplayName("Whitelist: deepseek is denied")
    void denylist_deepseek() {
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("deepseek")));
    }

    @Test
    @DisplayName("Whitelist: kimi family denied")
    void denylist_kimi() {
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("kimi-cn")));
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("kimi-intl")));
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("kimi-code")));
    }

    @Test
    @DisplayName("Whitelist: dashscope / ollama / anthropic denied")
    void denylist_misc() {
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("dashscope")));
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("ollama")));
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(provider("anthropic")));
    }

    @Test
    @DisplayName("Whitelist: unknown providerId denied (default-deny — §5.2 Case E3.3)")
    void denylist_unknownProvider() {
        // This is the critical regression guard: if anyone re-adds a default-allow
        // branch to isReasoningEffortWhitelistedProvider, this case fails first.
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(
                provider("my-custom-openai-compat-gateway")));
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(
                provider("openrouter")));
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(
                provider("together")));
    }

    @Test
    @DisplayName("Whitelist: null provider / null providerId denied")
    void denylist_nulls() {
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(null));
        assertFalse(OpenAiRequestRewriter.isReasoningEffortWhitelistedProvider(new ModelProviderEntity()));
    }

    // ---------- sanitizeReasoningEffortForProvider ----------

    @Test
    @DisplayName("Sanitize no-op: request has no reasoning_effort")
    void sanitize_noop_noReasoningEffort() {
        OpenAiApi.ChatCompletionRequest req = request("gpt-5", null);
        OpenAiApi.ChatCompletionRequest out = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(req, provider("deepseek"));
        assertSame(req, out, "should return same instance when reasoning_effort is already null");
    }

    @Test
    @DisplayName("§5.2 Case E3.1: primary=gpt-5 → fallback=deepseek strips reasoning_effort")
    void sanitize_failover_deepseek_strips() {
        // Simulate failover: OpenAiChatOptions.model still leaked as "gpt-5" on the deepseek request.
        OpenAiApi.ChatCompletionRequest req = request("gpt-5", "high");
        OpenAiApi.ChatCompletionRequest out = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(req, provider("deepseek"));
        assertNull(out.reasoningEffort(), "deepseek is not on the whitelist — strip regardless of model name");
        // Other fields preserved
        assertEquals("gpt-5", out.model());
    }

    @Test
    @DisplayName("§5.2 Case E3.2: kimi / dashscope / ollama also strip")
    void sanitize_failover_otherDenied_strips() {
        for (String pid : List.of("kimi-cn", "kimi-intl", "kimi-code", "dashscope", "ollama", "anthropic")) {
            OpenAiApi.ChatCompletionRequest req = request("gpt-5", "medium");
            OpenAiApi.ChatCompletionRequest out = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(req, provider(pid));
            assertNull(out.reasoningEffort(), "provider=" + pid + " must strip");
        }
    }

    @Test
    @DisplayName("§5.2 Case E3.3: unknown provider strips (default-deny regression guard)")
    void sanitize_unknownProvider_strips() {
        OpenAiApi.ChatCompletionRequest req = request("gpt-5", "high");
        OpenAiApi.ChatCompletionRequest out = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(
                req, provider("my-custom-openai-compat-gateway"));
        assertNull(out.reasoningEffort(),
                "unknown provider must strip (default-deny) — if this fails, someone re-added default-allow");
    }

    @Test
    @DisplayName("Whitelist + supporting model: keep reasoning_effort (gpt-5 on openai)")
    void sanitize_whitelisted_supportingModel_keeps() {
        OpenAiApi.ChatCompletionRequest req = request("gpt-5", "high");
        OpenAiApi.ChatCompletionRequest out = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(req, provider("openai"));
        assertSame(req, out, "gpt-5 on openai should pass through unchanged");
        assertEquals("high", out.reasoningEffort());
    }

    @Test
    @DisplayName("Whitelist + non-supporting model: strip (gpt-4 on openai)")
    void sanitize_whitelisted_nonSupportingModel_strips() {
        // gpt-4 is NOT OPENAI_REASONING family — reasoning_effort is not applicable there.
        OpenAiApi.ChatCompletionRequest req = request("gpt-4", "medium");
        OpenAiApi.ChatCompletionRequest out = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(req, provider("openai"));
        assertNull(out.reasoningEffort(),
                "gpt-4 is whitelisted-provider but non-supporting-family — family gate should strip");
    }

    @Test
    @DisplayName("Azure OpenAI with supporting model: keep reasoning_effort")
    void sanitize_azureOpenai_supporting_keeps() {
        OpenAiApi.ChatCompletionRequest req = request("gpt-5", "low");
        OpenAiApi.ChatCompletionRequest out = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(req, provider("azure-openai"));
        assertEquals("low", out.reasoningEffort());
    }

    @Test
    @DisplayName("Null provider: strip (defensive)")
    void sanitize_nullProvider_strips() {
        OpenAiApi.ChatCompletionRequest req = request("gpt-5", "high");
        OpenAiApi.ChatCompletionRequest out = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(req, null);
        assertNull(out.reasoningEffort());
    }
}
