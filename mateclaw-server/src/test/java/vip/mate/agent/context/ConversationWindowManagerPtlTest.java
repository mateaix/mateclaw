package vip.mate.agent.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.workspace.conversation.ConversationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract for the structured PTL (Prompt Too Long) recovery path.
 * <p>
 * Verifies:
 * <ol>
 *   <li>A PTL hit runs the full {@link ConversationWindowManager#compactMessages}
 *       pipeline (anchor + summary + tail) under a forced-tight budget, not
 *       the legacy tail-only drop.</li>
 *   <li>Tool-call clusters are kept intact across the cut so the retry
 *       prompt doesn't 400 the provider a second time.</li>
 *   <li>The persisted boundary row carries
 *       {@code metadata.trigger = "prompt_too_long"} so the summary is
 *       retrievable distinctly from a normal token-threshold compaction.</li>
 *   <li>A second PTL within the 60s cooldown falls back to tail-only and
 *       does NOT invoke the summary LLM (avoids compaction storms).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationWindowManagerPtlTest {

    @Mock private ChatModel chatModel;
    @Mock private ConversationService conversationService;

    private ConversationWindowProperties properties;
    private ConversationWindowManager manager;

    @BeforeEach
    void setUp() {
        properties = new ConversationWindowProperties();
        properties.setFirstUserAnchorEnabled(true);
        properties.setFirstUserAnchorMaxTokens(400);
        // memoryManager is null — onPreCompress hook is a no-op.
        manager = new ConversationWindowManager(properties, null, conversationService);

        // ChatModel always returns a short, deterministic summary so the
        // pipeline can persist a boundary row and we can assert on its
        // metadata.
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv ->
                makeChatResponse("STRUCTURED_SUMMARY_FROM_LLM"));
        when(conversationService.saveCompressionSummaryReturningId(
                anyString(), anyString(), anyInt(), any())).thenReturn(42L);
    }

    @Test
    @DisplayName("PTL structured pass: returns summary + anchor + tail, no broken tool-call pair, trigger=prompt_too_long")
    void structuredCompactionLandsAnchorSummaryAndTail() {
        List<Message> history = buildHistoryWithToolPairs();
        int sizeBefore = history.size();

        List<Message> compacted = manager.compactForRetry(history, chatModel, "conv-ptl-1", 1L);

        // ---- shape: not null, smaller than input ----
        assertThat(compacted).isNotNull();
        assertThat(compacted.size()).isLessThan(sizeBefore);

        // ---- contains the structured summary marker + anchor ----
        boolean hasStructuredSummary = compacted.stream()
                .filter(m -> m instanceof UserMessage)
                .map(Message::getText)
                .anyMatch(t -> t != null
                        && t.startsWith(ConversationWindowManager.SUMMARY_PREFIX)
                        && t.contains("STRUCTURED_SUMMARY_FROM_LLM"));
        assertThat(hasStructuredSummary)
                .as("compacted history must include the LLM summary wrapped with SUMMARY_PREFIX")
                .isTrue();
        boolean hasAnchor = compacted.stream()
                .filter(m -> m instanceof UserMessage)
                .map(Message::getText)
                .anyMatch(t -> t != null && t.startsWith(ConversationWindowManager.ANCHOR_PREFIX));
        assertThat(hasAnchor)
                .as("anchor of the original user goal must be present")
                .isTrue();

        // ---- pair safety: every AssistantMessage with tool_calls keeps its
        //                   matching ToolResponseMessages adjacent ----
        assertNoBrokenToolPair(compacted);

        // ---- the boundary persistence path tags trigger = "prompt_too_long" ----
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(conversationService).saveCompressionSummaryReturningId(
                org.mockito.ArgumentMatchers.eq("conv-ptl-1"),
                anyString(), anyInt(), metaCaptor.capture());
        assertThat(metaCaptor.getValue())
                .containsEntry("trigger", "prompt_too_long")
                .containsKey("preTokens")
                .containsKey("postTokens");
    }

    @Test
    @DisplayName("PTL cooldown: second call within 60s falls back to tail-only, no extra ChatModel.call")
    void secondPtlWithinCooldownFallsBackToTailOnly() {
        List<Message> history = buildHistoryWithToolPairs();

        // First call exercises the structured path → ChatModel.call invoked
        // for summary generation.
        manager.compactForRetry(history, chatModel, "conv-cooldown", 1L);
        verify(chatModel, times(1)).call(any(Prompt.class));

        // Second call within cooldown — tail-only fallback, no further LLM call.
        List<Message> second = manager.compactForRetry(history, chatModel, "conv-cooldown", 1L);
        assertThat(second).isNotNull();
        // Tail-only path drops summary + anchor — no SUMMARY_PREFIX in the
        // second result (this is what differentiates it from the structured
        // path even when both happen to return ≤ 4 messages).
        assertThat(second.stream().anyMatch(m -> {
            String t = m.getText();
            return t != null && t.startsWith(ConversationWindowManager.SUMMARY_PREFIX);
        })).isFalse();
        // Critical: the summary LLM was NOT called a second time.
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("Tiny history (≤ 2 messages) returns null without touching ChatModel")
    void tinyHistoryReturnsNull() {
        List<Message> tiny = List.of(new UserMessage("hi"), new AssistantMessage("hello"));
        List<Message> result = manager.compactForRetry(tiny, chatModel, "conv-tiny", 1L);
        assertThat(result).isNull();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    // ---------- helpers ----------

    /**
     * Build a 50-message history: alternating user/assistant turns plus five
     * intact assistant.tool_calls → ToolResponseMessage clusters scattered
     * through it. Each filler message carries ~300 chars so the total token
     * count is comfortably large enough to push the structured budget into
     * the "needs LLM summary" range.
     */
    private static List<Message> buildHistoryWithToolPairs() {
        List<Message> msgs = new ArrayList<>();
        String filler = "x".repeat(300);
        msgs.add(new UserMessage("ORIGINAL_USER_GOAL: investigate the bug in module X"));
        for (int i = 0; i < 22; i++) {
            msgs.add(new AssistantMessage("assistant turn " + i + " " + filler));
            msgs.add(new UserMessage("user turn " + i + " " + filler));
        }
        // Append five tool-call clusters at the tail half so the pair-safe cut
        // has work to do (the cut may walk through them).
        for (int i = 0; i < 5; i++) {
            String callId = "call-" + i;
            AssistantMessage call = AssistantMessage.builder()
                    .content("calling tool " + i)
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            callId, "function", "search", "{\"q\":\"q" + i + "\"}")))
                    .build();
            ToolResponseMessage response = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            callId, "search", "result body " + i + " " + filler)))
                    .build();
            msgs.add(call);
            msgs.add(response);
        }
        return msgs;
    }

    /**
     * Assert that every {@link AssistantMessage} carrying a non-empty
     * {@code tool_calls} block in {@code messages} is immediately followed
     * by at least one matching {@link ToolResponseMessage}, with one
     * response per call id. Catches the "boundary split through a tool
     * pair" failure mode the structured PTL path is specifically built to
     * avoid.
     */
    private static void assertNoBrokenToolPair(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof AssistantMessage am
                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                // Every call id must appear in subsequent ToolResponseMessage(s)
                // before any other AssistantMessage shows up.
                java.util.Set<String> outstanding = new java.util.LinkedHashSet<>();
                for (var c : am.getToolCalls()) outstanding.add(c.id());
                for (int j = i + 1; j < messages.size() && !outstanding.isEmpty(); j++) {
                    Message next = messages.get(j);
                    if (next instanceof ToolResponseMessage trm) {
                        for (var r : trm.getResponses()) outstanding.remove(r.id());
                    } else if (next instanceof AssistantMessage) {
                        break;
                    }
                }
                assertThat(outstanding)
                        .as("AssistantMessage at index %d has unmatched tool_call ids", i)
                        .isEmpty();
            }
        }
    }

    private static ChatResponse makeChatResponse(String text) {
        AssistantMessage am = new AssistantMessage(text);
        return new ChatResponse(List.of(new Generation(am)));
    }
}
