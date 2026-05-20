package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the OAuth-mode prompt rewriting that prevents Anthropic's edge
 * from rate-limiting MateClaw traffic. Each test corresponds to one of the
 * transforms applied on OAuth-authenticated requests.
 */
class ClaudeCodeIdentityChatModelDecoratorTest {

    @Test
    @DisplayName("transform prepends Claude Code identity as its own SystemMessage before the original")
    void transform_prependsToExistingSystem() {
        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(noopDelegate());
        Prompt input = new Prompt(List.of(
                new SystemMessage("You are a helpful coding assistant."),
                new UserMessage("hi")));
        Prompt result = d.transform(input);

        // RFC-062: identity must be its OWN system block (not merged into one string)
        // — Anthropic's OAuth anti-abuse gate 429s the merged form, accepts the array form.
        SystemMessage identity = (SystemMessage) result.getInstructions().get(0);
        assertEquals(ClaudeCodeIdentityChatModelDecorator.CLAUDE_CODE_SYSTEM_PREFIX, identity.getText());
        SystemMessage body = (SystemMessage) result.getInstructions().get(1);
        assertTrue(body.getText().contains("helpful coding assistant"));
    }

    @Test
    @DisplayName("transform inserts a system message when none was present")
    void transform_insertsSystemWhenAbsent() {
        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(noopDelegate());
        Prompt input = new Prompt(List.of(new UserMessage("hello")));
        Prompt result = d.transform(input);

        // First message must be a system message with just the identity prefix:
        // when none was supplied, system = [identity block].
        Message first = result.getInstructions().get(0);
        assertTrue(first instanceof SystemMessage);
        assertEquals(ClaudeCodeIdentityChatModelDecorator.CLAUDE_CODE_SYSTEM_PREFIX,
                ((SystemMessage) first).getText());
        // User message is preserved at index 1.
        assertTrue(result.getInstructions().get(1) instanceof UserMessage);
    }

    @Test
    @DisplayName("transform is idempotent — second pass doesn't double-prefix")
    void transform_idempotent() {
        // Defends against accidental double-wrapping (e.g. nested decorators or
        // a re-issue of the same Prompt). The guard keeps the identity prefix
        // from compounding to "You are Claude Code...You are Claude Code...".
        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(noopDelegate());
        Prompt original = new Prompt(List.of(new SystemMessage("Body"), new UserMessage("hi")));
        Prompt once = d.transform(original);
        Prompt twice = d.transform(once);

        SystemMessage sys = (SystemMessage) twice.getInstructions().get(0);
        // Identity should appear exactly once.
        int firstIdx = sys.getText().indexOf(ClaudeCodeIdentityChatModelDecorator.CLAUDE_CODE_SYSTEM_PREFIX);
        int secondIdx = sys.getText().indexOf(
                ClaudeCodeIdentityChatModelDecorator.CLAUDE_CODE_SYSTEM_PREFIX, firstIdx + 1);
        assertTrue(firstIdx >= 0 && secondIdx == -1,
                "Identity prefix must appear exactly once even after multiple transform passes");
    }

    @Test
    @DisplayName("sanitizeBranding replaces MateClaw references")
    void sanitizeBranding_replacesProductNames() {
        // Anthropic's content filter flags self-contradicting identity claims —
        // a "You are Claude Code" prefix followed by a body that says "You are
        // MateClaw" trips the filter. Strip the conflicting brand.
        String sanitized = ClaudeCodeIdentityChatModelDecorator.sanitizeBranding(
                "You are MateClaw, built on mateclaw");
        assertEquals("You are Claude Code, built on claude-code", sanitized);
    }

    @Test
    @DisplayName("sanitizeBranding tolerates empty / null input")
    void sanitizeBranding_nullSafe() {
        // Defensive — a prompt with no system text shouldn't NPE here.
        assertEquals("", ClaudeCodeIdentityChatModelDecorator.sanitizeBranding(""));
        assertEquals(null, ClaudeCodeIdentityChatModelDecorator.sanitizeBranding(null));
    }

    @Test
    @DisplayName("transform preserves chat options (temperature, model, etc.)")
    void transform_preservesOptions() {
        // Spring AI's AnthropicChatOptions carry critical per-request state
        // (max_tokens, thinking budget, cache_control). Losing them on rewrite
        // would silently break Claude 4.7 thinking mode.
        var options = org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                .model("claude-opus-4-7").maxTokens(1234).build();
        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(noopDelegate());
        Prompt input = new Prompt(List.of(new UserMessage("hi")), options);
        Prompt result = d.transform(input);

        var resultOpts = (org.springframework.ai.anthropic.AnthropicChatOptions) result.getOptions();
        assertEquals("claude-opus-4-7", resultOpts.getModel());
        assertEquals(1234, resultOpts.getMaxTokens());
    }

    @Test
    @DisplayName("call delegates the rewritten prompt downstream")
    void call_delegatesRewritten() {
        // Sanity: the prompt that reaches the underlying ChatModel must be the
        // rewritten one, not the original — otherwise the decorator is dead code.
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel capturing = new TestDelegate() {
            @Override
            public ChatResponse call(Prompt prompt) {
                captured.set(prompt);
                return null;
            }
        };
        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(capturing);
        d.call(new Prompt(List.of(new UserMessage("hi"))));

        assertNotNull(captured.get());
        Message first = captured.get().getInstructions().get(0);
        assertTrue(first instanceof SystemMessage);
        assertEquals(ClaudeCodeIdentityChatModelDecorator.CLAUDE_CODE_SYSTEM_PREFIX,
                ((SystemMessage) first).getText());
    }

    @Test
    @DisplayName("transform leaves non-system messages untouched")
    void transform_preservesUserAndAssistantMessages() {
        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(noopDelegate());
        Prompt input = new Prompt(List.of(
                new SystemMessage("be helpful"),
                new UserMessage("question 1"),
                new AssistantMessage("answer 1"),
                new UserMessage("question 2")));
        Prompt result = d.transform(input);

        // RFC-062: system splits into [identity, sanitized body] so user/assistant
        // shift to indices 2, 3, 4. Their content is the original instance — a copy
        // here would force Spring AI to re-encode multimodal content (images,
        // tool_results) for no benefit.
        assertTrue(result.getInstructions().get(2) instanceof UserMessage);
        assertEquals("question 1", ((UserMessage) result.getInstructions().get(2)).getText());
        assertEquals("answer 1", ((AssistantMessage) result.getInstructions().get(3)).getText());
        assertEquals("question 2", ((UserMessage) result.getInstructions().get(4)).getText());
    }

    @Test
    @DisplayName("transform wraps tool callbacks so getToolDefinition().name() returns mcp_<orig>")
    void transform_prefixesOutgoingToolNames() {
        // Anthropic's anti-abuse path inspects tool definitions; tools without
        // the mcp_ prefix on a request claiming Claude Code identity get the
        // request rate-limited (429 with body "Error"). Ensure we wrap.
        ToolCallback search = stubToolCallback("search", "Search the web");
        ToolCallback createFile = stubToolCallback("createFile", "Create a file");
        AnthropicChatOptions opts = AnthropicChatOptions.builder()
                .toolCallbacks(List.of(search, createFile))
                .build();

        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(noopDelegate());
        Prompt result = d.transform(new Prompt(List.of(new UserMessage("hi")), opts));

        AnthropicChatOptions resOpts = (AnthropicChatOptions) result.getOptions();
        List<ToolCallback> wrapped = resOpts.getToolCallbacks();
        assertEquals(2, wrapped.size());
        assertEquals("mcp_search", wrapped.get(0).getToolDefinition().name());
        assertEquals("mcp_createFile", wrapped.get(1).getToolDefinition().name());
    }

    @Test
    @DisplayName("PrefixedToolCallback forwards call() to the underlying tool unchanged")
    void prefixedToolCallback_forwardsCall() {
        // Critical contract: prefixing happens on the wire, but MateClaw's tool
        // implementation must still receive the original argument string and
        // return the original output verbatim. If this fails, every tool
        // execution under OAuth would silently mis-route.
        AtomicReference<String> capturedInput = new AtomicReference<>();
        ToolCallback underlying = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("search").description("d").inputSchema("{}").build();
            }
            @Override
            public String call(String input) {
                capturedInput.set(input);
                return "search-output";
            }
        };
        var wrapped = new ClaudeCodeIdentityChatModelDecorator.PrefixedToolCallback(underlying);
        String out = wrapped.call("{\"q\":\"test\"}");
        assertEquals("search-output", out);
        assertEquals("{\"q\":\"test\"}", capturedInput.get());
        assertEquals("mcp_search", wrapped.getToolDefinition().name());
    }

    @Test
    @DisplayName("PrefixedToolCallback is idempotent — double-wrap doesn't double-prefix")
    void prefixedToolCallback_idempotent() {
        // Defends against accidental nested decoration. A wrapped wrapper
        // should still expose mcp_search, not mcp_mcp_search.
        ToolCallback underlying = stubToolCallback("search", "d");
        var once = new ClaudeCodeIdentityChatModelDecorator.PrefixedToolCallback(underlying);
        var twice = new ClaudeCodeIdentityChatModelDecorator.PrefixedToolCallback(once);
        assertEquals("mcp_search", once.getToolDefinition().name());
        assertEquals("mcp_search", twice.getToolDefinition().name());
    }

    @Test
    @DisplayName("stripToolPrefixes removes mcp_ from response tool_use names")
    void stripToolPrefixes_responseSide() {
        // Claude returns tool_use with name="mcp_search" (because we prefixed
        // the definition); MateClaw's tool registry only knows "search" so
        // the prefix must come off before the response leaves the decorator.
        AssistantMessage am = AssistantMessage.builder()
                .content("calling search")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call_1", "function", "mcp_search",
                                "{\"q\":\"foo\"}"),
                        new AssistantMessage.ToolCall("call_2", "function", "mcp_createFile",
                                "{\"path\":\"x\"}")))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(am)));

        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(noopDelegate());
        ChatResponse stripped = d.stripToolPrefixes(response);

        AssistantMessage out = stripped.getResult().getOutput();
        assertEquals("search", out.getToolCalls().get(0).name());
        assertEquals("createFile", out.getToolCalls().get(1).name());
        // ID + arguments must pass through untouched — losing the call ID
        // would break Anthropic's tool_result correlation on next turn.
        assertEquals("call_1", out.getToolCalls().get(0).id());
        assertEquals("{\"q\":\"foo\"}", out.getToolCalls().get(0).arguments());
    }

    @Test
    @DisplayName("stripToolPrefixes returns input unchanged when no tool_use blocks present")
    void stripToolPrefixes_noToolCalls_passthrough() {
        // Optimization: don't allocate a new list/Generation when there's
        // nothing to rewrite. Verify identity-equality for the trivial case.
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("just text"))));
        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(noopDelegate());
        ChatResponse out = d.stripToolPrefixes(response);
        assertTrue(out == response, "no-op rewrite should return the same instance");
    }

    @Test
    @DisplayName("transform re-prefixes tool_use names in AssistantMessage history")
    void transform_reprefixesHistoryToolUse() {
        // Prior turn: Claude called mcp_search → we stripped to "search" before
        // storing → next request must re-prepend mcp_ so Anthropic's history
        // matches its own prior tool_use block. Otherwise Anthropic's
        // tool_use_id correlation breaks and you get "tool_use without
        // matching tool_result" 400s.
        AssistantMessage history = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call_1", "function", "search",
                                "{\"q\":\"foo\"}")))
                .build();

        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(noopDelegate());
        Prompt result = d.transform(new Prompt(List.of(
                new SystemMessage("be helpful"),
                history,
                new UserMessage("now do that"))));

        // RFC-062: system splits into [identity, sanitized body] so AssistantMessage
        // history shifts to index 2.
        AssistantMessage rewrittenHistory = (AssistantMessage) result.getInstructions().get(2);
        assertEquals("mcp_search", rewrittenHistory.getToolCalls().get(0).name());
        // ID stays the same so tool_result correlation chains through.
        assertEquals("call_1", rewrittenHistory.getToolCalls().get(0).id());
    }

    @Test
    @DisplayName("call delegates rewritten prompt and strips response prefixes end-to-end")
    void call_endToEnd() {
        // Integration: outgoing prompt should have prefixed tool names, and
        // the AssistantMessage we return should come back unprefixed. Mirrors
        // what ReasoningNode would observe per turn.
        ToolCallback tool = stubToolCallback("search", "search");
        AnthropicChatOptions opts = AnthropicChatOptions.builder()
                .toolCallbacks(List.of(tool)).build();

        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel delegate = new TestDelegate() {
            @Override
            public ChatResponse call(Prompt prompt) {
                capturedPrompt.set(prompt);
                AssistantMessage am = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_x", "function", "mcp_search", "{}")))
                        .build();
                return new ChatResponse(List.of(new Generation(am)));
            }
        };
        ClaudeCodeIdentityChatModelDecorator d = new ClaudeCodeIdentityChatModelDecorator(delegate);
        ChatResponse out = d.call(new Prompt(List.of(new UserMessage("hi")), opts));

        AnthropicChatOptions sentOpts = (AnthropicChatOptions) capturedPrompt.get().getOptions();
        assertEquals("mcp_search", sentOpts.getToolCallbacks().get(0).getToolDefinition().name(),
                "outgoing tool name must be prefixed");
        assertEquals("search", out.getResult().getOutput().getToolCalls().get(0).name(),
                "incoming tool name must be stripped");
        // Sanity — name must round-trip differently from the wire format.
        assertNotEquals("mcp_search", out.getResult().getOutput().getToolCalls().get(0).name());
    }

    /* ----- Test helpers ----- */

    private static ChatModel noopDelegate() {
        return new TestDelegate();
    }

    private static ToolCallback stubToolCallback(String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(name).description(description).inputSchema("{}").build();
            }
            @Override
            public String call(String input) { return "ok"; }
        };
    }

    /** Minimal ChatModel that returns null/empty — sufficient for transform-only tests. */
    private static class TestDelegate implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) { return null; }
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
    }
}
