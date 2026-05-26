package vip.mate.agent.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link MessageNormalizer}.
 *
 * <p>Pins the invariants that make the normalizer safe to apply unconditionally
 * before every LLM call:
 * <ul>
 *   <li>All SystemMessages end up merged into a single SystemMessage at index 0.</li>
 *   <li>Non-system messages keep their relative order — so {@code AssistantMessage(tool_calls)}
 *       and its matching {@code ToolResponseMessage} are never reordered.</li>
 *   <li>Blank / whitespace-only SystemMessages are dropped from the merge.</li>
 *   <li>Already-canonical inputs (zero systems, or one non-blank system at index 0) are
 *       returned by reference — no allocation overhead on the happy path.</li>
 *   <li>The kill switch (JVM property {@value MessageNormalizer#ENABLED_PROPERTY}) makes
 *       {@link MessageNormalizer#normalize} a no-op.</li>
 * </ul>
 */
class MessageNormalizerTest {

    @BeforeEach
    void enableNormalizer() {
        MessageNormalizer.setEnabledForTesting(true);
    }

    @AfterEach
    void resetNormalizer() {
        MessageNormalizer.setEnabledForTesting(true);
    }

    // ---------- Fast paths ----------

    @Test
    @DisplayName("null and empty inputs pass through unchanged")
    void nullAndEmpty_passThrough() {
        assertThat(MessageNormalizer.normalize((Prompt) null)).isNull();
        assertThat(MessageNormalizer.normalize((List<Message>) null)).isNull();
        List<Message> empty = List.of();
        assertThat(MessageNormalizer.normalize(empty)).isSameAs(empty);
    }

    @Test
    @DisplayName("no SystemMessage at all → input list returned by reference")
    void noSystem_passThrough() {
        List<Message> in = List.of(
                new UserMessage("hello"),
                AssistantMessage.builder().content("hi").build(),
                new UserMessage("follow-up")
        );
        assertThat(MessageNormalizer.normalize(in)).isSameAs(in);
    }

    @Test
    @DisplayName("single non-blank SystemMessage at index 0 → input returned by reference")
    void canonicalShape_passThrough() {
        List<Message> in = List.of(
                new SystemMessage("you are a helpful assistant"),
                new UserMessage("hi")
        );
        assertThat(MessageNormalizer.normalize(in)).isSameAs(in);
    }

    // ---------- The bug case: SystemMessage after UserMessage ----------

    @Test
    @DisplayName("SystemMessage at tail (ReasoningNode ledger-snapshot pattern) → merged to head")
    void systemAtTail_movedToHead() {
        // Mirrors the exact shape ReasoningNode produces today:
        //   [system(main), system(skillCatalog), user(runtime), user(wiki),
        //    system(ledger snapshot), system(stale reminder),
        //    ...history user/assistant messages...]
        List<Message> in = new ArrayList<>(List.of(
                new SystemMessage("MAIN_PROMPT"),
                new SystemMessage("SKILL_CATALOG"),
                new UserMessage("RUNTIME_CTX"),
                new UserMessage("WIKI_SNIPPET"),
                new SystemMessage("LEDGER_SNAPSHOT"),
                new SystemMessage("STALE_REMINDER"),
                new UserMessage("user question"),
                AssistantMessage.builder().content("answer").build()
        ));

        List<Message> out = MessageNormalizer.normalize(in);

        // Exactly one SystemMessage, at index 0, containing all four segments
        // joined by the canonical separator and in original encounter order.
        assertThat(out.stream().filter(m -> m instanceof SystemMessage)).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(out.get(0).getText()).isEqualTo(
                "MAIN_PROMPT" + MessageNormalizer.SEPARATOR
                        + "SKILL_CATALOG" + MessageNormalizer.SEPARATOR
                        + "LEDGER_SNAPSHOT" + MessageNormalizer.SEPARATOR
                        + "STALE_REMINDER");

        // Non-system messages preserve their original relative order.
        assertThat(out.subList(1, out.size()))
                .extracting(Message::getText)
                .containsExactly("RUNTIME_CTX", "WIKI_SNIPPET", "user question", "answer");
    }

    // ---------- Blanks ----------

    @Test
    @DisplayName("blank SystemMessages are skipped during merge")
    void blankSystemsDropped() {
        List<Message> in = List.of(
                new SystemMessage("MAIN"),
                new SystemMessage("   "),
                new SystemMessage(""),
                new UserMessage("hi"),
                new SystemMessage("\n\t  \n")
        );
        List<Message> out = MessageNormalizer.normalize(in);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(out.get(0).getText()).isEqualTo("MAIN");
        assertThat(out.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    @DisplayName("all SystemMessages blank → SystemMessage dropped entirely")
    void allBlankSystems_allDropped() {
        List<Message> in = List.of(
                new SystemMessage(""),
                new SystemMessage("   "),
                new UserMessage("hi")
        );
        List<Message> out = MessageNormalizer.normalize(in);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    @DisplayName("single blank SystemMessage at index 0 → dropped (not preserved by fast path)")
    void singleBlankAtHead_dropped() {
        // Fast-path guard: a single SystemMessage at [0] is canonical only when
        // it has text. A blank one at [0] should still be dropped so we don't
        // send providers an empty system slot.
        List<Message> in = List.of(
                new SystemMessage("  "),
                new UserMessage("hi")
        );
        List<Message> out = MessageNormalizer.normalize(in);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(UserMessage.class);
    }

    // ---------- Tool-call pairing preservation ----------

    @Test
    @DisplayName("tool_call ↔ tool_response pairing survives normalization")
    void toolCallPairingPreserved() {
        // Build a realistic ReAct history fragment with a system mid-stream
        // (the bug case) and a tool-call/tool-response pair that must stay
        // adjacent and in-order.
        AssistantMessage assistantWithToolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-abc", "function", "read_file", "{\"path\":\"x\"}")))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-abc", "read_file", "file body")))
                .build();

        List<Message> in = List.of(
                new SystemMessage("MAIN"),
                new UserMessage("first turn"),
                new SystemMessage("LATE_SYSTEM"),
                assistantWithToolCall,
                toolResponse,
                new UserMessage("second turn")
        );

        List<Message> out = MessageNormalizer.normalize(in);

        // System at [0] only; everything else in original order.
        assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(out.get(0).getText()).isEqualTo("MAIN" + MessageNormalizer.SEPARATOR + "LATE_SYSTEM");
        assertThat(out.get(1)).isInstanceOf(UserMessage.class);
        assertThat(out.get(2)).isSameAs(assistantWithToolCall);
        assertThat(out.get(3)).isSameAs(toolResponse);
        assertThat(out.get(4)).isInstanceOf(UserMessage.class);

        // The AssistantMessage(tool_calls) → ToolResponseMessage adjacency is
        // critical: providers that strictly validate pairing (kimi-code, some
        // OpenAI-compat layers) reject a 400 if these are reordered or split
        // by another message.
        int assistantIdx = out.indexOf(assistantWithToolCall);
        int responseIdx = out.indexOf(toolResponse);
        assertThat(responseIdx).isEqualTo(assistantIdx + 1);
    }

    // ---------- Prompt overload ----------

    @Test
    @DisplayName("Prompt overload preserves options by reference")
    void promptOverloadPreservesOptions() {
        Prompt in = new Prompt(List.of(
                new SystemMessage("a"),
                new UserMessage("u"),
                new SystemMessage("b")
        ));
        Prompt out = MessageNormalizer.normalize(in);

        // Different Prompt instance (because messages changed)…
        assertThat(out).isNotSameAs(in);
        // …but the options reference is preserved verbatim, which matters
        // because doStreamCall mutates options.user via AssistantThinkingRelay
        // and we cannot break that chain.
        assertThat(out.getOptions()).isSameAs(in.getOptions());

        assertThat(out.getInstructions()).hasSize(2);
        assertThat(out.getInstructions().get(0).getText())
                .isEqualTo("a" + MessageNormalizer.SEPARATOR + "b");
    }

    @Test
    @DisplayName("Prompt overload returns same reference on canonical input")
    void promptOverloadFastPath() {
        Prompt in = new Prompt(List.of(
                new SystemMessage("only one"),
                new UserMessage("u")
        ));
        assertThat(MessageNormalizer.normalize(in)).isSameAs(in);
    }

    // ---------- Kill switch ----------

    @Test
    @DisplayName("kill switch off → normalize is a no-op (input returned by reference)")
    void killSwitchOff_isNoOp() {
        MessageNormalizer.setEnabledForTesting(false);
        try {
            List<Message> in = List.of(
                    new SystemMessage("MAIN"),
                    new UserMessage("u"),
                    new SystemMessage("LATE_SYSTEM") // would normally be merged
            );
            assertThat(MessageNormalizer.normalize(in)).isSameAs(in);

            Prompt p = new Prompt(in);
            assertThat(MessageNormalizer.normalize(p)).isSameAs(p);
        } finally {
            MessageNormalizer.setEnabledForTesting(true);
        }
    }
}
