package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import vip.mate.llm.model.ModelFamily;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-aware rewrites applied to an outbound OpenAI-compatible
 * {@link OpenAiApi.ChatCompletionRequest} just before it hits the wire.
 *
 * <p>{@link OpenAiCompatibleChatModelBuilder} runs these in a fixed order on both
 * the blocking and streaming chat-completion paths. Each method is a pure
 * transformation: it returns the original request unchanged when it has nothing
 * to do, or a rebuilt request (the Spring AI record is immutable) otherwise.
 *
 * <p>The rewrites exist because OpenAI-compatible providers diverge in ways
 * Spring AI's {@code OpenAiChatOptions} cannot express — reasoning-content
 * replay contracts, reasoning-effort acceptance, strict tool-choice validation,
 * video media encoding, and Kimi's built-in web search tool.
 */
@Slf4j
final class OpenAiRequestRewriter {

    private OpenAiRequestRewriter() {}

    // ==================== reasoning_content patching ====================

    /**
     * Consume the {@link AssistantThinkingRelay} entry and rebuild the outbound
     * request so assistant tool-call / thinking messages carry the correct
     * {@code reasoning_content}.
     *
     * <p>This is the consumer side of the relay. The producer
     * ({@code NodeStreamingChatHelper}) stashes per-assistant thinking keyed on a
     * token embedded in {@code request.user()}. Here we:
     * <ol>
     *   <li>{@link AssistantThinkingRelay#take(String)} the entry and restore
     *       {@code request.user()} to {@code entry.originalUser()} so the
     *       internal token never reaches the provider.</li>
     *   <li>Compute {@code lastUserIdx} (the boundary of the current user turn).
     *       Assistant messages at {@code i <= lastUserIdx} are prior-turn history:
     *       their {@code reasoning_content} normally stays null. Only
     *       {@code i > lastUserIdx} messages are eligible for patching, unless the
     *       provider policy opts into cross-turn patching.</li>
     *   <li>Select a {@link FallbackPolicy} by {@code providerId}. When the relay
     *       has a real value we use it; when empty, the policy decides whether to
     *       inject {@code " "} (legacy tolerance) or leave {@code null} to surface
     *       an explicit provider error.</li>
     * </ol>
     *
     * <p>The relay iterator advances for every assistant message (including
     * prior-turn ones) to stay positionally aligned with the producer's extraction.
     */
    static OpenAiApi.ChatCompletionRequest patchReasoningContent(
            OpenAiApi.ChatCompletionRequest request, ModelProviderEntity provider) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }

        // 1. Consume relay (if any) and compute the sanitized user field.
        AssistantThinkingRelay.RelayEntry entry = AssistantThinkingRelay.take(request.user());
        String sanitizedUser = (entry != null)
                ? entry.originalUser()
                : (AssistantThinkingRelay.isToken(request.user()) ? null : request.user());

        // 2. Detect thinking mode — relay presence is also a trigger.
        boolean thinkingMode = request.reasoningEffort() != null
                || requiresReasoningContentPatch(request.model())
                || request.messages().stream().anyMatch(m ->
                        m.role() == OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
                                && m.reasoningContent() != null)
                || entry != null;
        if (!thinkingMode) {
            // Nothing to patch but we may still need to strip a leaked relay token from user.
            return request.user() != null && !request.user().equals(sanitizedUser)
                    ? rebuildWithUser(request, sanitizedUser)
                    : request;
        }

        // 3. Find lastUserIdx so we can skip cross-turn assistants.
        int lastUserIdx = -1;
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            if (request.messages().get(i).role() == OpenAiApi.ChatCompletionMessage.Role.USER) {
                lastUserIdx = i;
                break;
            }
        }

        FallbackPolicy policy = FallbackPolicy.forProvider(provider);
        java.util.Iterator<String> it = (entry != null)
                ? entry.thinkings().iterator()
                : java.util.Collections.emptyIterator();

        // 4. Walk messages, patching only in-turn assistants; always advance iterator
        //    for all assistants so producer/consumer positions stay aligned.
        boolean anyPatched = false;
        List<OpenAiApi.ChatCompletionMessage> patched = new ArrayList<>(request.messages().size());
        for (int i = 0; i < request.messages().size(); i++) {
            OpenAiApi.ChatCompletionMessage msg = request.messages().get(i);
            if (msg.role() != OpenAiApi.ChatCompletionMessage.Role.ASSISTANT) {
                patched.add(msg);
                continue;
            }

            String next = it.hasNext() ? it.next() : null;

            // Already has a real value: leave alone
            if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                patched.add(msg);
                continue;
            }

            // Cross-turn assistant: usually skipped, since thinking resets across
            // user turns. But some providers require reasoning_content even on
            // prior-turn assistants and reject requests where any prior assistant
            // has it null. For policies with patchCrossTurn=true, fall through and
            // patch with the empty fallback (" ") so multi-turn conversations
            // don't 400.
            if (i <= lastUserIdx && !policy.patchCrossTurn) {
                patched.add(msg);
                continue;
            }

            boolean hasToolCalls = msg.toolCalls() != null && !msg.toolCalls().isEmpty();
            if (!hasToolCalls && !policy.patchNonToolCall) {
                patched.add(msg);
                continue;
            }

            String injected;
            if (next != null && !next.isEmpty()) {
                injected = next;
            } else {
                injected = policy.emptyFallback;
                if (injected == null && policy.warnOnMissingReal) {
                    log.warn("[patchReasoningContent] provider={} requires real reasoning_content "
                                    + "but relay has no value for assistant message at index {}; "
                                    + "leaving null so provider returns explicit error.",
                            providerIdOrUnknown(provider), i);
                }
            }
            if (injected == null && msg.reasoningContent() == null) {
                // No change — keep original
                patched.add(msg);
                continue;
            }
            patched.add(new OpenAiApi.ChatCompletionMessage(
                    msg.rawContent(), msg.role(), msg.name(), msg.toolCallId(),
                    msg.toolCalls(), msg.refusal(), msg.audioOutput(),
                    msg.annotations(), injected));
            anyPatched = true;
        }

        boolean userChanged = request.user() != null && !request.user().equals(sanitizedUser)
                || (request.user() == null && sanitizedUser != null);
        if (!anyPatched && !userChanged) {
            return request;
        }

        // 5. Rebuild with patched messages + sanitized user.
        return new OpenAiApi.ChatCompletionRequest(
                patched,
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                sanitizedUser,
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    /**
     * Provider-keyed policy for how {@link #patchReasoningContent} behaves when
     * the relay has no real thinking for an in-turn assistant message.
     *
     * <ul>
     *   <li>{@code emptyFallback}: value to inject when the relay has no real
     *       value — {@code null} means leave {@code reasoning_content} null;
     *       {@code " "} preserves legacy tolerance.</li>
     *   <li>{@code warnOnMissingReal}: emit WARN when {@code emptyFallback==null}
     *       fires.</li>
     *   <li>{@code patchNonToolCall}: whether to patch assistant messages without
     *       tool_calls. DeepSeek's contract applies to all in-turn assistant
     *       messages; others only to tool_call messages.</li>
     *   <li>{@code patchCrossTurn}: whether to also patch prior-turn assistants
     *       ({@code i <= lastUserIdx}). DeepSeek requires reasoning_content on
     *       every assistant message in the request, including prior-turn history,
     *       and MateClaw does not persist reasoning_content — so cross-turn
     *       patching keeps multi-turn conversations from 400-ing.</li>
     * </ul>
     *
     * <p>{@code DEFAULT} keeps the legacy {@code " "} tolerance rather than going
     * no-op: an unrecognized provider (self-hosted DeepSeek-like backend, custom
     * OpenAI-compatible gateway) might still require the patch.
     */
    private enum FallbackPolicy {
        DEEPSEEK(" ",  false, true,  true),
        KIMI    (" ",  false, false, false),
        OPENAI  (" ",  false, false, false),
        DEFAULT (" ",  false, false, false);

        final String emptyFallback;
        final boolean warnOnMissingReal;
        final boolean patchNonToolCall;
        /** Whether to also patch prior-turn assistants ({@code i <= lastUserIdx}). */
        final boolean patchCrossTurn;

        FallbackPolicy(String emptyFallback, boolean warnOnMissingReal,
                       boolean patchNonToolCall, boolean patchCrossTurn) {
            this.emptyFallback = emptyFallback;
            this.warnOnMissingReal = warnOnMissingReal;
            this.patchNonToolCall = patchNonToolCall;
            this.patchCrossTurn = patchCrossTurn;
        }

        static FallbackPolicy forProvider(ModelProviderEntity provider) {
            if (provider == null || provider.getProviderId() == null) {
                return DEFAULT;
            }
            String id = provider.getProviderId().toLowerCase();
            return switch (id) {
                case "deepseek" -> DEEPSEEK;
                case "kimi-cn", "kimi-intl", "kimi-code" -> KIMI;
                case "openai", "azure-openai" -> OPENAI;
                default -> DEFAULT;
            };
        }
    }

    /**
     * Rebuild a request with only the {@code user} field replaced. Used when
     * {@link #patchReasoningContent} has no assistant-message changes but must
     * strip a relay token from the outbound {@code user} field.
     */
    private static OpenAiApi.ChatCompletionRequest rebuildWithUser(
            OpenAiApi.ChatCompletionRequest request, String newUser) {
        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                newUser,
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    private static boolean requiresReasoningContentPatch(String modelName) {
        ModelFamily family = ModelFamily.detect(modelName);
        return family.isThinking();
    }

    // ==================== reasoning_effort sanitizing ====================

    /**
     * Provider-first sanitization of {@code reasoning_effort}.
     *
     * <p>Authoritative judgement uses {@code provider.getProviderId()} as a
     * whitelist (default-deny). Only official OpenAI providers may carry
     * {@code reasoning_effort}; everything else — known non-supporters and any
     * unrecognized providerId (self-hosted gateways, aggregators) — is stripped.
     *
     * <p>{@code request.model()} is intentionally distrusted here: the failover
     * chain can reuse the same {@code OpenAiChatOptions} across providers, so a
     * failover hop from a GPT-5 primary to DeepSeek would still carry model name
     * "gpt-5". Checking only the model family would let the primary's
     * {@code reasoning_effort} leak to DeepSeek.
     *
     * <p>Only when the provider is whitelisted do we fall through to the
     * {@link ModelFamily} check.
     */
    static OpenAiApi.ChatCompletionRequest sanitizeReasoningEffortForProvider(
            OpenAiApi.ChatCompletionRequest request, ModelProviderEntity provider) {
        if (request == null || request.reasoningEffort() == null) {
            return request;
        }

        if (!isReasoningEffortWhitelistedProvider(provider)) {
            log.warn("[reasoning_effort sanitizer] provider={} is not on the reasoning_effort "
                            + "whitelist (only openai/azure-openai are); stripping value='{}' "
                            + "(request.model()='{}' may be leaked from failover primary).",
                    providerIdOrUnknown(provider), request.reasoningEffort(), request.model());
            return rebuildWithReasoningEffort(request, null);
        }

        ModelFamily targetFamily = ModelFamily.detect(request.model());
        if (!targetFamily.supportsReasoningEffort()) {
            log.warn("[reasoning_effort sanitizer] provider={} model={} family={} does not "
                            + "support reasoning_effort; stripping value='{}'.",
                    provider.getProviderId(), request.model(), targetFamily, request.reasoningEffort());
            return rebuildWithReasoningEffort(request, null);
        }
        return request;
    }

    /**
     * Whitelist of providers known to accept {@code reasoning_effort} on
     * {@code /v1/chat/completions} (or {@code /v1/responses}). Anything else is
     * denied. Adding a provider here must come with a corresponding test case.
     */
    static boolean isReasoningEffortWhitelistedProvider(ModelProviderEntity provider) {
        if (provider == null || provider.getProviderId() == null) {
            return false;
        }
        String id = provider.getProviderId().toLowerCase();
        return switch (id) {
            case "openai", "azure-openai" -> true;
            default -> false;
        };
    }

    private static String providerIdOrUnknown(ModelProviderEntity p) {
        return (p == null || p.getProviderId() == null) ? "<unknown>" : p.getProviderId();
    }

    /**
     * Rebuild a request with a new {@code reasoningEffort} value (typically
     * {@code null} to strip).
     */
    private static OpenAiApi.ChatCompletionRequest rebuildWithReasoningEffort(
            OpenAiApi.ChatCompletionRequest request, String newReasoningEffort) {
        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                request.user(),
                newReasoningEffort,
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    /**
     * GPT-5 compatibility: on the {@code /v1/chat/completions} path, {@code tools}
     * and {@code reasoning_effort} cannot both be present.
     *
     * <p>When a gpt-5* model carries both, {@code reasoning_effort} is removed and
     * a warning is logged. To use {@code reasoning_effort}, switch to the
     * {@code /v1/responses} endpoint via the {@code completionsPath} generate kwarg.
     */
    static OpenAiApi.ChatCompletionRequest stripReasoningEffortIfIncompatible(
            OpenAiApi.ChatCompletionRequest request) {
        if (request.reasoningEffort() == null) {
            return request;
        }
        if (request.tools() == null || request.tools().isEmpty()) {
            return request;
        }
        String model = request.model();
        if (model == null || !model.trim().toLowerCase().startsWith("gpt-5")) {
            return request;
        }

        log.warn("[GPT-5 compat] model {} carries both tools and reasoning_effort on "
                        + "chat/completions; removing reasoning_effort to avoid a 400. "
                        + "To use reasoning_effort, set completionsPath to /v1/responses",
                model);

        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                request.user(),
                null,  // reasoningEffort — removed
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    // ==================== tool_choice / media ====================

    /**
     * Strip {@code tool_choice="auto"} from outbound requests.
     *
     * <p>Per the OpenAI spec, omitting {@code tool_choice} when {@code tools} is
     * non-empty is equivalent to {@code "auto"}. Stripping the explicit literal:
     * <ul>
     *   <li>does not change behavior on compliant servers — they still default to
     *       auto when tools are present;</li>
     *   <li>unblocks strict OpenAI-compatible self-hosted serving frameworks that
     *       reject {@code tool_choice="auto"} at request validation time unless
     *       launched with an auto-tool-choice opt-in flag.</li>
     * </ul>
     *
     * <p>Explicit values other than {@code "auto"} are passed through unchanged.
     */
    static OpenAiApi.ChatCompletionRequest stripAutoToolChoice(OpenAiApi.ChatCompletionRequest request) {
        Object tc = request.toolChoice();
        if (tc == null || !"auto".equals(String.valueOf(tc))) {
            return request;
        }
        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                null,  // toolChoice — strip "auto" so strict OpenAI-compatible servers accept the request
                request.parallelToolCalls(),
                request.user(),
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    /**
     * Convert video content blocks that Spring AI mis-serializes as
     * {@code image_url} into {@code video_url} format.
     *
     * <p>Spring AI's {@code MediaContent} has no video_url type, so every non-audio
     * / non-pdf media block is serialized as {@code image_url}. Models such as
     * Zhipu GLM-5V require video to use {@code video_url}; otherwise they report
     * an image parse error. This walks user-message content and rewrites any
     * {@code data:video/*} {@code image_url} into {@code video_url}.
     */
    @SuppressWarnings("unchecked")
    static OpenAiApi.ChatCompletionRequest patchVideoMediaContent(OpenAiApi.ChatCompletionRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }

        boolean needsPatch = false;
        for (var msg : request.messages()) {
            if (msg.role() == OpenAiApi.ChatCompletionMessage.Role.USER) {
                Object raw = msg.rawContent();
                if (raw instanceof List<?> parts) {
                    for (Object part : parts) {
                        // MediaContent record
                        if (part instanceof OpenAiApi.ChatCompletionMessage.MediaContent mc
                                && "image_url".equals(mc.type())
                                && mc.imageUrl() != null
                                && mc.imageUrl().url() != null
                                && mc.imageUrl().url().startsWith("data:video/")) {
                            needsPatch = true;
                            break;
                        }
                        // Map form (Spring AI represents content parts as LinkedHashMap internally)
                        if (part instanceof java.util.Map<?,?> map) {
                            Object type = map.get("type");
                            if ("image_url".equals(type)) {
                                Object imgUrlObj = map.get("image_url");
                                if (imgUrlObj instanceof java.util.Map<?,?> imgUrl) {
                                    Object url = imgUrl.get("url");
                                    if (url instanceof String urlStr && urlStr.startsWith("data:video/")) {
                                        needsPatch = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (needsPatch) break;
        }
        if (!needsPatch) {
            return request;
        }

        List<OpenAiApi.ChatCompletionMessage> patched = request.messages().stream().map(msg -> {
            if (msg.role() != OpenAiApi.ChatCompletionMessage.Role.USER || !(msg.rawContent() instanceof List<?> parts)) {
                return msg;
            }
            List<Object> newParts = new ArrayList<>();
            for (Object part : parts) {
                String videoDataUrl = null;

                // Case 1: MediaContent record (native Spring AI construction)
                if (part instanceof OpenAiApi.ChatCompletionMessage.MediaContent mc
                        && "image_url".equals(mc.type())
                        && mc.imageUrl() != null && mc.imageUrl().url() != null
                        && mc.imageUrl().url().startsWith("data:video/")) {
                    videoDataUrl = mc.imageUrl().url();
                }
                // Case 2: Map form (Jackson deserialization or Spring AI internal Map)
                if (videoDataUrl == null && part instanceof java.util.Map<?,?> map
                        && "image_url".equals(map.get("type"))) {
                    Object imgUrlObj = map.get("image_url");
                    if (imgUrlObj instanceof java.util.Map<?,?> imgUrl) {
                        Object url = imgUrl.get("url");
                        if (url instanceof String urlStr && urlStr.startsWith("data:video/")) {
                            videoDataUrl = urlStr;
                        }
                    }
                }

                if (videoDataUrl != null) {
                    // Rewrite to video_url format
                    newParts.add(Map.of(
                            "type", "video_url",
                            "video_url", Map.of("url", videoDataUrl)
                    ));
                } else {
                    newParts.add(part);
                }
            }
            return new OpenAiApi.ChatCompletionMessage(
                    newParts, msg.role(), msg.name(), msg.toolCallId(),
                    msg.toolCalls(), msg.refusal(), msg.audioOutput(),
                    msg.annotations(), msg.reasoningContent());
        }).toList();

        return new OpenAiApi.ChatCompletionRequest(
                patched,
                request.model(), request.store(), request.metadata(),
                request.frequencyPenalty(), request.logitBias(),
                request.logprobs(), request.topLogprobs(),
                request.maxTokens(), request.maxCompletionTokens(),
                request.n(), request.outputModalities(), request.audioParameters(),
                request.presencePenalty(), request.responseFormat(),
                request.seed(), request.serviceTier(), request.stop(),
                request.stream(), request.streamOptions(),
                request.temperature(), request.topP(),
                request.tools(), request.toolChoice(), request.parallelToolCalls(),
                request.user(), request.reasoningEffort(),
                request.webSearchOptions(), request.verbosity(),
                request.promptCacheKey(), request.safetyIdentifier(),
                request.extraBody()
        );
    }

    // ==================== Kimi built-in search ====================

    /**
     * Inject the {@code $web_search} built-in tool into a Kimi request.
     *
     * <p>Kimi's built-in search is enabled by declaring
     * {@code {"type":"builtin_function","function":{"name":"$web_search"}}} in the
     * tools array. Spring AI's {@code FunctionTool.Type} only has {@code FUNCTION},
     * so this injects the raw JSON structure via {@code extraBody} — overriding the
     * tools field with the original tools plus {@code $web_search}.
     */
    static OpenAiApi.ChatCompletionRequest injectKimiWebSearch(OpenAiApi.ChatCompletionRequest request) {
        // Build the $web_search entry as a Map
        Map<String, Object> webSearchTool = Map.of(
                "type", "builtin_function",
                "function", Map.of("name", "$web_search")
        );

        // Convert existing tools to List<Map> and append $web_search
        List<Map<String, Object>> allTools = new ArrayList<>();
        if (request.tools() != null) {
            for (OpenAiApi.FunctionTool tool : request.tools()) {
                Map<String, Object> toolMap = new LinkedHashMap<>();
                toolMap.put("type", "function");
                if (tool.getFunction() != null) {
                    Map<String, Object> funcMap = new LinkedHashMap<>();
                    funcMap.put("name", tool.getFunction().getName());
                    if (tool.getFunction().getDescription() != null) {
                        funcMap.put("description", tool.getFunction().getDescription());
                    }
                    if (tool.getFunction().getParameters() != null) {
                        funcMap.put("parameters", tool.getFunction().getParameters());
                    }
                    if (tool.getFunction().getStrict() != null) {
                        funcMap.put("strict", tool.getFunction().getStrict());
                    }
                    toolMap.put("function", funcMap);
                }
                allTools.add(toolMap);
            }
        }
        allTools.add(webSearchTool);

        // Inject tools via extraBody (overrides the tools field), and clear the
        // original tools field to avoid duplicate serialization.
        Map<String, Object> extraBody = new LinkedHashMap<>();
        if (request.extraBody() != null) {
            extraBody.putAll(request.extraBody());
        }
        extraBody.put("tools", allTools);

        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                null,  // tools — cleared, extraBody takes over
                request.toolChoice(),
                request.parallelToolCalls(),
                request.user(),
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                extraBody
        );
    }
}
