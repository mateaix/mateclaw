package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@link OpenAiCompatibleChatModelBuilder#buildOpenAiOptions}
 * forwarding unrecognized top-level {@code generateKwargs} keys into
 * {@link OpenAiChatOptions#getExtraBody()} via
 * {@link ProviderGenerateKwargs#collectPassthroughExtraBody}.
 *
 * <p>Locks in the fix: previously, an admin-configured key like vLLM's
 * {@code chat_template_kwargs} (used to disable Qwen thinking mode) was silently
 * dropped because {@code buildOpenAiOptions} only read a fixed allow-list of
 * known keys out of {@code generateKwargs} and never copied anything else into
 * {@code extraBody}.
 */
@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleChatModelBuilderTest {

    @Mock
    private ModelProviderService modelProviderService;

    private OpenAiCompatibleChatModelBuilder builder;
    private ModelProviderEntity provider;

    @BeforeEach
    void setUp() {
        // The ObjectProvider<...> constructor params (RestClient.Builder / WebClient.Builder /
        // ObservationRegistry) are only consumed by buildOpenAiApi(), never by
        // buildOpenAiOptions() under test here, so null is safe — nothing in this test class
        // exercises the HTTP-client-construction path.
        builder = new OpenAiCompatibleChatModelBuilder(
                modelProviderService,
                null,
                null,
                null);
        provider = new ModelProviderEntity();
        provider.setProviderId("test-openai-compatible");
    }

    @Test
    @DisplayName("Provider error payloads redact credentials and stay bounded")
    void providerErrorPayloadIsRedactedAndTruncated() {
        String secret = "Bearer abc.def.ghi";
        String payload = "{\"authorization\":\"" + secret + "\",\"api_key\":\"sk-private\",\"detail\":\""
                + "x".repeat(2000) + "\"}";

        String safe = OpenAiCompatibleChatModelBuilder.redactAndTruncate(payload);

        assertFalse(safe.contains("abc.def.ghi"));
        assertFalse(safe.contains("sk-private"));
        assertTrue(safe.contains("[REDACTED]"));
        assertTrue(safe.length() <= 1025);
    }

    @AfterEach
    void clearHolder() {
        ThinkingLevelHolder.clear();
    }

    private static ModelConfigEntity model(String modelName) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setModelName(modelName);
        return m;
    }

    @Test
    @DisplayName("Unrecognized top-level key (chat_template_kwargs) is forwarded into extraBody with the nested map intact")
    void unknownKey_chatTemplateKwargs_forwardedToExtraBody() {
        Map<String, Object> chatTemplateKwargs = Map.of("enable_thinking", false);
        Map<String, Object> kwargs = Map.of("chat_template_kwargs", chatTemplateKwargs);
        when(modelProviderService.readProviderGenerateKwargs(provider)).thenReturn(kwargs);

        OpenAiChatOptions options = builder.buildOpenAiOptions(model("gpt-4-turbo"), provider);

        assertNotNull(options.getExtraBody(), "extraBody must be populated when a passthrough key is present");
        assertEquals(chatTemplateKwargs, options.getExtraBody().get("chat_template_kwargs"),
                "the nested map must be forwarded verbatim, not flattened or re-wrapped");
    }

    @Test
    @DisplayName("Known key (temperature) is consumed via its typed option and NOT duplicated in extraBody; unknown key still forwarded")
    void knownKeyGoesTyped_unknownKeyGoesExtraBody_noDuplication() {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("temperature", 0.7);
        kwargs.put("chat_template_kwargs", Map.of("enable_thinking", false));
        when(modelProviderService.readProviderGenerateKwargs(provider)).thenReturn(kwargs);

        OpenAiChatOptions options = builder.buildOpenAiOptions(model("gpt-4-turbo"), provider);

        assertEquals(Double.valueOf(0.7), options.getTemperature(),
                "temperature must still be resolved into the typed OpenAiChatOptions field");
        assertNotNull(options.getExtraBody());
        assertFalse(options.getExtraBody().containsKey("temperature"),
                "temperature is a RESERVED_GENERATE_KWARGS_KEYS entry — it must not be duplicated into extraBody");
        assertTrue(options.getExtraBody().containsKey("chat_template_kwargs"),
                "the unrecognized key must still be forwarded alongside the typed temperature handling");
    }

    @Test
    @DisplayName("Known provider-discovery key (modelsPath) is reserved and never forwarded into extraBody")
    void knownKey_modelsPath_notForwardedToExtraBody() {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("modelsPath", "/openai/v1/models");
        kwargs.put("chat_template_kwargs", Map.of("enable_thinking", false));
        when(modelProviderService.readProviderGenerateKwargs(provider)).thenReturn(kwargs);

        OpenAiChatOptions options = builder.buildOpenAiOptions(model("gpt-4-turbo"), provider);

        assertNotNull(options.getExtraBody());
        assertFalse(options.getExtraBody().containsKey("modelsPath"),
                "modelsPath is consumed by OpenAiModelsPath and must not leak into chat completion request bodies");
        assertTrue(options.getExtraBody().containsKey("chat_template_kwargs"),
                "unrecognized passthrough keys must still be forwarded");
    }

    @Test
    @DisplayName("Snake_case built-in search kwargs enable web search options")
    void snakeCaseBuiltinSearchKwargs_enableWebSearchOptions() {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("enable_search", true);
        kwargs.put("search_strategy", "high");
        when(modelProviderService.readProviderGenerateKwargs(provider)).thenReturn(kwargs);

        OpenAiChatOptions options = builder.buildOpenAiOptions(model("gpt-4o-search-preview"), provider);

        assertNotNull(options.getWebSearchOptions(),
                "enable_search should be treated the same as enableSearch");
    }

    @Test
    @DisplayName("Empty generateKwargs: no exception, extraBody stays empty/null (pre-existing behavior preserved)")
    void emptyGenerateKwargs_noExceptionNoExtraBody() {
        when(modelProviderService.readProviderGenerateKwargs(provider)).thenReturn(Map.of());

        OpenAiChatOptions options = assertDoesNotThrow(
                () -> builder.buildOpenAiOptions(model("gpt-4-turbo"), provider));

        // collectPassthroughExtraBody returns Map.of() for empty kwargs, so the merge block in
        // buildOpenAiOptions is skipped entirely and extraBody is left at whatever
        // OpenAiChatOptions.builder().build() defaults to (null) — never a non-null empty map.
        assertTrue(options.getExtraBody() == null || options.getExtraBody().isEmpty(),
                "no passthrough keys present — extraBody must not be force-populated");
    }

    @Test
    @DisplayName("Passthrough extraBody keys coexist with DeepSeekV4ThinkingDecorator-injected keys — neither clobbers the other")
    void passthroughAndDecoratorInjectedKeys_coexist() {
        // T2 sub-case 4: verify the merge-order comment in buildOpenAiOptions ("get-then-merge
        // rather than overwrite") actually holds up once a second layer (the DeepSeek V4
        // decorator, applied at request time in build()) also writes into extraBody.
        Map<String, Object> chatTemplateKwargs = Map.of("enable_thinking", false);
        Map<String, Object> kwargs = Map.of("chat_template_kwargs", chatTemplateKwargs);
        when(modelProviderService.readProviderGenerateKwargs(provider)).thenReturn(kwargs);

        OpenAiChatOptions options = builder.buildOpenAiOptions(model("deepseek-v4-flash"), provider);
        assertEquals(chatTemplateKwargs, options.getExtraBody().get("chat_template_kwargs"));

        ThinkingLevelHolder.set("high");
        DeepSeekV4ThinkingDecorator decorator = new DeepSeekV4ThinkingDecorator(new NoopChatModel());
        Prompt patched = decorator.transform(new Prompt(List.of(new UserMessage("hi")), options));
        OpenAiChatOptions patchedOptions = (OpenAiChatOptions) patched.getOptions();

        assertEquals(chatTemplateKwargs, patchedOptions.getExtraBody().get("chat_template_kwargs"),
                "T1's passthrough entry must survive the decorator's own extraBody merge");
        assertEquals(Map.of("type", "enabled"),
                patchedOptions.getExtraBody().get(DeepSeekV4ThinkingDecorator.THINKING_FIELD),
                "the decorator-injected thinking key must still be present alongside the passthrough entry");
    }

    /* ---------- Test double ---------- */

    private static class NoopChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
    }
}
