package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.config.ConversationWindowProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First-user anchor injection — the artifact re-introduced into the
 * compacted prompt so the model never loses sight of what the user
 * originally asked, even after the actual first turn has been compressed
 * into a structured summary.
 *
 * <p>Invariants verified here:
 * <ul>
 *   <li>Anchors are always {@link UserMessage}s, never SystemMessages
 *       (preventing privilege escalation of historical user input).</li>
 *   <li>The anchor reflects the FIRST <em>real</em> user message — prior
 *       summaries and prior anchors are skipped, otherwise iterative
 *       compaction would anchor compressor output.</li>
 *   <li>Body sizing degrades gracefully: verbatim ≤ budget, head+tail
 *       within 3× budget, pointer line above 3×.</li>
 * </ul>
 */
class ConversationWindowManagerAnchorTest {

    @Test
    void shortFirstUserStaysVerbatim() {
        ConversationWindowManager mgr = newManager(true, 400);

        String goal = "find the bug in foo.js";
        Message anchor = mgr.buildFirstUserAnchor(List.of(
                new UserMessage(goal),
                new AssistantMessage("looking into it")
        ));

        assertInstanceOf(UserMessage.class, anchor);
        String text = anchor.getText();
        assertTrue(text.startsWith(ConversationWindowManager.ANCHOR_PREFIX));
        assertTrue(text.contains(goal),
                "short goals fit the budget verbatim, no truncation marker should appear");
    }

    @Test
    void anchorIsAlwaysUserMessageNeverSystem() {
        ConversationWindowManager mgr = newManager(true, 400);

        Message anchor = mgr.buildFirstUserAnchor(List.of(
                new UserMessage("rewrite this README")
        ));

        // Critical safety property: never promote historical user input into a SystemMessage.
        assertInstanceOf(UserMessage.class, anchor);
    }

    @Test
    void disabledAnchorReturnsNull() {
        ConversationWindowManager mgr = newManager(false, 400);

        Message anchor = mgr.buildFirstUserAnchor(List.of(
                new UserMessage("anything")
        ));

        assertNull(anchor);
    }

    @Test
    void noUserInPrefixReturnsNull() {
        ConversationWindowManager mgr = newManager(true, 400);

        // Prefix is all assistant messages — no user goal to anchor.
        Message anchor = mgr.buildFirstUserAnchor(List.of(
                new AssistantMessage("blah"),
                new AssistantMessage("more blah")
        ));

        assertNull(anchor);
    }

    @Test
    void previousSummaryAndPriorAnchorAreSkipped() {
        ConversationWindowManager mgr = newManager(true, 400);

        String realGoal = "ship a feature flag for the new pricing page";
        Message anchor = mgr.buildFirstUserAnchor(List.of(
                // round-2 prefix: starts with a previous summary, then a prior anchor,
                // then the actual original user message.
                new UserMessage(ConversationWindowManager.SUMMARY_PREFIX + "earlier summary text"),
                new UserMessage(ConversationWindowManager.ANCHOR_PREFIX + "stale anchor from prior round"),
                new UserMessage(realGoal),
                new AssistantMessage("on it")
        ));

        assertNotNull(anchor);
        assertTrue(anchor.getText().contains(realGoal),
                "anchor must reflect the REAL first user message, not a prior summary or prior anchor");
    }

    @Test
    void mediumOverBudgetIsHeadTailTruncated() {
        // 80-token budget → roughly 160-char head+tail target.
        ConversationWindowManager mgr = newManager(true, 80);

        // ~400 chars — within 3× the budget so head+tail truncation should apply.
        String body = "a".repeat(200) + "MIDDLE" + "b".repeat(200);
        Message anchor = mgr.buildFirstUserAnchor(List.of(new UserMessage(body)));

        assertNotNull(anchor);
        String text = anchor.getText();
        assertTrue(text.contains("...["),
                "head+tail truncation marker should be present");
        assertTrue(text.length() < body.length(),
                "anchor must be smaller than original (was " + text.length() + " vs " + body.length() + ")");
        // Head and tail of the original body must both be present.
        assertTrue(text.startsWith(ConversationWindowManager.ANCHOR_PREFIX));
        // The first run of 'a's should still be there
        assertTrue(text.contains("aaaaaaaaaa"));
        // And the tail run of 'b's
        assertTrue(text.contains("bbbbbbbbbb"));
    }

    @Test
    void hugeBodyDegradesToPointerLine() {
        ConversationWindowManager mgr = newManager(true, 80);

        // > 3× the budget → pointer-only path.
        String body = "X".repeat(5000);
        Message anchor = mgr.buildFirstUserAnchor(List.of(new UserMessage(body)));

        assertNotNull(anchor);
        String text = anchor.getText();
        assertTrue(text.length() < 500,
                "pointer line should be far smaller than the body (was " + text.length() + ")");
        assertTrue(text.endsWith("..."),
                "pointer line should end with the truncation marker");
    }

    @Test
    void blankUserMessageReturnsNull() {
        ConversationWindowManager mgr = newManager(true, 400);

        Message anchor = mgr.buildFirstUserAnchor(List.of(
                new UserMessage(""),
                new AssistantMessage("ack")
        ));

        // No real goal text — nothing to anchor.
        assertNull(anchor);
    }

    @Test
    void anchorPrefixIsConsistent() {
        ConversationWindowManager mgr = newManager(true, 400);

        Message a = mgr.buildFirstUserAnchor(List.of(new UserMessage("short")));
        Message b = mgr.buildFirstUserAnchor(List.of(new UserMessage("a different short goal")));

        // Stable marker — downstream code (and the dedup in buildFirstUserAnchor itself)
        // depends on this prefix being constant.
        assertEquals(ConversationWindowManager.ANCHOR_PREFIX,
                a.getText().substring(0, ConversationWindowManager.ANCHOR_PREFIX.length()));
        assertEquals(ConversationWindowManager.ANCHOR_PREFIX,
                b.getText().substring(0, ConversationWindowManager.ANCHOR_PREFIX.length()));
    }

    // ------------------------------------------------------------------ helpers

    private static ConversationWindowManager newManager(boolean enabled, int maxAnchorTokens) {
        ConversationWindowProperties props = new ConversationWindowProperties();
        props.setFirstUserAnchorEnabled(enabled);
        props.setFirstUserAnchorMaxTokens(maxAnchorTokens);
        return new ConversationWindowManager(props, null, null);
    }
}
