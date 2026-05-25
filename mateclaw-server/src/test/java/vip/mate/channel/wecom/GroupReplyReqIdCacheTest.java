package vip.mate.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.wecom.cards.WeComCardDispatcher;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin the group-chat reply-slot cache contract. WeCom AI Bot platform
 * blocks {@code aibot_send_msg} in group chats — proactive pushes (cron
 * summaries, async-task forwards, image-generation completions) must
 * ride {@code aibot_respond_msg} bound to a prior frame's reqId.
 *
 * <p>Without this cache, any group push silently failed: the test rig
 * here exercises the cache plumbing directly so future changes to the
 * cache eviction strategy or the lookup helper don't regress group
 * delivery semantics.
 */
class GroupReplyReqIdCacheTest {

    private WeComChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        ChannelEntity entity = new ChannelEntity();
        entity.setId(1L);
        entity.setChannelType("wecom");
        entity.setConfigJson("{}");
        adapter = new WeComChannelAdapter(
                entity,
                Mockito.mock(ChannelMessageRouter.class),
                new ObjectMapper(),
                Mockito.mock(ApprovalNotificationService.class),
                Mockito.mock(WeComCardDispatcher.class),
                Mockito.mock(WeComKeepaliveScheduler.class));
    }

    @Test
    @DisplayName("unknown chatId yields null — single chats fall through to aibot_send_msg")
    void unknownChatYieldsNull() {
        assertNull(adapter.pickGroupReplyReqId("never-seen-chat"));
        assertNull(adapter.pickGroupReplyReqId(""));
        assertNull(adapter.pickGroupReplyReqId(null));
    }

    @Test
    @DisplayName("remembered group reqId is returned by the lookup")
    void rememberAndLookup() throws Exception {
        Method remember = WeComChannelAdapter.class.getDeclaredMethod(
                "rememberGroupReplyReqId", String.class, String.class);
        remember.setAccessible(true);

        remember.invoke(adapter, "group-1", "req-aaa");
        assertEquals("req-aaa", adapter.pickGroupReplyReqId("group-1"));

        // Most-recent semantics: a newer reqId for the same group overwrites.
        remember.invoke(adapter, "group-1", "req-bbb");
        assertEquals("req-bbb", adapter.pickGroupReplyReqId("group-1"));
    }

    @Test
    @DisplayName("cache stays bounded under flood — no unbounded growth")
    void cacheBounded() throws Exception {
        Method remember = WeComChannelAdapter.class.getDeclaredMethod(
                "rememberGroupReplyReqId", String.class, String.class);
        remember.setAccessible(true);

        // Exceed the 1000-entry max with 1500 distinct groups.
        for (int i = 0; i < 1500; i++) {
            remember.invoke(adapter, "group-" + i, "req-" + i);
        }

        // Inspect the underlying cache size via reflection.
        java.lang.reflect.Field f = WeComChannelAdapter.class.getDeclaredField("lastChatReqIds");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> map = (ConcurrentHashMap<String, String>) f.get(adapter);
        assertTrue(map.size() <= 1000,
                "cache must not grow beyond LAST_CHAT_REQ_IDS_MAX_SIZE; got " + map.size());
    }

    @Test
    @DisplayName("each group gets independent reqId tracking — no cross-group leakage")
    void independentPerGroup() throws Exception {
        Method remember = WeComChannelAdapter.class.getDeclaredMethod(
                "rememberGroupReplyReqId", String.class, String.class);
        remember.setAccessible(true);

        remember.invoke(adapter, "group-A", "req-A1");
        remember.invoke(adapter, "group-B", "req-B1");
        assertEquals("req-A1", adapter.pickGroupReplyReqId("group-A"));
        assertEquals("req-B1", adapter.pickGroupReplyReqId("group-B"));

        // Updating one doesn't affect the other.
        remember.invoke(adapter, "group-A", "req-A2");
        assertEquals("req-A2", adapter.pickGroupReplyReqId("group-A"));
        assertEquals("req-B1", adapter.pickGroupReplyReqId("group-B"));
    }
}
