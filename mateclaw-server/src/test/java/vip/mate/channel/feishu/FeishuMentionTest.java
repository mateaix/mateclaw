package vip.mate.channel.feishu;

import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.UserId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeishuMentionTest {

    private static final String BOT_ID = "ou_bot123";
    private static final String OTHER_ID = "ou_user456";

    // ==================== eventMentionsContainBot ====================

    @Test
    void event_nullMentions_returnsFalse() {
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(null, BOT_ID));
    }

    @Test
    void event_emptyMentions_returnsFalse() {
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[0], BOT_ID));
    }

    @Test
    void event_nullBotOpenId_returnsFalse() {
        MentionEvent mention = mentionEvent(BOT_ID);
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, null));
    }

    @Test
    void event_botIsMentioned_returnsTrue() {
        MentionEvent mention = mentionEvent(BOT_ID);
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID));
    }

    @Test
    void event_onlyOtherUserMentioned_returnsFalse() {
        MentionEvent mention = mentionEvent(OTHER_ID);
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID));
    }

    @Test
    void event_botAmongMultipleMentions_returnsTrue() {
        MentionEvent[] mentions = {mentionEvent(OTHER_ID), mentionEvent(BOT_ID)};
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(mentions, BOT_ID));
    }

    @Test
    void event_mentionWithNullId_skippedSafely() {
        MentionEvent mention = MentionEvent.newBuilder().key("@_user_xxx").build(); // no id set
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID));
    }

    // ==================== webhookMentionsContainBot ====================

    @Test
    void webhook_nullList_returnsFalse() {
        assertFalse(FeishuChannelAdapter.webhookMentionsContainBot(null, BOT_ID));
    }

    @Test
    void webhook_emptyList_returnsFalse() {
        assertFalse(FeishuChannelAdapter.webhookMentionsContainBot(List.of(), BOT_ID));
    }

    @Test
    void webhook_nullBotOpenId_returnsFalse() {
        List<?> mentions = List.of(webhookMention(BOT_ID));
        assertFalse(FeishuChannelAdapter.webhookMentionsContainBot(mentions, null));
    }

    @Test
    void webhook_botIsMentioned_returnsTrue() {
        List<?> mentions = List.of(webhookMention(BOT_ID));
        assertTrue(FeishuChannelAdapter.webhookMentionsContainBot(mentions, BOT_ID));
    }

    @Test
    void webhook_onlyOtherUserMentioned_returnsFalse() {
        List<?> mentions = List.of(webhookMention(OTHER_ID));
        assertFalse(FeishuChannelAdapter.webhookMentionsContainBot(mentions, BOT_ID));
    }

    @Test
    void webhook_botAmongMultipleMentions_returnsTrue() {
        List<?> mentions = List.of(webhookMention(OTHER_ID), webhookMention(BOT_ID));
        assertTrue(FeishuChannelAdapter.webhookMentionsContainBot(mentions, BOT_ID));
    }

    @Test
    void webhook_malformedItem_skippedSafely() {
        List<?> mentions = List.of("not-a-map", Map.of("no_id_key", "value"), webhookMention(BOT_ID));
        assertTrue(FeishuChannelAdapter.webhookMentionsContainBot(mentions, BOT_ID));
    }

    @Test
    void webhook_idMissingOpenId_skippedSafely() {
        Map<String, Object> mention = Map.of("id", Map.of("user_id", "u123")); // no open_id key
        assertFalse(FeishuChannelAdapter.webhookMentionsContainBot(List.of(mention), BOT_ID));
    }

    // ==================== isGroupNonMentionDrop (gate matrix) ====================

    @Test
    void gate_groupRequireMentionBotMentioned_passesThrough() {
        assertFalse(FeishuChannelAdapter.isGroupNonMentionDrop(true, true, true, BOT_ID));
    }

    @Test
    void gate_groupRequireMentionBotNotMentioned_dropsWhenOpenIdKnown() {
        assertTrue(FeishuChannelAdapter.isGroupNonMentionDrop(true, true, false, BOT_ID));
    }

    @Test
    void gate_groupRequireMentionBotNotMentioned_failsOpenWhenOpenIdUnknown() {
        // Bot identity unavailable (API outage / pending fetch) → degrade to allow.
        // This was the bug the original PR shipped with: gate dropped instead of fell open.
        assertFalse(FeishuChannelAdapter.isGroupNonMentionDrop(true, true, false, null));
    }

    @Test
    void gate_p2pAlwaysPasses_regardlessOfRequireMention() {
        // require_mention only applies to group chat; DMs are never gated.
        assertFalse(FeishuChannelAdapter.isGroupNonMentionDrop(false, true, false, BOT_ID));
        assertFalse(FeishuChannelAdapter.isGroupNonMentionDrop(false, true, false, null));
        assertFalse(FeishuChannelAdapter.isGroupNonMentionDrop(false, true, true, BOT_ID));
    }

    @Test
    void gate_requireMentionDisabled_passesThrough() {
        assertFalse(FeishuChannelAdapter.isGroupNonMentionDrop(true, false, false, BOT_ID));
        assertFalse(FeishuChannelAdapter.isGroupNonMentionDrop(true, false, false, null));
    }

    // ==================== helpers ====================

    private static MentionEvent mentionEvent(String openId) {
        UserId userId = UserId.newBuilder().openId(openId).build();
        return MentionEvent.newBuilder().id(userId).build();
    }

    private static Map<String, Object> webhookMention(String openId) {
        return Map.of("id", Map.of("open_id", openId));
    }
}
