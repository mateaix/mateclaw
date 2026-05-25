package vip.mate.channel.feishu;

import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.UserId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FeishuMentionTest {

    private static final String BOT_ID = "ou_bot123";
    private static final String BOT_NAME = "TestBot";
    private static final String OTHER_ID = "ou_user456";

    // ==================== eventMentionsContainBot ====================

    @Test
    void event_nullMentions_returnsFalse() {
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(null, BOT_ID, BOT_NAME));
    }

    @Test
    void event_emptyMentions_returnsFalse() {
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[0], BOT_ID, BOT_NAME));
    }

    @Test
    void event_nullBotOpenId_returnsFalse() {
        MentionEvent mention = mentionEvent(BOT_ID);
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, null, BOT_NAME));
    }

    @Test
    void event_botIsMentioned_returnsTrue() {
        MentionEvent mention = mentionEvent(BOT_ID);
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, BOT_NAME));
    }

    @Test
    void event_onlyOtherUserMentioned_returnsFalse() {
        MentionEvent mention = mentionEvent(OTHER_ID);
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, BOT_NAME));
    }

    @Test
    void event_botAmongMultipleMentions_returnsTrue() {
        MentionEvent[] mentions = {mentionEvent(OTHER_ID), mentionEvent(BOT_ID)};
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(mentions, BOT_ID, BOT_NAME));
    }

    @Test
    void event_mentionWithNullId_skippedSafely() {
        MentionEvent mention = MentionEvent.newBuilder().key("@_user_xxx").build(); // no id set
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, BOT_NAME));
    }

    @Test
    void event_botNameMatches_returnsTrue() {
        // 飞书 SDK 对 bot mention 使用不同 ID 体系时，fallback 到 name 匹配
        UserId userId = UserId.newBuilder().openId("ou_different_id").build();
        MentionEvent mention = MentionEvent.newBuilder().id(userId).name(BOT_NAME).key("@_user_1").build();
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, BOT_NAME));
    }

    @Test
    void event_nameMismatchIdMismatch_returnsFalse() {
        UserId userId = UserId.newBuilder().openId("ou_different_id").build();
        MentionEvent mention = MentionEvent.newBuilder().id(userId).name("SomeOtherBot").key("@_user_1").build();
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, BOT_NAME));
    }

    @Test
    void event_nullBotName_onlyIdMatchWorks() {
        // botName 为 null 时，只靠 ID 匹配
        MentionEvent mention = mentionEvent(BOT_ID);
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, null));
        UserId userId = UserId.newBuilder().openId("ou_different_id").build();
        MentionEvent mention2 = MentionEvent.newBuilder().id(userId).name(BOT_NAME).build();
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention2}, BOT_ID, null));
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

    // ==================== collectMentionIdentifiers ====================

    @Test
    void collect_nullOrEmpty_noop() {
        Set<String> sink = new HashSet<>();
        FeishuChannelAdapter.collectMentionIdentifiers(null, sink);
        FeishuChannelAdapter.collectMentionIdentifiers(new MentionEvent[0], sink);
        assertTrue(sink.isEmpty());
    }

    @Test
    void collect_gathersAllNonNullIdentifiers() {
        UserId uid = UserId.newBuilder().openId("ou_a").unionId("on_a").userId("u_a").build();
        MentionEvent m = MentionEvent.newBuilder().id(uid).name("Alice").build();
        Set<String> sink = new HashSet<>();
        FeishuChannelAdapter.collectMentionIdentifiers(new MentionEvent[]{m}, sink);
        assertEquals(Set.of("ou_a", "on_a", "u_a", "Alice"), sink);
    }

    @Test
    void collect_skipsNullFields() {
        UserId uid = UserId.newBuilder().openId("ou_a").build();  // no unionId / userId
        MentionEvent m = MentionEvent.newBuilder().id(uid).build();  // no name
        Set<String> sink = new HashSet<>();
        FeishuChannelAdapter.collectMentionIdentifiers(new MentionEvent[]{m}, sink);
        assertEquals(Set.of("ou_a"), sink);
    }

    // ==================== mentionMatchesAnyAlias ====================

    @Test
    void aliasMatch_emptyMentionsOrAliases_returnsFalse() {
        Set<String> aliases = Set.of("ou_x");
        assertFalse(FeishuChannelAdapter.mentionMatchesAnyAlias(null, aliases));
        assertFalse(FeishuChannelAdapter.mentionMatchesAnyAlias(new MentionEvent[0], aliases));
        UserId uid = UserId.newBuilder().openId("ou_x").build();
        MentionEvent m = MentionEvent.newBuilder().id(uid).build();
        assertFalse(FeishuChannelAdapter.mentionMatchesAnyAlias(new MentionEvent[]{m}, Set.of()));
        assertFalse(FeishuChannelAdapter.mentionMatchesAnyAlias(new MentionEvent[]{m}, null));
    }

    @Test
    void aliasMatch_openIdHit_returnsTrue() {
        UserId uid = UserId.newBuilder().openId("ou_chat_alias").build();
        MentionEvent m = MentionEvent.newBuilder().id(uid).build();
        assertTrue(FeishuChannelAdapter.mentionMatchesAnyAlias(new MentionEvent[]{m}, Set.of("ou_chat_alias")));
    }

    @Test
    void aliasMatch_unionIdHit_returnsTrue() {
        UserId uid = UserId.newBuilder().unionId("on_alias").build();
        MentionEvent m = MentionEvent.newBuilder().id(uid).build();
        assertTrue(FeishuChannelAdapter.mentionMatchesAnyAlias(new MentionEvent[]{m}, Set.of("on_alias")));
    }

    @Test
    void aliasMatch_nameHit_returnsTrue() {
        // 群内别名场景：mention 里只有 chat-scope openId + 自定义名
        UserId uid = UserId.newBuilder().openId("ou_chat_only").build();
        MentionEvent m = MentionEvent.newBuilder().id(uid).name("拉格纳罗斯").build();
        // 缓存里只有 name（极端 case），仍要能命中
        assertTrue(FeishuChannelAdapter.mentionMatchesAnyAlias(new MentionEvent[]{m}, Set.of("拉格纳罗斯")));
    }

    @Test
    void aliasMatch_noOverlap_returnsFalse() {
        UserId uid = UserId.newBuilder().openId("ou_x").unionId("on_x").build();
        MentionEvent m = MentionEvent.newBuilder().id(uid).name("Unknown").build();
        assertFalse(FeishuChannelAdapter.mentionMatchesAnyAlias(new MentionEvent[]{m},
                Set.of("ou_other", "on_other", "OtherBot")));
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
