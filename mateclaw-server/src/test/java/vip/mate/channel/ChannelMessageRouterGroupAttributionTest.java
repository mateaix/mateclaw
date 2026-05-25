package vip.mate.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin the group-chat sender-attribution contract.
 *
 * <p>In groups, three users sharing one conversation send overlapping
 * questions. Without {@code [@sender]} tags, the persisted history
 * collapses into an unattributed wall of "user:" turns and the LLM can
 * no longer tell who asked what. Without per-sender debounce boundaries,
 * a paste-split fragment from user A can also accidentally absorb user
 * B's text and mis-attribute it. These tests pin both contracts so a
 * future refactor can't silently regress group multi-user usability.
 */
class ChannelMessageRouterGroupAttributionTest {

    private static ChannelMessage groupMessage(String senderId, String senderName, String content) {
        return ChannelMessage.builder()
                .channelType("wecom")
                .senderId(senderId)
                .senderName(senderName)
                .chatId("group-abc")  // chatId set ⇒ group context
                .content(content)
                .build();
    }

    private static ChannelMessage singleMessage(String senderId, String content) {
        return ChannelMessage.builder()
                .channelType("wecom")
                .senderId(senderId)
                .senderName(senderId)
                .chatId(null)  // chatId null ⇒ 1:1 chat
                .content(content)
                .build();
    }

    // ===== buildGroupTag =====

    @Test
    @DisplayName("single chat (chatId null) → no tag, no behavior change")
    void singleChatNoTag() {
        assertNull(ChannelMessageRouter.buildGroupTag(singleMessage("alice", "hi")));
    }

    @Test
    @DisplayName("group chat with senderName → [@senderName] tag")
    void groupWithSenderName() {
        ChannelMessage m = groupMessage("alice-id", "Alice Wang", "hi");
        assertEquals("[@Alice Wang]", ChannelMessageRouter.buildGroupTag(m));
    }

    @Test
    @DisplayName("group chat falls back to senderId when senderName is blank")
    void groupFallsBackToSenderId() {
        ChannelMessage m = groupMessage("alice-id", "", "hi");
        assertEquals("[@alice-id]", ChannelMessageRouter.buildGroupTag(m));
    }

    @Test
    @DisplayName("group chat with no resolvable identity returns null (don't fabricate a tag)")
    void groupNoIdentity() {
        ChannelMessage m = ChannelMessage.builder()
                .channelType("wecom")
                .chatId("group-abc")
                .content("hi")
                .build();
        // Both senderId and senderName are null. Better to skip attribution
        // than to invent "[@null]" which would corrupt the prompt.
        assertNull(ChannelMessageRouter.buildGroupTag(m));
    }

    @Test
    @DisplayName("blank chatId is treated as not-a-group")
    void blankChatIdNotAGroup() {
        ChannelMessage m = ChannelMessage.builder()
                .channelType("wecom")
                .senderId("alice")
                .senderName("Alice")
                .chatId("   ")
                .content("hi")
                .build();
        assertNull(ChannelMessageRouter.buildGroupTag(m));
    }

    // ===== applyGroupTag =====

    @Test
    @DisplayName("applyGroupTag: single chat content passes through verbatim")
    void applyTagSingleChatPassesThrough() {
        ChannelMessage m = singleMessage("alice", "hello world");
        assertEquals("hello world",
                ChannelMessageRouter.applyGroupTag(m, "hello world"));
    }

    @Test
    @DisplayName("applyGroupTag: group content gets [@sender] prefix")
    void applyTagGroupPrefixes() {
        ChannelMessage m = groupMessage("alice-id", "Alice", "hello world");
        assertEquals("[@Alice] hello world",
                ChannelMessageRouter.applyGroupTag(m, "hello world"));
    }

    @Test
    @DisplayName("applyGroupTag: idempotent — already-prefixed content is not double-tagged")
    void applyTagIdempotent() {
        ChannelMessage m = groupMessage("alice-id", "Alice", "ignored");
        // Simulates a code path that has already attributed the content
        // (e.g. a future channel adapter that pre-tags inbound text).
        assertEquals("[@Alice] hello",
                ChannelMessageRouter.applyGroupTag(m, "[@Alice] hello"));
    }

    @Test
    @DisplayName("applyGroupTag: empty content stays empty (no bare-tag artifact)")
    void applyTagEmptyStaysEmpty() {
        ChannelMessage m = groupMessage("alice-id", "Alice", "");
        // A truly empty message (no text, no parts producing text) shouldn't
        // surface as a useless "[@Alice]" turn — the agent has nothing to
        // act on. Skip the tag to keep persisted history clean.
        assertEquals("", ChannelMessageRouter.applyGroupTag(m, ""));
        assertNull(ChannelMessageRouter.applyGroupTag(m, null));
    }

    // ===== isSameSender (the merge boundary helper) =====

    @Test
    @DisplayName("isSameSender: same sender → merge allowed (paste-split / rapid follow-up)")
    void sameSenderMergeAllowed() {
        assertTrue(ChannelMessageRouter.isSameSender("alice", "alice"));
    }

    @Test
    @DisplayName("isSameSender: different sender → no merge (group sender boundary)")
    void differentSenderNoMerge() {
        // The whole point of the group fix: A's pending must NOT absorb B's
        // text, otherwise the merged buffer attributes both to A.
        assertFalse(ChannelMessageRouter.isSameSender("alice", "bob"));
    }

    @Test
    @DisplayName("isSameSender: null on either side → no merge (defensive)")
    void nullSendersNoMerge() {
        // Pending fixtures occasionally have null senderIds; better to start
        // a fresh pending than to silently merge into an unidentified buffer.
        assertFalse(ChannelMessageRouter.isSameSender(null, "alice"));
        assertFalse(ChannelMessageRouter.isSameSender("alice", null));
        assertFalse(ChannelMessageRouter.isSameSender(null, null));
    }
}
