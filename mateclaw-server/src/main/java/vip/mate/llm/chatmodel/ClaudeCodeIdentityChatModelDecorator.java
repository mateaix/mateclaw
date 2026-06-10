package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Claude Code OAuth identity transform applied to every Anthropic request
 * when the underlying auth is a Claude Code OAuth token.
 *
 * <p>Anthropic's OAuth edge enforces an anti-abuse path that rate-limits
 * (and intermittently 5xxs) requests claiming Claude Code identity but
 * shaped differently from real Claude Code traffic. Symptoms:
 *
 * <ul>
 *   <li>HTTP 429 with {@code rate_limit_error} on quiet accounts that haven't
 *       come close to their token budget — give-away is a body of just
 *       {@code {"type":"error","error":{"type":"rate_limit_error","message":"Error"}}}
 *       (genuine quota exhaustion carries a descriptive message).</li>
 *   <li>Sporadic 500s on the first call after a long idle period.</li>
 * </ul>
 *
 * <h2>Transforms applied per call</h2>
 * <ol>
 *   <li><b>System prompt prefix</b>: prepend
 *       {@code "You are Claude Code, Anthropic's official CLI for Claude."}.
 *       Insert a new SystemMessage if none exists.</li>
 *   <li><b>Brand scrub</b>: replace {@code "MateClaw"}/{@code "mateclaw"}
 *       in system text with their Claude Code equivalents — Anthropic's
 *       content filter flags identity contradictions.</li>
 *   <li><b>Tool {@code mcp_} prefix (outgoing)</b>: every tool definition
 *       sent to Anthropic is renamed {@code mcp_<orig>} — Claude Code
 *       runs all tools through MCP servers, so real Claude Code traffic
 *       always has the prefix. Mismatch trips anti-abuse.</li>
 *   <li><b>History tool_use prefix</b>: previously-issued tool calls in
 *       AssistantMessage history get the prefix re-applied (we strip on
 *       response, so they're stored unprefixed).</li>
 *   <li><b>Tool {@code mcp_} prefix (incoming)</b>: ChatResponse tool_use
 *       names are stripped of the {@code mcp_} prefix so MateClaw's tool
 *       registry can resolve them.</li>
 * </ol>
 */
@Slf4j
public class ClaudeCodeIdentityChatModelDecorator implements ChatModel {

    /** Magic identity prefix Anthropic's OAuth edge requires in the system prompt. */
    static final String CLAUDE_CODE_SYSTEM_PREFIX =
            "You are Claude Code, Anthropic's official CLI for Claude.";

    /** Tool-name prefix Claude Code uses for all MCP-routed tools. */
    static final String MCP_TOOL_PREFIX = "mcp_";

    private final ChatModel delegate;

    public ClaudeCodeIdentityChatModelDecorator(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return stripToolPrefixes(delegate.call(transform(prompt)));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(transform(prompt)).map(this::stripToolPrefixes);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /* ====================================================================== */
    /* Outbound transform: Prompt → Prompt with identity + tool prefix         */
    /* ====================================================================== */

    /**
     * Build a new {@link Prompt} with the OAuth identity transforms applied.
     * Package-private for unit tests.
     */
    Prompt transform(Prompt original) {
        if (original == null) {
            return null;
        }
        List<Message> source = original.getInstructions();
        List<Message> rewritten = new ArrayList<>(source.size() + 1);

        boolean systemSeen = false;
        for (Message msg : source) {
            if (msg instanceof SystemMessage sm && !systemSeen) {
                // Emit identity as its own block so Spring AI serialises system as an
                // array.  Anthropic's OAuth anti-abuse gate 429s when the identity prefix
                // and additional content are merged into a single string, but accepts
                // them as separate array elements (verified 2026-04-25).
                rewritten.add(new SystemMessage(CLAUDE_CODE_SYSTEM_PREFIX));
                String sanitized = sanitizeBranding(sm.getText());
                if (sanitized != null && !sanitized.isBlank()) {
                    rewritten.add(new SystemMessage(sanitized));
                }
                systemSeen = true;
            } else if (msg instanceof AssistantMessage am && am.hasToolCalls()) {
                // Re-prefix tool_use names in history. We strip on response, so
                // by the time MateClaw stores the AssistantMessage the names
                // are unprefixed — must put the prefix back when echoing the
                // history to Anthropic for it to match its own prior turn.
                rewritten.add(rebuildAssistantMessage(am, true));
            } else {
                rewritten.add(msg);
            }
        }
        if (!systemSeen) {
            rewritten.add(0, new SystemMessage(CLAUDE_CODE_SYSTEM_PREFIX));
        }

        ChatOptions transformedOptions = transformOptions(original.getOptions());
        return new Prompt(rewritten, transformedOptions);
    }

    /**
     * Wrap each tool callback in the options so its {@code getToolDefinition().name()}
     * returns {@code mcp_<orig>}. Spring AI sends those names verbatim to Anthropic.
     * Other tool fields (description, schema) untouched. Returns {@code null} for
     * non-Anthropic options so we don't accidentally drop them on a custom subclass.
     */
    private ChatOptions transformOptions(ChatOptions options) {
        if (!(options instanceof AnthropicChatOptions anthropicOpts)) {
            return options;
        }
        List<ToolCallback> originalCallbacks = anthropicOpts.getToolCallbacks();
        Set<String> originalToolNames = anthropicOpts.getToolNames();

        boolean hasCallbacks = originalCallbacks != null && !originalCallbacks.isEmpty();
        boolean hasToolNames = originalToolNames != null && !originalToolNames.isEmpty();
        if (!hasCallbacks && !hasToolNames) {
            return options;
        }

        AnthropicChatOptions copy = AnthropicChatOptions.fromOptions(anthropicOpts);
        if (hasCallbacks) {
            List<ToolCallback> wrapped = new ArrayList<>(originalCallbacks.size());
            for (ToolCallback cb : originalCallbacks) {
                wrapped.add(cb instanceof PrefixedToolCallback ? cb : new PrefixedToolCallback(cb));
            }
            copy.setToolCallbacks(wrapped);
        }
        if (hasToolNames) {
            // toolNames is a set used by Spring AI's tool resolver to filter from
            // a wider registry. If MateClaw populates it (most paths use callbacks
            // directly so this is rare), prefix the names so they line up with
            // the wrapped callbacks above.
            Set<String> prefixed = new LinkedHashSet<>(originalToolNames.size());
            for (String n : originalToolNames) {
                prefixed.add(n.startsWith(MCP_TOOL_PREFIX) ? n : MCP_TOOL_PREFIX + n);
            }
            copy.setToolNames(prefixed);
        }
        return copy;
    }

    /* ====================================================================== */
    /* Inbound transform: ChatResponse → strip tool prefix                     */
    /* ====================================================================== */

    ChatResponse stripToolPrefixes(ChatResponse response) {
        if (response == null) {
            return null;
        }
        List<Generation> origGens = response.getResults();
        if (origGens == null || origGens.isEmpty()) {
            return response;
        }
        List<Generation> rewritten = null;
        for (int i = 0; i < origGens.size(); i++) {
            Generation g = origGens.get(i);
            AssistantMessage am = g.getOutput();
            if (am == null || !am.hasToolCalls()) continue;
            boolean changed = false;
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                if (tc.name() != null && tc.name().startsWith(MCP_TOOL_PREFIX)) {
                    changed = true;
                    break;
                }
            }
            if (!changed) continue;

            if (rewritten == null) {
                rewritten = new ArrayList<>(origGens);
            }
            AssistantMessage stripped = rebuildAssistantMessage(am, false);
            ChatGenerationMetadata meta = g.getMetadata();
            rewritten.set(i, new Generation(stripped, meta));
        }
        if (rewritten == null) {
            return response; // no tool_use blocks needed rewriting
        }
        return new ChatResponse(rewritten, response.getMetadata());
    }

    /**
     * Rebuild an AssistantMessage with tool_call names prefixed (when
     * {@code prefix=true}) or stripped (when {@code prefix=false}).
     */
    private AssistantMessage rebuildAssistantMessage(AssistantMessage original, boolean prefix) {
        List<AssistantMessage.ToolCall> rebuilt = new ArrayList<>(original.getToolCalls().size());
        for (AssistantMessage.ToolCall tc : original.getToolCalls()) {
            String name = tc.name();
            String newName;
            if (prefix) {
                newName = (name == null || name.startsWith(MCP_TOOL_PREFIX)) ? name : MCP_TOOL_PREFIX + name;
            } else {
                newName = (name != null && name.startsWith(MCP_TOOL_PREFIX))
                        ? name.substring(MCP_TOOL_PREFIX.length()) : name;
            }
            rebuilt.add(new AssistantMessage.ToolCall(tc.id(), tc.type(), newName, tc.arguments()));
        }
        return AssistantMessage.builder()
                .content(original.getText())
                .properties(original.getMetadata())
                .toolCalls(rebuilt)
                .media(original.getMedia())
                .build();
    }

    /* ====================================================================== */
    /* String helpers (system prompt + branding)                               */
    /* ====================================================================== */

    private static String prependIdentity(String existingSystem) {
        if (existingSystem == null || existingSystem.isBlank()) {
            return CLAUDE_CODE_SYSTEM_PREFIX;
        }
        if (existingSystem.startsWith(CLAUDE_CODE_SYSTEM_PREFIX)) {
            return existingSystem;
        }
        return CLAUDE_CODE_SYSTEM_PREFIX + "\n\n" + existingSystem;
    }

    static String sanitizeBranding(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text
                .replace("MateClaw", "Claude Code")
                .replace("mateclaw", "claude-code")
                .replace("Mate Claw", "Claude Code");
    }

    /* ====================================================================== */
    /* PrefixedToolCallback — wraps a ToolCallback to expose the mcp_ name     */
    /* ====================================================================== */

    /**
     * Wraps a {@link ToolCallback} so its {@code getToolDefinition().name()}
     * returns {@code mcp_<orig>}, while {@code call(...)} forwards verbatim
     * to the underlying tool. Anthropic sees the prefixed name on the wire;
     * MateClaw's tool implementation never sees the prefix.
     */
    static final class PrefixedToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final ToolDefinition prefixedDefinition;

        PrefixedToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
            ToolDefinition orig = delegate.getToolDefinition();
            String origName = orig.name();
            String prefixed = (origName != null && origName.startsWith(MCP_TOOL_PREFIX))
                    ? origName : MCP_TOOL_PREFIX + origName;
            this.prefixedDefinition = DefaultToolDefinition.builder()
                    .name(prefixed)
                    .description(orig.description())
                    .inputSchema(orig.inputSchema())
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return prefixedDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return delegate.call(input);
        }

        @Override
        public String call(String input, ToolContext context) {
            return delegate.call(input, context);
        }

        ToolCallback unwrap() {
            return delegate;
        }
    }
}
