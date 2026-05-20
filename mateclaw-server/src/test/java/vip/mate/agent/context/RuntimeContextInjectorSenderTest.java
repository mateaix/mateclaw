package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin {@link RuntimeContextInjector}'s sender-block inclusion rules.
 *
 * <p>The sender block is the user-visible payoff of Batch 3 — the LLM
 * sees who's talking, what channel they came in on, and whether it's a
 * 1:1 or group. Test the inclusion / exclusion matrix here so that any
 * regression on the gate (channelType blank / web / cron) is loud.
 *
 * <p>We deliberately do NOT assert on the time line — it changes every
 * second and existing eval baselines already cover it.
 */
class RuntimeContextInjectorSenderTest {

    @Test
    @DisplayName("IM origin with sender + chat → block includes channel, sender, chat lines")
    void imOriginIncludesAllSenderLines() {
        ChatOrigin origin = new ChatOrigin(
                7L, "feishu:oc_abc", "ou_xyz", 5L, "/data/ws/5",
                9L, null, false,
                /* senderName */ "Alice",
                /* channelType */ "feishu",
                /* chatId */ "oc_abc");

        String ctx = RuntimeContextInjector.buildContextMessage("/data/ws/5", null, origin);

        assertTrue(ctx.contains("Channel: feishu"), "channel line missing: " + ctx);
        assertTrue(ctx.contains("Sender: Alice"), "sender name missing: " + ctx);
        assertTrue(ctx.contains("id=ou_xyz"), "sender id missing: " + ctx);
        assertTrue(ctx.contains("Chat: oc_abc"), "chat line missing: " + ctx);
        assertTrue(ctx.contains("group conversation"), "group hint missing: " + ctx);
    }

    @Test
    @DisplayName("IM origin without chatId → no chat line, no group hint")
    void privateChatOmitsChatLine() {
        ChatOrigin origin = new ChatOrigin(
                7L, "feishu:ou_xyz", "ou_xyz", 5L, "/data/ws/5",
                9L, null, false,
                "Alice", "feishu", null);

        String ctx = RuntimeContextInjector.buildContextMessage("/data/ws/5", null, origin);

        assertTrue(ctx.contains("Channel: feishu"));
        assertTrue(ctx.contains("Sender: Alice"));
        assertFalse(ctx.contains("Chat:"), "private chat must not emit Chat line");
        assertFalse(ctx.contains("group conversation"));
    }

    @Test
    @DisplayName("web origin → no sender block (preserves existing prompt cache + eval baseline)")
    void webOriginSuppressesSenderBlock() {
        ChatOrigin origin = ChatOrigin.web("conv_1", "user-1", 5L, "/data/ws/5");

        String ctx = RuntimeContextInjector.buildContextMessage("/data/ws/5", null, origin);

        assertFalse(ctx.contains("Channel:"), "web origin must NOT emit Channel line: " + ctx);
        assertFalse(ctx.contains("Sender:"));
        assertFalse(ctx.contains("Chat:"));
    }

    @Test
    @DisplayName("cron origin → no sender block (system-triggered, no human sender)")
    void cronOriginSuppressesSenderBlock() {
        ChatOrigin origin = ChatOrigin.cron("cron_7", 1L, null, 9L, null);

        String ctx = RuntimeContextInjector.buildContextMessage("", null, origin);

        assertFalse(ctx.contains("Channel:"), "cron origin must NOT emit Channel line: " + ctx);
        assertFalse(ctx.contains("Sender:"));
    }

    @Test
    @DisplayName("null origin → no sender block (matches the no-arg legacy overload exactly)")
    void nullOriginNoSenderBlock() {
        String withNull = RuntimeContextInjector.buildContextMessage("/data/ws/5", null, null);
        String legacy = RuntimeContextInjector.buildContextMessage("/data/ws/5");

        // Both omit the sender block — and stay byte-identical so the
        // legacy overload remains a no-op proxy to the new path.
        assertFalse(withNull.contains("Channel:"));
        assertFalse(legacy.contains("Channel:"));
    }

    @Test
    @DisplayName("EMPTY origin → no sender block")
    void emptyOriginNoSenderBlock() {
        String ctx = RuntimeContextInjector.buildContextMessage("/data/ws/5", null, ChatOrigin.EMPTY);
        assertFalse(ctx.contains("Channel:"));
    }

    @Test
    @DisplayName("IM origin with blank senderName → still emits Channel line, omits Sender line")
    void blankSenderName() {
        ChatOrigin origin = new ChatOrigin(
                7L, null, "ou_xyz", null, null, null, null, false,
                /* senderName */ "  ", "feishu", null);

        String ctx = RuntimeContextInjector.buildContextMessage(null, null, origin);

        assertTrue(ctx.contains("Channel: feishu"));
        assertFalse(ctx.contains("Sender:"), "blank senderName must skip Sender line: " + ctx);
    }
}
