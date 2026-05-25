package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC-049 PR-2 §2.4.1: verify the {@code lastUserIdx} boundary semantics of
 * {@link NodeStreamingChatHelper#stripThinkingFromPrompt}.
 *
 * <p>Prior-turn AssistantMessages ({@code i <= lastUserIdx}) must have their
 * {@code reasoningContent} stripped — DeepSeek's contract says "reset across
 * user turns". In-turn AssistantMessages ({@code i > lastUserIdx}) must keep
 * their thinking so DeepSeek's "pass back within the same turn" requirement
 * holds for ReAct multi-round tool calls.
 */
class StripThinkingBoundaryTest {

    private static AssistantMessage assistantWithThinking(String content, String thinking) {
        AssistantMessage.Builder b = AssistantMessage.builder().content(content);
        if (thinking != null) {
            b.properties(Map.of("reasoningContent", thinking));
        }
        return b.build();
    }

    private static String thinkingOf(Message m) {
        if (!(m instanceof AssistantMessage am)) return null;
        Object rc = am.getMetadata() != null ? am.getMetadata().get("reasoningContent") : null;
        return rc instanceof String s ? s : null;
    }

    @Test
    @DisplayName("No UserMessage (edge): lastUserIdx=-1 → all assistants treated as in-turn, thinking kept")
    void noUser_allKept() {
        // Edge case: when the prompt contains no UserMessage at all (e.g. system-only
        // setup or a freshly-built Prompt that hasn't received user input yet), there
        // is no prior-turn boundary, so every assistant is considered in-turn and
        // their thinking is preserved. This is the safer default — we never strip
        // without a clear cross-turn signal.
        List<Message> msgs = List.of(
                new SystemMessage("sys"),
                assistantWithThinking("a1", "think-1"),
                assistantWithThinking("a2", "think-2")
        );
        Prompt cleaned = NodeStreamingChatHelper.stripThinkingFromPrompt(new Prompt(msgs));
        assertEquals("think-1", thinkingOf(cleaned.getInstructions().get(1)));
        assertEquals("think-2", thinkingOf(cleaned.getInstructions().get(2)));
    }

    @Test
    @DisplayName("Single turn: UserMessage then assistants → all in-turn assistants keep thinking")
    void singleTurn_allInTurnKept() {
        List<Message> msgs = List.of(
                new SystemMessage("sys"),
                new UserMessage("q1"),
                assistantWithThinking("a1-tool", "think-a1"),
                assistantWithThinking("a2-final", "think-a2")
        );
        Prompt cleaned = NodeStreamingChatHelper.stripThinkingFromPrompt(new Prompt(msgs));
        assertEquals("think-a1", thinkingOf(cleaned.getInstructions().get(2)));
        assertEquals("think-a2", thinkingOf(cleaned.getInstructions().get(3)));
    }

    @Test
    @DisplayName("Case H: cross-turn stripped, in-turn preserved")
    void crossTurn_stripped_inTurn_kept() {
        // [sys, U1, A1(think1), U2, A2(think2), A3(think3)]
        // lastUserIdx = 3 (U2)
        // i=2 A1 → prior-turn → strip
        // i=4 A2 → in-turn → keep
        // i=5 A3 → in-turn → keep
        List<Message> msgs = List.of(
                new SystemMessage("sys"),
                new UserMessage("u1"),
                assistantWithThinking("a1", "think-1"),
                new UserMessage("u2"),
                assistantWithThinking("a2", "think-2"),
                assistantWithThinking("a3", "think-3")
        );
        Prompt cleaned = NodeStreamingChatHelper.stripThinkingFromPrompt(new Prompt(msgs));

        assertNull(thinkingOf(cleaned.getInstructions().get(2)),
                "A1 is prior-turn (i=2 <= lastUserIdx=3) — thinking must be stripped");
        assertEquals("think-2", thinkingOf(cleaned.getInstructions().get(4)),
                "A2 is in-turn (i=4 > lastUserIdx=3) — thinking must be kept");
        assertEquals("think-3", thinkingOf(cleaned.getInstructions().get(5)),
                "A3 is in-turn (i=5 > lastUserIdx=3) — thinking must be kept");
    }

    @Test
    @DisplayName("Options reference is preserved by the returned Prompt (producer relies on this)")
    void optionsPreservedByReference() {
        org.springframework.ai.openai.OpenAiChatOptions opts =
                org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build();
        opts.setUser("original-user");

        List<Message> msgs = List.of(
                new UserMessage("u1"),
                assistantWithThinking("a1", "think")
        );
        Prompt in = new Prompt(msgs, opts);
        Prompt cleaned = NodeStreamingChatHelper.stripThinkingFromPrompt(in);

        // The returned Prompt's options must be the same instance, so the
        // producer's subsequent setUser(relayToken) is visible through cleaned too.
        assertTrue(cleaned.getOptions() == in.getOptions(),
                "stripThinkingFromPrompt must preserve the options reference");
        assertEquals("original-user",
                ((org.springframework.ai.openai.OpenAiChatOptions) cleaned.getOptions()).getUser());
    }

    @Test
    @DisplayName("Assistant without thinking is untouched (no churn)")
    void noThinkingMetadata_passthrough() {
        List<Message> msgs = List.of(
                new UserMessage("u1"),
                new AssistantMessage("plain a")
        );
        Prompt cleaned = NodeStreamingChatHelper.stripThinkingFromPrompt(new Prompt(msgs));
        // Should return a Prompt with the same messages (no rebuild required)
        assertEquals(msgs.size(), cleaned.getInstructions().size());
        assertNull(thinkingOf(cleaned.getInstructions().get(1)));
    }

    @Test
    @DisplayName("Prior-turn assistant with non-thinking metadata: thinking stripped, other metadata preserved")
    void priorTurnAssistant_otherMetadataPreserved() {
        AssistantMessage priorAssistant = AssistantMessage.builder()
                .content("prior")
                .properties(Map.of("reasoningContent", "old-think", "custom-key", "custom-val"))
                .build();
        List<Message> msgs = List.of(
                new UserMessage("u1"),
                priorAssistant,
                new UserMessage("u2"),
                assistantWithThinking("current", "current-think")
        );
        Prompt cleaned = NodeStreamingChatHelper.stripThinkingFromPrompt(new Prompt(msgs));

        Message rebuiltPrior = cleaned.getInstructions().get(1);
        assertTrue(rebuiltPrior instanceof AssistantMessage);
        AssistantMessage am = (AssistantMessage) rebuiltPrior;
        assertNull(am.getMetadata().get("reasoningContent"), "thinking must be stripped");
        assertEquals("custom-val", am.getMetadata().get("custom-key"), "other metadata must be preserved");
    }
}
