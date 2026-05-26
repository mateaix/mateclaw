package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of {@link LoopMessageBudgeter} — the per-ReAct-loop trim that
 * replaces the previous fixed head-4 / tail-36 cut.
 *
 * <p>The single load-bearing invariant is the <b>anchor</b>: the latest
 * {@link UserMessage} must always survive into the LLM-bound list, even when
 * the token-budgeted tail would have dropped it. Losing that message is what
 * made the agent answer "I am an AI assistant" to a question about Qwen 3.7.
 */
class LoopMessageBudgeterTest {

    private final LoopMessageBudgeter budgeter = new LoopMessageBudgeter();

    // ---- baselines ---------------------------------------------------------

    @Test
    @DisplayName("empty list returns untouched")
    void empty_noOp() {
        LoopMessageBudgeter.Result r = budgeter.budget(List.of(), defaultCfg());
        assertEquals(0, r.messages().size());
        assertFalse(r.trace().modified());
    }

    @Test
    @DisplayName("below trigger thresholds: list is forwarded unchanged")
    void belowTrigger_passthrough() {
        List<Message> input = new ArrayList<>();
        input.add(new SystemMessage("you are a tester"));
        input.add(new UserMessage("hi"));
        input.add(new AssistantMessage("hello"));

        LoopMessageBudgeter.Result r = budgeter.budget(input, defaultCfg());

        assertSame(input, r.messages(), "untouched fast-path must return the same list reference");
        assertFalse(r.trace().modified());
        assertEquals(3, r.trace().finalCount());
        assertEquals(1, r.trace().headKept());
    }

    // ---- anchor enforcement (the regression we're fixing) ------------------

    @Test
    @DisplayName("anchor: latest UserMessage is never dropped, tail pulled back to keep it")
    void anchor_preventsLatestUserMessageDrop() {
        // Simulate the Qwen-3.7 failure shape: one system prompt, the user
        // question at index 4, then a flood of tool spam that fills the
        // entire token-budgeted tail. The latest UserMessage lives at the
        // "middle" of the list and a naive token-budget tail cut drops it.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("system"));            // 0
        msgs.add(new UserMessage("old turn"));            // 1
        msgs.add(new AssistantMessage("old reply"));      // 2
        for (int i = 0; i < 10; i++) {
            // 10 fat noise messages between old turn and the new user turn
            msgs.add(new AssistantMessage(fat("noise-pre-" + i)));
        }
        msgs.add(new UserMessage("查下 qwen 3.7"));        // anchor — must survive
        for (int i = 0; i < 100; i++) {
            // 100 fat tool observations after the new user message
            msgs.add(new AssistantMessage(fat("tool-obs-" + i)));
        }
        int anchorIdx = 13;
        assertTrue(msgs.get(anchorIdx) instanceof UserMessage
                && ((UserMessage) msgs.get(anchorIdx)).getText().contains("qwen 3.7"));

        // Tail budget intentionally tight so the naive cut would drop the anchor.
        LoopBudgetConfig cfg = new LoopBudgetConfig(50_000, 10_000, 4, 1.5, 0, 200);

        LoopMessageBudgeter.Result r = budgeter.budget(msgs, cfg);

        assertTrue(r.trace().modified(), "budget should have triggered");
        assertTrue(r.trace().anchorEnforced(),
                "tail cut had to be pulled back to keep the anchor — that's the whole point");
        assertTrue(containsExact(r.messages(), "查下 qwen 3.7"),
                "the user's question MUST remain in the LLM-bound message list");
        // Head must still carry the system prompt.
        assertTrue(r.messages().get(0) instanceof SystemMessage);
    }

    // ---- token-budget tail vs old fixed-count tail -------------------------

    @Test
    @DisplayName("tail is sized by token estimate, not message count")
    void tail_sizedByTokens() {
        // Tail of mostly tiny messages can grow to many entries; tail of a
        // few huge messages stays small. The same config should produce
        // tails of very different message counts.
        LoopBudgetConfig cfg = new LoopBudgetConfig(50_000, 8_000, 4, 1.5, 0, 500);

        // Case A: 200 tiny assistant messages — many should survive in tail.
        List<Message> tiny = new ArrayList<>();
        tiny.add(new SystemMessage("sys"));
        tiny.add(new UserMessage("anchor"));
        for (int i = 0; i < 200; i++) tiny.add(new AssistantMessage("x" + i));
        // Force trigger by adding bulk to original token total.
        for (int i = 0; i < 60; i++) tiny.add(new AssistantMessage(fat("bulk" + i)));
        int finalTiny = budgeter.budget(tiny, cfg).trace().finalCount();

        // Case B: only fat messages.
        List<Message> big = new ArrayList<>();
        big.add(new SystemMessage("sys"));
        big.add(new UserMessage("anchor"));
        for (int i = 0; i < 60; i++) big.add(new AssistantMessage(fat("big" + i)));
        int finalBig = budgeter.budget(big, cfg).trace().finalCount();

        assertTrue(finalTiny > finalBig,
                "tail with tiny messages should keep more entries than tail with fat ones "
                        + "(tiny=" + finalTiny + ", big=" + finalBig + ")");
    }

    // ---- head detection ----------------------------------------------------

    @Test
    @DisplayName("head: every consecutive SystemMessage is preserved (not just first 4)")
    void head_acceptsManySystemMessages() {
        List<Message> msgs = new ArrayList<>();
        // 6 system messages: SOUL, AGENTS, runtime context, wiki, tool prompt,
        // skill catalog — realistic for production agents.
        for (int i = 0; i < 6; i++) msgs.add(new SystemMessage("system-" + i));
        msgs.add(new UserMessage("anchor"));
        for (int i = 0; i < 80; i++) msgs.add(new AssistantMessage(fat("obs-" + i)));

        LoopBudgetConfig cfg = new LoopBudgetConfig(20_000, 5_000, 4, 1.5, 0, 200);
        LoopMessageBudgeter.Result r = budgeter.budget(msgs, cfg);

        assertTrue(r.trace().triggered(), "token total exceeds trigger; main path must run");
        assertEquals(6, r.trace().headKept(),
                "all six system messages must survive — not the legacy hard-coded 4");
        for (int i = 0; i < 6; i++) {
            assertTrue(r.messages().get(i) instanceof SystemMessage,
                    "head slot " + i + " should be SystemMessage");
        }
    }

    // ---- tool-pair integrity ----------------------------------------------

    @Test
    @DisplayName("tool-pair: Assistant(tool_calls) and ToolResponseMessage at boundary stay paired")
    void toolPair_pulledBackTogether() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(new UserMessage("anchor"));
        // A long head of fat messages that pushes the tail boundary.
        for (int i = 0; i < 50; i++) msgs.add(new AssistantMessage(fat("head-" + i)));
        // Tool-pair right at the would-be cut boundary.
        msgs.add(asst("call-A"));
        msgs.add(resp("call-A"));
        // Tail of recent assistant chatter after the pair.
        for (int i = 0; i < 5; i++) msgs.add(new AssistantMessage("tail-" + i));

        LoopBudgetConfig cfg = new LoopBudgetConfig(10_000, 3_000, 4, 1.5, 0, 200);
        LoopMessageBudgeter.Result r = budgeter.budget(msgs, cfg);

        // Both members of the pair survive together; never one without the other.
        boolean hasCall = r.messages().stream().anyMatch(m -> m instanceof AssistantMessage am
                && am.getToolCalls() != null && am.getToolCalls().stream().anyMatch(tc -> "call-A".equals(tc.id())));
        boolean hasResp = r.messages().stream().anyMatch(m -> m instanceof ToolResponseMessage trm
                && trm.getResponses().stream().anyMatch(rr -> "call-A".equals(rr.id())));
        assertEquals(hasCall, hasResp,
                "tool_call and its response must both survive or both be removed — never one without the other");
        assertEquals(0, r.trace().orphansRemoved(),
                "pull-back at boundary should have prevented any orphan from being produced");
    }

    @Test
    @DisplayName("integrity invariant: call-X and resp-X either both survive or both are removed")
    void toolPair_integrityInvariant() {
        // The pull-back at the boundary normally keeps pairs together, and
        // the bidirectional orphan pass catches cross-boundary leftovers.
        // The invariant we care about is the post-condition: the final list
        // never contains a response without its matching call (or vice versa).
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(new UserMessage("anchor"));
        for (int i = 0; i < 100; i++) msgs.add(new AssistantMessage(fat("mid-" + i)));
        msgs.add(asst("call-X"));
        msgs.add(new AssistantMessage(fat("tail-fill-1")));
        msgs.add(resp("call-X"));
        msgs.add(new AssistantMessage("tail-fill-2"));

        LoopBudgetConfig cfg = new LoopBudgetConfig(10_000, 2_000, 4, 1.5, 0, 200);
        LoopMessageBudgeter.Result r = budgeter.budget(msgs, cfg);

        boolean hasCall = r.messages().stream().anyMatch(m -> m instanceof AssistantMessage am
                && am.getToolCalls() != null
                && am.getToolCalls().stream().anyMatch(tc -> "call-X".equals(tc.id())));
        boolean hasResp = r.messages().stream().anyMatch(m -> m instanceof ToolResponseMessage trm
                && trm.getResponses().stream().anyMatch(rr -> "call-X".equals(rr.id())));
        assertEquals(hasCall, hasResp,
                "tool_call and matching response must both survive or both be dropped — "
                        + "orphan removal + pull-back guarantee this post-condition "
                        + "(hasCall=" + hasCall + ", hasResp=" + hasResp + ")");
    }

    // ---- absolute max safety net ------------------------------------------

    @Test
    @DisplayName("absoluteMax: pathological count is capped even when token budget allowed more")
    void absoluteMax_caps() {
        // 500 tiny messages — each ~5 chars; total tokens well under the
        // budget so the token cut allows everything, but message count is
        // pathological and must be capped. Anchor is at index 1 (right after
        // the system header), so the cap would drop it without the stitch.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(new UserMessage("ANCHOR"));
        for (int i = 0; i < 500; i++) msgs.add(new AssistantMessage("x"));

        // Larger minTailMessages floor not relevant here — we want to verify
        // the hard count cap, which dominates over the floor in this case.
        LoopBudgetConfig cfg = new LoopBudgetConfig(50_000, 30_000, 4, 1.5, 0, 50);

        LoopMessageBudgeter.Result r = budgeter.budget(msgs, cfg);

        assertTrue(r.trace().modified());
        assertTrue(r.trace().finalCount() <= 50,
                "target max should cap the count regardless of token budget (got "
                        + r.trace().finalCount() + ")");
        assertTrue(r.trace().targetMaxTripped(), "trace should record the trip");
        assertTrue(containsExact(r.messages(), "ANCHOR"),
                "even when absoluteMax forces a hard drop, the latest UserMessage must "
                        + "remain — stitched in if necessary");
    }

    // ---- config validation --------------------------------------------------

    @Test
    @DisplayName("LoopBudgetConfig.forContext picks sensible ratios from a context window")
    void forContext_ratios() {
        LoopBudgetConfig c = LoopBudgetConfig.forContext(128_000);
        assertEquals(64_000, c.triggerTokens());
        assertEquals(38_400, c.keepTailTokens());
        assertEquals(4, c.minTailMessages());
        assertEquals(1.5, c.tailSoftCeilingRatio(), 0.001);
        assertEquals(0, c.reservedPrefixTokens());
        assertEquals(200, c.targetMaxMessages());
    }

    @Test
    @DisplayName("LoopBudgetConfig rejects degenerate values")
    void config_rejectsBadValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new LoopBudgetConfig(5_000, 6_000, 4, 1.5, 0, 200),
                "tail must be strictly less than trigger");
        assertThrows(IllegalArgumentException.class,
                () -> new LoopBudgetConfig(100, 50, 4, 1.5, 0, 200),
                "trigger below MIN_TRIGGER_TOKENS rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new LoopBudgetConfig(50_000, 10_000, 1, 1.5, 0, 200),
                "minTailMessages below floor rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new LoopBudgetConfig(50_000, 10_000, 4, 0.5, 0, 200),
                "tailSoftCeilingRatio below 1.0 rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new LoopBudgetConfig(50_000, 10_000, 4, 1.5, -1, 200),
                "negative reservedPrefixTokens rejected");
    }

    // ---- prefix-token accounting -------------------------------------------

    @Test
    @DisplayName("reservedPrefixTokens: trigger fires earlier when prefix is heavy")
    void reservedPrefix_triggersBudgetWithSmallerHistory() {
        // Build a moderate history that would NOT trigger on its own (~6K
        // tokens), but combined with a 60K prefix reservation pushes the
        // total over the 50K trigger.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(new UserMessage("anchor"));
        for (int i = 0; i < 10; i++) msgs.add(new AssistantMessage(fat("obs-" + i)));

        // Without prefix accounting: well under 50K trigger.
        LoopBudgetConfig noPrefix = new LoopBudgetConfig(50_000, 10_000, 4, 1.5, 0, 500);
        assertFalse(budgeter.budget(msgs, noPrefix).trace().triggered(),
                "without prefix reservation, this history must not trigger");

        // With 60K prefix: should fire.
        LoopBudgetConfig withPrefix = new LoopBudgetConfig(50_000, 10_000, 4, 1.5, 60_000, 500);
        LoopMessageBudgeter.Result r = budgeter.budget(msgs, withPrefix);
        assertTrue(r.trace().triggered(),
                "with 60K reserved prefix, the same history must trip the trigger");
        assertEquals(60_000, r.trace().reservedPrefixTokens());
    }

    // ---- min-tail floor ----------------------------------------------------

    @Test
    @DisplayName("minTailMessages: a single huge tool output doesn't collapse the tail")
    void minTail_floorPreservesRecentContext() {
        // 50 fat history messages + an extremely fat last tool output that
        // alone exceeds the hard tail budget. Without the floor, the
        // backward walk in findTailCutByTokens would stop at just that one
        // message, losing all recent reasoning.
        // Anchor at the END so anchor enforcement doesn't paper over the
        // floor — anchor-near-head would pull the cut back to include
        // everything, masking the floor's structural effect.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        for (int i = 0; i < 50; i++) msgs.add(new AssistantMessage(fat("head-fill-" + i)));
        // The last assistant dwarfs the hard 5K tail budget.
        msgs.add(new AssistantMessage(huge("monster-last")));
        msgs.add(new UserMessage("tail anchor"));

        LoopBudgetConfig cfg = new LoopBudgetConfig(30_000, 5_000, 4, 1.5, 0, 200);

        LoopMessageBudgeter.Result r = budgeter.budget(msgs, cfg);

        assertTrue(r.trace().triggered(), "token total exceeds trigger");
        assertTrue(r.trace().minTailFloorApplied(),
                "the floor must have engaged — last message alone exceeds hard tail budget");
        assertTrue(r.trace().tailKept() >= 4,
                "at least minTailMessages (4) entries should survive — got " + r.trace().tailKept());
        assertTrue(containsExact(r.messages(), "tail anchor"),
                "anchor at the end must remain");
    }

    // ---- pair-integrity-vs-cap reporting -----------------------------------

    @Test
    @DisplayName("capExceededForPairIntegrity: cap is exceeded when pair pull-back wins")
    void capExceeded_whenPairIntegrityForcesEarlierCut() {
        // Setup: configure a targetMaxMessages cap such that the natural
        // cap-driven cut lands inside a multi-response pair. Pair pull-back
        // has to drag the boundary back across the entire pair → final
        // count exceeds the cap, and the trace records the trade-off.
        // Layout (idx): 0=sys, 1=anchor, 2-87=head fillers (86),
        // 88=asst(c1..c5), 89-93=resp(c1..c5), 94-110=tail fillers (17).
        // Total 111 messages.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(new UserMessage("anchor"));
        for (int i = 0; i < 86; i++) msgs.add(new AssistantMessage(fat("head-" + i)));
        msgs.add(asstMulti("c1", "c2", "c3", "c4", "c5"));
        for (int i = 1; i <= 5; i++) msgs.add(resp("c" + i));
        for (int i = 0; i < 17; i++) msgs.add(new AssistantMessage("tail-" + i));

        // Cap of 20 → tail cap = 19. provisionalTailStart = 111-19 = 92
        // (lands inside the responses). Pair pull-back drags it to 88.
        // Final tail = 111-88 = 23, +head 1 = 24 > 20 cap → integrity wins.
        LoopBudgetConfig cfg = new LoopBudgetConfig(20_000, 5_000, 4, 1.5, 0, 20);

        LoopMessageBudgeter.Result r = budgeter.budget(msgs, cfg);

        assertTrue(r.trace().targetMaxTripped());
        assertTrue(r.trace().capExceededForPairIntegrity(),
                "pair pull-back forced final count above targetMaxMessages — "
                        + "trace must surface this so observers can react");
        assertTrue(r.trace().finalCount() > cfg.targetMaxMessages(),
                "final count must actually exceed the cap (got " + r.trace().finalCount()
                        + ", cap=" + cfg.targetMaxMessages() + ")");
        // Pair integrity still held: every response has its call.
        assertTrue(ToolPairSanitizer.isPaired(r.messages()),
                "tool-pair invariant must hold post-budget");
    }

    // ---- ToolPairSanitizer post-condition ----------------------------------

    @Test
    @DisplayName("ToolPairSanitizer.isPaired: post-condition holds across all trim paths")
    void sanitizer_postConditionAlwaysHolds() {
        // Run several scenarios and assert the sanitizer post-condition.
        // Building a quick matrix is cheaper than convincing ourselves the
        // budgeter never produces an orphan, ever.
        List<List<Message>> scenarios = new ArrayList<>();

        // (a) clean history, no trim needed
        List<Message> a = new ArrayList<>();
        a.add(new SystemMessage("sys"));
        a.add(new UserMessage("u"));
        a.add(asst("c1"));
        a.add(resp("c1"));
        a.add(new AssistantMessage("done"));
        scenarios.add(a);

        // (b) trim with pair at boundary
        List<Message> b = new ArrayList<>();
        b.add(new SystemMessage("sys"));
        b.add(new UserMessage("u"));
        for (int i = 0; i < 60; i++) b.add(new AssistantMessage(fat("h" + i)));
        b.add(asst("c1"));
        b.add(resp("c1"));
        b.add(new AssistantMessage("tail"));
        scenarios.add(b);

        // (c) cap-driven cut deep inside pair territory
        List<Message> c = new ArrayList<>();
        c.add(new SystemMessage("sys"));
        c.add(new UserMessage("u"));
        for (int i = 0; i < 100; i++) c.add(new AssistantMessage("tiny" + i));
        c.add(asst("cx"));
        for (int i = 0; i < 100; i++) c.add(new AssistantMessage("tiny-mid" + i));
        c.add(resp("cx"));
        scenarios.add(c);

        for (int idx = 0; idx < scenarios.size(); idx++) {
            LoopBudgetConfig cfg = new LoopBudgetConfig(8_000, 2_500, 4, 1.5, 0, 30);
            LoopMessageBudgeter.Result r = budgeter.budget(scenarios.get(idx), cfg);
            assertTrue(ToolPairSanitizer.isPaired(r.messages()),
                    "scenario " + idx + " produced an unpaired list: " + r.trace());
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static LoopBudgetConfig defaultCfg() {
        return LoopBudgetConfig.forContext(128_000);
    }

    /** Produce a ~2KB string so token-budget tests cross thresholds with few entries. */
    private static String fat(String tag) {
        StringBuilder sb = new StringBuilder(2000);
        sb.append(tag).append(": ");
        while (sb.length() < 2000) sb.append("lorem ipsum dolor sit amet ");
        return sb.toString();
    }

    /** Produce a ~30KB string — bigger than typical tail budgets so it forces the floor. */
    private static String huge(String tag) {
        StringBuilder sb = new StringBuilder(30_000);
        sb.append(tag).append(": ");
        while (sb.length() < 30_000) sb.append("lorem ipsum dolor sit amet consectetur ");
        return sb.toString();
    }

    private static AssistantMessage asst(String callId) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall(callId, "function", "tool_" + callId, "{}")))
                .build();
    }

    private static AssistantMessage asstMulti(String... callIds) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String id : callIds) {
            calls.add(new AssistantMessage.ToolCall(id, "function", "tool_" + id, "{}"));
        }
        return AssistantMessage.builder().content("").toolCalls(calls).build();
    }

    private static ToolResponseMessage resp(String callId) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(callId, "tool_" + callId, "ok"))).build();
    }

    /** Returns true if any UserMessage in {@code list} has text containing the given substring. */
    private static boolean containsExact(List<Message> list, String substring) {
        return list.stream().anyMatch(m -> m instanceof UserMessage u && u.getText().contains(substring));
    }
}
