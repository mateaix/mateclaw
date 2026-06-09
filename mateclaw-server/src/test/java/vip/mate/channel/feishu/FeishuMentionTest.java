package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.UserId;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FeishuMentionTest {

    private static final String BOT_ID = "ou_bot123";
    private static final String OTHER_ID = "ou_user456";

    // ==================== eventMentionsContainBot ====================

    @Test
    void event_nullMentions_returnsFalse() {
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(null, BOT_ID, null));
    }

    @Test
    void event_emptyMentions_returnsFalse() {
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[0], BOT_ID, null));
    }

    @Test
    void event_nullBotOpenId_returnsFalse() {
        MentionEvent mention = mentionEvent(BOT_ID);
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, null, null));
    }

    @Test
    void event_botIsMentioned_returnsTrue() {
        MentionEvent mention = mentionEvent(BOT_ID);
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, null));
    }

    @Test
    void event_onlyOtherUserMentioned_returnsFalse() {
        MentionEvent mention = mentionEvent(OTHER_ID);
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, null));
    }

    @Test
    void event_botAmongMultipleMentions_returnsTrue() {
        MentionEvent[] mentions = {mentionEvent(OTHER_ID), mentionEvent(BOT_ID)};
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(mentions, BOT_ID, null));
    }

    @Test
    void event_mentionWithNullId_skippedSafely() {
        MentionEvent mention = MentionEvent.newBuilder().key("@_user_xxx").build(); // no id set
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, null));
    }

    // ==================== eventMentionsContainBot: 多 ID 体系 + name 命中 ====================

    @Test
    void event_matchByUnionId_returnsTrue() {
        UserId id = UserId.newBuilder().unionId("on_union_777").build();
        MentionEvent mention = MentionEvent.newBuilder().id(id).build();
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, "on_union_777", null));
    }

    @Test
    void event_matchByUserId_returnsTrue() {
        UserId id = UserId.newBuilder().userId("u_555").build();
        MentionEvent mention = MentionEvent.newBuilder().id(id).build();
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, "u_555", null));
    }

    @Test
    void event_matchByName_returnsTrue() {
        // No matching id, but the mention name equals the bot's display name.
        MentionEvent mention = MentionEvent.newBuilder().id(UserId.newBuilder().openId(OTHER_ID).build())
                .name("MateBot").build();
        assertTrue(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, "MateBot"));
    }

    @Test
    void event_noIdNoNameMatch_returnsFalse() {
        MentionEvent mention = MentionEvent.newBuilder().id(UserId.newBuilder().openId(OTHER_ID).build())
                .name("Somebody").build();
        assertFalse(FeishuChannelAdapter.eventMentionsContainBot(new MentionEvent[]{mention}, BOT_ID, "MateBot"));
    }

    // ==================== detectBotMentionWithLearning: 别名学习（有状态） ====================

    @Test
    void learning_aliasMissedBeforeLearn_thenHitsAfterDualDelivery() {
        FeishuChannelAdapter adapter = newAdapter();
        String alias = "ou_group_alias_001";
        String chatA = "oc_chatA";

        // 学习前：群内别名单独到达，botOpenId/botName 都匹配不上 → 漏检。
        assertFalse(adapter.detectBotMentionWithLearning(
                new MentionEvent[]{mentionEvent(alias)}, chatA, "m_pre", BOT_ID, null));

        // 双投递：同一 messageId 先来别名（不匹配），后来全局身份（匹配）→ 聚合学习。
        assertFalse(adapter.detectBotMentionWithLearning(
                new MentionEvent[]{mentionEvent(alias)}, chatA, "m_dual", BOT_ID, null));
        assertTrue(adapter.detectBotMentionWithLearning(
                new MentionEvent[]{mentionEvent(BOT_ID)}, chatA, "m_dual", BOT_ID, null));

        // 学习后：同群再次只收到别名 → 命中。
        assertTrue(adapter.detectBotMentionWithLearning(
                new MentionEvent[]{mentionEvent(alias)}, chatA, "m_post", BOT_ID, null));
    }

    @Test
    void learning_aliasIsolatedPerChat() {
        FeishuChannelAdapter adapter = newAdapter();
        String alias = "ou_group_alias_001";
        String chatA = "oc_chatA";
        String chatB = "oc_chatB";

        // 在 A 群学到别名。
        adapter.detectBotMentionWithLearning(new MentionEvent[]{mentionEvent(alias)}, chatA, "m1", BOT_ID, null);
        adapter.detectBotMentionWithLearning(new MentionEvent[]{mentionEvent(BOT_ID)}, chatA, "m1", BOT_ID, null);
        assertTrue(adapter.detectBotMentionWithLearning(
                new MentionEvent[]{mentionEvent(alias)}, chatA, "m2", BOT_ID, null));

        // B 群从未学习该别名 → 不泄漏，仍漏检。
        assertFalse(adapter.detectBotMentionWithLearning(
                new MentionEvent[]{mentionEvent(alias)}, chatB, "m3", BOT_ID, null));
    }

    // ==================== mention tracker TTL ====================

    @Test
    void tracker_staleEntriesEvictedByTtl() {
        Map<String, FeishuChannelAdapter.MentionTrack> tracker = new java.util.concurrent.ConcurrentHashMap<>();
        FeishuChannelAdapter.MentionTrack track = new FeishuChannelAdapter.MentionTrack();
        tracker.put("m1", track);

        long ttl = 60_000L;
        // now == createdAt → 未过期，保留。
        FeishuChannelAdapter.evictStaleTracks(tracker, track.createdAtMs, ttl);
        assertEquals(1, tracker.size());

        // now 超过 createdAt + ttl → 过期，淘汰。
        FeishuChannelAdapter.evictStaleTracks(tracker, track.createdAtMs + ttl + 1, ttl);
        assertEquals(0, tracker.size());
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

    private static FeishuChannelAdapter newAdapter() {
        ChannelEntity e = new ChannelEntity();
        e.setId(1L);
        e.setChannelType("feishu");
        e.setConfigJson("{\"app_id\":\"x\",\"app_secret\":\"y\"}");
        return new FeishuChannelAdapter(
                e,
                mock(ChannelMessageRouter.class),
                new ObjectMapper(),
                null, null, null, null, null, null,
                null);
    }

    private static MentionEvent mentionEvent(String openId) {
        UserId userId = UserId.newBuilder().openId(openId).build();
        return MentionEvent.newBuilder().id(userId).build();
    }

    private static Map<String, Object> webhookMention(String openId) {
        return Map.of("id", Map.of("open_id", openId));
    }
}
