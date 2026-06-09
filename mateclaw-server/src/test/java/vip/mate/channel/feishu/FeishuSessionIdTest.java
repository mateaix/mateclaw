package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #299：群会话 ID 改用完整 chatId 避免后缀碰撞，存量旧会话用读时别名回退（不重写）。
 */
class FeishuSessionIdTest {

    private static final String CHANNEL = FeishuChannelAdapter.CHANNEL_TYPE; // "feishu"
    private static final String APP_ID = "cli_a1b2c3d4e5f6";

    // ==================== legacyGroupSuffix：旧后缀算法 + 碰撞演示 ====================

    @Test
    void legacySuffix_format_appLast4UnderscoreChatLast8() {
        FeishuChannelAdapter adapter = newAdapter(mock(ChannelMessageRouter.class));
        // appId 后 4 = "e5f6"; chatId 后 8 = "11112222"
        assertEquals("e5f6_11112222", adapter.legacyGroupSuffix("oc_aaaa11112222"));
    }

    @Test
    void legacySuffix_collides_whileFullChatIdDoesNot() {
        FeishuChannelAdapter adapter = newAdapter(mock(ChannelMessageRouter.class));
        String chatA = "oc_AAAA_11112222";
        String chatB = "oc_BBBB_11112222"; // 不同群，但 chatId 后 8 位相同
        // 旧后缀碰撞（这正是 #299 要修的 bug）……
        assertEquals(adapter.legacyGroupSuffix(chatA), adapter.legacyGroupSuffix(chatB));
        // ……而完整 chatId 不碰撞。
        assertNotEquals(chatA, chatB);
    }

    // ==================== pickGroupSessionSuffix：纯选择逻辑 ====================

    @Test
    void pick_newGroup_neitherExists_usesCanonical() {
        assertEquals("oc_full", FeishuChannelAdapter.pickGroupSessionSuffix(
                "oc_full", "e5f6_ocfull99", false, false));
    }

    @Test
    void pick_canonicalAlreadyExists_usesCanonical() {
        assertEquals("oc_full", FeishuChannelAdapter.pickGroupSessionSuffix(
                "oc_full", "e5f6_ocfull99", true, false));
    }

    @Test
    void pick_onlyLegacyExists_reusesLegacy() {
        assertEquals("e5f6_ocfull99", FeishuChannelAdapter.pickGroupSessionSuffix(
                "oc_full", "e5f6_ocfull99", false, true));
    }

    // ==================== resolveGroupSessionSuffix：读时别名回退（含路由查找） ====================

    @Test
    void resolve_newGroup_returnsFullChatId() {
        ChannelMessageRouter router = mock(ChannelMessageRouter.class);
        when(router.conversationExists(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        FeishuChannelAdapter adapter = newAdapter(router);
        assertEquals("oc_aaaa11112222", adapter.resolveGroupSessionSuffix("oc_aaaa11112222"));
    }

    @Test
    void resolve_canonicalExists_returnsFullChatId() {
        ChannelMessageRouter router = mock(ChannelMessageRouter.class);
        when(router.conversationExists(CHANNEL + ":oc_aaaa11112222")).thenReturn(true);
        FeishuChannelAdapter adapter = newAdapter(router);
        assertEquals("oc_aaaa11112222", adapter.resolveGroupSessionSuffix("oc_aaaa11112222"));
    }

    @Test
    void resolve_legacyConversationExists_reusesLegacyId() {
        ChannelMessageRouter router = mock(ChannelMessageRouter.class);
        when(router.conversationExists(CHANNEL + ":oc_aaaa11112222")).thenReturn(false);
        when(router.conversationExists(CHANNEL + ":e5f6_11112222")).thenReturn(true);
        FeishuChannelAdapter adapter = newAdapter(router);
        // 存量群：canonical 无、legacy 有 → 沿用 legacy，历史无缝延续。
        assertEquals("e5f6_11112222", adapter.resolveGroupSessionSuffix("oc_aaaa11112222"));
    }

    // ==================== helpers ====================

    private static FeishuChannelAdapter newAdapter(ChannelMessageRouter router) {
        ChannelEntity e = new ChannelEntity();
        e.setId(1L);
        e.setChannelType("feishu");
        e.setConfigJson("{\"app_id\":\"" + APP_ID + "\",\"app_secret\":\"y\"}");
        return new FeishuChannelAdapter(
                e, router, new ObjectMapper(),
                null, null, null, null, null, null, null);
    }
}
