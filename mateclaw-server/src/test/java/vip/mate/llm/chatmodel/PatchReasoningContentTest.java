package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.Role;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Consumer-side tests for
 * {@link OpenAiRequestRewriter#patchReasoningContent(ChatCompletionRequest, ModelProviderEntity)}.
 *
 * <p>Covers four orthogonal dimensions:
 * <ul>
 *   <li>FallbackPolicy — DEEPSEEK (null + warn + patchNonToolCall) vs KIMI / OPENAI /
 *       DEFAULT (" " + no-warn + tool-call-only)</li>
 *   <li>{@code lastUserIdx} scope — assistants at {@code i <= lastUserIdx} never patched;
 *       iterator still advances for alignment</li>
 *   <li>sanitizedUser — restored from {@code RelayEntry.originalUser}; relay token
 *       never egresses</li>
 *   <li>relay presence — iterator consumed in order; missing relay triggers policy
 *       fallback only for in-turn messages</li>
 * </ul>
 */
class PatchReasoningContentTest {

    // ---------- Fixtures ----------

    private static ModelProviderEntity provider(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        return p;
    }

    private static ChatCompletionMessage user(String text) {
        return new ChatCompletionMessage(text, Role.USER);
    }

    private static ChatCompletionMessage system(String text) {
        return new ChatCompletionMessage(text, Role.SYSTEM);
    }

    /** Plain assistant message — no tool calls, no reasoning_content. */
    private static ChatCompletionMessage assistantPlain(String text) {
        return new ChatCompletionMessage(text, Role.ASSISTANT, null, null, null, null, null, null, null);
    }

    /** Assistant tool_call message with optional pre-existing reasoning_content. */
    private static ChatCompletionMessage assistantToolCall(String text, String reasoningContent) {
        ToolCall tc = new ToolCall("call_1", "function", null);
        return new ChatCompletionMessage(text, Role.ASSISTANT, null, null, List.of(tc), null, null, null, reasoningContent);
    }

    /** Build a ChatCompletionRequest with the given messages + user field; all other fields null. */
    private static ChatCompletionRequest request(List<ChatCompletionMessage> messages, String user) {
        return new ChatCompletionRequest(
                messages,           // messages
                "test-model",       // model
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null,         // toolChoice, parallelToolCalls
                user,               // user
                null,               // reasoningEffort
                null, null, null, null, null
        );
    }

    @BeforeEach
    void clearRelay() {
        AssistantThinkingRelay.clearAll();
    }

    @AfterEach
    void clearRelayAfter() {
        AssistantThinkingRelay.clearAll();
    }

    // ---------- No-relay, no-thinking-mode path ----------

    @Test
    @DisplayName("No relay token + no thinking signals → request passes through unchanged")
    void noop_whenNoRelayAndNoThinkingMode() {
        ChatCompletionRequest req = request(List.of(
                system("sys"),
                user("q1"),
                assistantToolCall("", null)     // no reasoning_content anywhere → not thinking mode
        ), "caller-user-1");

        // model is "test-model" which maps to STANDARD family → requiresReasoningContentPatch returns false
        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek"));
        assertSame(req, out, "no thinking signal → no rebuild");
        assertEquals("caller-user-1", out.user(), "user field untouched");
    }

    @Test
    @DisplayName("Leaked relay token (no entry in map) + no thinking signals → strips token, rebuilds user")
    void stripsLeakedToken_whenNoEntryNoThinking() {
        // Prefix-shaped but not actually stashed — simulates consumer running after
        // producer's finally already discarded. take() returns null; isToken() still true.
        String fakeToken = AssistantThinkingRelay.TOKEN_PREFIX + "orphan-uuid";
        ChatCompletionRequest req = request(List.of(
                user("q1"),
                assistantPlain("hi")
        ), fakeToken);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("openai"));
        assertNotSame(req, out, "rebuild expected to strip leaked token");
        assertNull(out.user(), "leaked token must be sanitized to null");
    }

    // ---------- sanitizedUser restoration from RelayEntry ----------

    @Test
    @DisplayName("sanitizedUser is restored from RelayEntry.originalUser")
    void sanitizedUser_restoredFromRelayEntry() {
        List<String> thinkings = List.of("", "in-turn-think");
        String token = AssistantThinkingRelay.stash(thinkings, "original-caller-42");

        ChatCompletionRequest req = request(List.of(
                assistantPlain("prior-assistant"),  // i=0, position 0 in thinkings → ""
                user("q1"),
                assistantToolCall("a1", null)        // i=2, position 1 in thinkings → "in-turn-think"
        ), token);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek"));

        assertEquals("original-caller-42", out.user(), "sanitizedUser must equal entry.originalUser()");
        assertEquals("in-turn-think", out.messages().get(2).reasoningContent(),
                "in-turn assistant (i=2 > lastUserIdx=1) should receive the real thinking");
    }

    // ---------- lastUserIdx scope ----------

    @Test
    @DisplayName("DEEPSEEK patchCrossTurn=true: prior-turn assistants get ' ' fallback so multi-turn doesn't 400")
    void crossTurnAssistants_patchedWithSpace_deepseek() {
        // [sys, U1, A1(tool_call, no-rc), U2, A2(tool_call, no-rc)]
        // lastUserIdx = 3 (U2)
        // Relay thinkings: [null for A1, "real-a2" for A2]
        //
        // DeepSeek (since 2026-04) requires reasoning_content on EVERY assistant
        // in the request — prior-turn included. Without patchCrossTurn, A1 stays
        // null and DeepSeek 400s on every multi-turn conversation. With it, A1
        // gets the same " " fallback in-turn assistants get.
        List<String> thinkings = Arrays.asList("", "real-a2");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ChatCompletionRequest req = request(List.of(
                system("sys"),
                user("q1"),
                assistantToolCall("a1", null),       // i=2, cross-turn (2 <= 3)
                user("q2"),
                assistantToolCall("a2", null)        // i=4, in-turn (4 > 3)
        ), token);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek"));

        assertEquals(" ", out.messages().get(2).reasoningContent(),
                "cross-turn A1 gets ' ' fallback so DeepSeek thinking-mode validation passes");
        assertEquals("real-a2", out.messages().get(4).reasoningContent(),
                "in-turn A2 (i=4 > lastUserIdx=3) receives the real relay value");
    }

    @Test
    @DisplayName("Iterator stays aligned: cross-turn consumes '' positions so in-turn gets correct thinking")
    void iteratorAlignment_acrossCrossTurnAndInTurn() {
        // [U1, A1(no-rc), A2(no-rc), U2, A3(no-rc), A4(no-rc)]
        // lastUserIdx = 3 (U2). Producer extraction order = A1,A2,A3,A4.
        // Relay: ["","" (cross-turn, stripped already), "real-a3", "real-a4"]
        List<String> thinkings = List.of("", "", "real-a3", "real-a4");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ChatCompletionRequest req = request(List.of(
                user("q1"),
                assistantToolCall("a1", null),   // i=1 cross-turn
                assistantToolCall("a2", null),   // i=2 cross-turn
                user("q2"),
                assistantToolCall("a3", null),   // i=4 in-turn
                assistantToolCall("a4", null)    // i=5 in-turn
        ), token);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek"));

        // DEEPSEEK patchCrossTurn=true: cross-turn now also gets ' ' fallback.
        // Iterator alignment is preserved: A1/A2 consume the empty entries '',
        // A3/A4 consume their real values in correct positions.
        assertEquals(" ", out.messages().get(1).reasoningContent(), "A1 cross-turn ' ' fallback");
        assertEquals(" ", out.messages().get(2).reasoningContent(), "A2 cross-turn ' ' fallback");
        assertEquals("real-a3", out.messages().get(4).reasoningContent(), "A3 in-turn gets real-a3 (not real-a4)");
        assertEquals("real-a4", out.messages().get(5).reasoningContent(), "A4 in-turn gets real-a4");
    }

    // ---------- FallbackPolicy × emptyFallback ----------

    @Test
    @DisplayName("DEEPSEEK policy: relay empty for in-turn tool_call → reasoning_content gets ' ' fallback")
    void deepseek_relayEmpty_fallsBackToSpace() {
        // 72bd33dc switched DEEPSEEK from emptyFallback=null (force explicit 400)
        // to " " — null kept self-replicating 400s every multi-tool turn that
        // crossed a summarizing boundary. Aligning with KIMI/OPENAI tolerance.
        List<String> thinkings = List.of("");  // one assistant, no real thinking
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ChatCompletionRequest req = request(List.of(
                user("q1"),
                assistantToolCall("a1", null)  // in-turn
        ), token);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek"));

        assertEquals(" ", out.messages().get(1).reasoningContent(),
                "DeepSeek: ' ' fallback restores forward progress when relay has no real value");
    }

    @Test
    @DisplayName("KIMI policy: relay empty for in-turn tool_call → ' ' injected (legacy tolerance)")
    void kimi_relayEmpty_injectsSpace() {
        // Kimi path is triggered by the model-family check; use model name that maps to KIMI_THINKING.
        List<String> thinkings = List.of("");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        List<ChatCompletionMessage> msgs = List.of(
                user("q1"),
                assistantToolCall("a1", null)
        );
        ChatCompletionRequest req = new ChatCompletionRequest(
                msgs, "kimi-k2.5", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, token,
                null, null, null, null, null, null
        );

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("kimi-cn"));

        assertEquals(" ", out.messages().get(1).reasoningContent(),
                "Kimi tolerates ' ' — preserve legacy behavior");
    }

    @Test
    @DisplayName("Unknown provider uses DEFAULT policy: ' ' injected (legacy tolerance, not noop)")
    void defaultPolicy_unknownProvider_injectsSpace() {
        List<String> thinkings = List.of("");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        // Use a model that triggers requiresReasoningContentPatch so thinking mode is active
        List<ChatCompletionMessage> msgs = List.of(
                user("q1"),
                assistantToolCall("a1", null)
        );
        ChatCompletionRequest req = new ChatCompletionRequest(
                msgs, "deepseek-reasoner", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, token,
                null, null, null, null, null, null
        );

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("custom-gateway"));

        assertEquals(" ", out.messages().get(1).reasoningContent(),
                "DEFAULT keeps legacy ' ' for unrecognized providers — avoid regressing self-hosted backends");
    }

    // ---------- FallbackPolicy × patchNonToolCall ----------

    @Test
    @DisplayName("DEEPSEEK policy patches non-tool_call in-turn assistants too (patchNonToolCall=true)")
    void deepseek_patchesNonToolCallAssistant() {
        List<String> thinkings = List.of("thinking-for-plain");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ChatCompletionRequest req = request(List.of(
                user("q1"),
                assistantPlain("plain answer")  // no tool_calls
        ), token);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek"));

        assertEquals("thinking-for-plain", out.messages().get(1).reasoningContent(),
                "DeepSeek contract requires reasoning_content even on non-tool_call assistants when in thinking mode");
    }

    @Test
    @DisplayName("KIMI policy leaves non-tool_call assistants alone (patchNonToolCall=false)")
    void kimi_skipsNonToolCallAssistant() {
        List<String> thinkings = List.of("would-not-be-used");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        List<ChatCompletionMessage> msgs = List.of(
                user("q1"),
                assistantPlain("plain answer")
        );
        ChatCompletionRequest req = new ChatCompletionRequest(
                msgs, "kimi-k2.5", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, token,
                null, null, null, null, null, null
        );

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("kimi-cn"));

        assertNull(out.messages().get(1).reasoningContent(),
                "Kimi only patches tool_call assistants; plain assistants are untouched");
    }

    // ---------- Preserve pre-existing real values ----------

    @Test
    @DisplayName("Assistant that already has real reasoning_content is left alone")
    void existingRealValue_preserved() {
        List<String> thinkings = List.of("would-overwrite");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ChatCompletionRequest req = request(List.of(
                user("q1"),
                assistantToolCall("a1", "pre-existing-real-thinking")  // already has a value
        ), token);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek"));

        assertEquals("pre-existing-real-thinking", out.messages().get(1).reasoningContent(),
                "non-blank existing reasoning_content must not be overwritten by relay");
    }

    // ---------- Edge: empty messages ----------

    @Test
    @DisplayName("Empty messages list: no-op, returns same instance")
    void emptyMessages_noop() {
        ChatCompletionRequest req = request(List.of(), null);
        assertSame(req, OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek")));
    }

    @Test
    @DisplayName("Null messages: no-op, returns same instance")
    void nullMessages_noop() {
        ChatCompletionRequest req = new ChatCompletionRequest(
                null, "m", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
        );
        assertSame(req, OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek")));
    }

    // ---------- Fewer relay entries than assistants: defensive policy fallback ----------

    @Test
    @DisplayName("Relay shorter than assistant count: extra in-turn assistants fall back to policy")
    void relayShorterThanAssistants_fallsBack() {
        // Producer extracted 1 entry but there are 2 in-turn tool_call assistants
        // (e.g. one was added after relay stash — shouldn't happen but be defensive).
        List<String> thinkings = List.of("real-1");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ChatCompletionRequest req = request(new ArrayList<>(List.of(
                user("q1"),
                assistantToolCall("a1", null),
                assistantToolCall("a2", null)
        )), token);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek"));

        assertEquals("real-1", out.messages().get(1).reasoningContent());
        assertEquals(" ", out.messages().get(2).reasoningContent(),
                "DEEPSEEK with emptyFallback=' ' (post-72bd33dc): missing real values get the same tolerant fallback");
    }

    // ---------- patchCrossTurn policy (2026-04-29) ----------

    @Test
    @DisplayName("KIMI / OPENAI / DEFAULT do NOT patch cross-turn — only DEEPSEEK does")
    void crossTurnPatching_isDeepseekOnly() {
        // Same shape as crossTurnAssistants_patchedWithSpace_deepseek but with
        // KIMI provider — KIMI's contract resets thinking across user turns,
        // so prior-turn assistants must remain null. Pinning this here protects
        // against accidentally flipping patchCrossTurn=true for all providers.
        List<String> thinkings = Arrays.asList("", "real-a2");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ChatCompletionRequest req = request(List.of(
                user("q1"),
                assistantToolCall("a1", null),       // i=1, cross-turn (1 <= 2)
                user("q2"),
                assistantToolCall("a2", null)        // i=3, in-turn (3 > 2)
        ), token);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("kimi-cn"));

        assertNull(out.messages().get(1).reasoningContent(),
                "KIMI does not patch cross-turn — thinking resets across user turns");
        assertEquals("real-a2", out.messages().get(3).reasoningContent(),
                "KIMI in-turn assistants still receive their relay value");
    }

    @Test
    @DisplayName("DEEPSEEK cross-turn assistant without tool_calls also patched (patchNonToolCall=true)")
    void crossTurnPlainAssistant_patchedForDeepseek() {
        // Plain prior-turn text assistant (no tool_calls): without the
        // patchNonToolCall guard, this would still be skipped. DEEPSEEK has
        // both patchNonToolCall=true AND patchCrossTurn=true, so it should
        // get the ' ' fallback. This is the most common production case
        // since plain assistants dominate IM channel history.
        List<String> thinkings = Arrays.asList("", "");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ChatCompletionRequest req = request(List.of(
                user("q1"),
                new ChatCompletionMessage("plain a1", Role.ASSISTANT),  // no tool_calls
                user("q2"),
                new ChatCompletionMessage("plain a2", Role.ASSISTANT)
        ), token);

        ChatCompletionRequest out = OpenAiRequestRewriter.patchReasoningContent(req, provider("deepseek"));

        assertEquals(" ", out.messages().get(1).reasoningContent(),
                "DEEPSEEK plain prior-turn assistant gets ' ' so request validates");
        assertEquals(" ", out.messages().get(3).reasoningContent(),
                "DEEPSEEK plain in-turn assistant gets ' ' as before");
    }
}
