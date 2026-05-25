package vip.mate.channel.leader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.discord.DiscordChannelAdapter;
import vip.mate.channel.feishu.FeishuChannelAdapter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.qq.QQChannelAdapter;
import vip.mate.channel.telegram.TelegramChannelAdapter;
import vip.mate.channel.wecom.WeComChannelAdapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Behavioural test for the {@code requiresSingleLeader()} hook on the
 * Feishu and QQ adapters. This is what gates leader election in
 * {@code ChannelManager}, so a regression that flips the answer would
 * silently re-introduce the multi-instance connection-limit bug.
 */
class SingleLeaderHookTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChannelMessageRouter router = mock(ChannelMessageRouter.class);

    private ChannelEntity channel(String type, String configJson) {
        ChannelEntity e = new ChannelEntity();
        e.setId(1L);
        e.setName("test");
        e.setChannelType(type);
        e.setConfigJson(configJson);
        e.setEnabled(true);
        return e;
    }

    @Test
    @DisplayName("Feishu in WebSocket mode requires single leader (default mode)")
    void feishuWebsocketRequiresLeader() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter(
                channel("feishu", "{\"app_id\":\"x\",\"app_secret\":\"y\"}"),
                router, objectMapper);
        assertTrue(adapter.requiresSingleLeader(),
                "Default Feishu mode is websocket and must require single leader");
    }

    @Test
    @DisplayName("Feishu explicitly set to websocket mode requires single leader")
    void feishuExplicitWebsocketRequiresLeader() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter(
                channel("feishu",
                        "{\"app_id\":\"x\",\"app_secret\":\"y\",\"connection_mode\":\"websocket\"}"),
                router, objectMapper);
        assertTrue(adapter.requiresSingleLeader());
    }

    @Test
    @DisplayName("Feishu in webhook mode does NOT require single leader (load-balanced HTTP)")
    void feishuWebhookDoesNotRequireLeader() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter(
                channel("feishu",
                        "{\"app_id\":\"x\",\"app_secret\":\"y\",\"connection_mode\":\"webhook\"}"),
                router, objectMapper);
        assertFalse(adapter.requiresSingleLeader(),
                "Webhook callbacks are HTTP-fanned by the LB, so all nodes may subscribe");
    }

    @Test
    @DisplayName("QQ always requires single leader (gateway rejects duplicate IDENTIFY)")
    void qqAlwaysRequiresLeader() {
        QQChannelAdapter adapter = new QQChannelAdapter(
                channel("qq", "{\"app_id\":\"x\",\"client_secret\":\"y\"}"),
                router, objectMapper);
        assertTrue(adapter.requiresSingleLeader());
    }

    @Test
    @DisplayName("WeCom always requires single leader (WS-only aibot transport)")
    void wecomAlwaysRequiresLeader() {
        WeComChannelAdapter adapter = new WeComChannelAdapter(
                channel("wecom", "{\"bot_id\":\"x\",\"secret\":\"y\"}"),
                router, objectMapper,
                mock(vip.mate.channel.notification.ApprovalNotificationService.class),
                mock(vip.mate.channel.wecom.cards.WeComCardDispatcher.class),
                mock(vip.mate.channel.wecom.WeComKeepaliveScheduler.class));
        assertTrue(adapter.requiresSingleLeader());
    }

    @Test
    @DisplayName("Discord always requires single leader (Gateway WS, 1 session per token)")
    void discordAlwaysRequiresLeader() {
        DiscordChannelAdapter adapter = new DiscordChannelAdapter(
                channel("discord", "{\"bot_token\":\"x\"}"),
                router, objectMapper);
        assertTrue(adapter.requiresSingleLeader());
    }

    @Test
    @DisplayName("Telegram in long-polling mode requires single leader (default mode)")
    void telegramPollingRequiresLeader() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
                channel("telegram", "{\"bot_token\":\"x\"}"),
                router, objectMapper);
        assertTrue(adapter.requiresSingleLeader(),
                "Default Telegram mode is long-polling and must require single leader");
    }

    @Test
    @DisplayName("Telegram explicitly set to polling requires single leader")
    void telegramExplicitPollingRequiresLeader() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
                channel("telegram",
                        "{\"bot_token\":\"x\",\"connection_mode\":\"polling\"}"),
                router, objectMapper);
        assertTrue(adapter.requiresSingleLeader());
    }

    @Test
    @DisplayName("Telegram in webhook mode (explicit + url) does NOT require single leader")
    void telegramExplicitWebhookDoesNotRequireLeader() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
                channel("telegram",
                        "{\"bot_token\":\"x\",\"connection_mode\":\"webhook\",\"webhook_url\":\"https://example.com/hook\"}"),
                router, objectMapper);
        assertFalse(adapter.requiresSingleLeader(),
                "Webhook callbacks are HTTP-fanned by the LB, so all nodes may subscribe");
    }

    @Test
    @DisplayName("Telegram legacy config (no connection_mode + webhook_url set) infers webhook → no leader")
    void telegramLegacyWebhookInferredDoesNotRequireLeader() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
                channel("telegram",
                        "{\"bot_token\":\"x\",\"webhook_url\":\"https://example.com/hook\"}"),
                router, objectMapper);
        assertFalse(adapter.requiresSingleLeader(),
                "Legacy config without connection_mode but with webhook_url must be inferred as webhook");
    }

    @Test
    @DisplayName("Telegram connection_mode=webhook but webhook_url blank falls back to polling → leader required")
    void telegramWebhookWithoutUrlIsPolling() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
                channel("telegram",
                        "{\"bot_token\":\"x\",\"connection_mode\":\"webhook\"}"),
                router, objectMapper);
        assertTrue(adapter.requiresSingleLeader(),
                "Webhook mode without a URL falls through to polling and must require single leader");
    }
}
