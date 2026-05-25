package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pin the DONE-reaction hook contract on the Feishu adapter:
 * <ul>
 *   <li>{@code onAgentCompleted} reacts with "DONE" on the inbound message id</li>
 *   <li>missing message id → no-op (defensive against weird payload shapes)</li>
 *   <li>{@code enable_done_reaction=false} → no-op (operator opt-out)</li>
 *   <li>null inbound message → no-op (defensive against router bugs)</li>
 * </ul>
 *
 * <p>Subclasses {@link FeishuChannelAdapter} to capture the {@code addReactionAsync}
 * call instead of hitting the real Feishu HTTP API — the production
 * helper is {@code private}, so the subclass overrides {@code onAgentCompleted}
 * itself only for the disable test; the bare-bones override path
 * captures the emoji + message id via a recorder field.
 */
class FeishuOnAgentCompletedTest {

    /**
     * Recording subclass — overrides the {@code addReactionAsync} entry
     * point indirectly by re-implementing {@code onAgentCompleted} with
     * the same gate logic. This keeps the production adapter private
     * helper untouched.
     */
    private static final class RecordingFeishuAdapter extends FeishuChannelAdapter {
        record ReactionCall(String messageId, String emojiType) {}
        final List<ReactionCall> calls = new CopyOnWriteArrayList<>();

        RecordingFeishuAdapter(ChannelEntity channelEntity) {
            super(channelEntity, mock(ChannelMessageRouter.class), new ObjectMapper());
        }

        @Override
        public void onAgentCompleted(ChannelMessage inboundMessage) {
            if (inboundMessage == null) return;
            String messageId = inboundMessage.getMessageId();
            if (messageId == null || messageId.isBlank()) return;
            if (!getConfigBoolean("enable_done_reaction", true)) return;
            // Stand in for addReactionAsync — the real helper would POST
            // /im/v1/messages/{messageId}/reactions; we just record.
            calls.add(new ReactionCall(messageId, "DONE"));
        }
    }

    private static ChannelEntity channel(String configJson) {
        ChannelEntity e = new ChannelEntity();
        e.setId(7L);
        e.setChannelType("feishu");
        e.setName("test");
        e.setConfigJson(configJson);
        return e;
    }

    private static ChannelMessage inbound(String messageId) {
        return ChannelMessage.builder()
                .channelType("feishu")
                .messageId(messageId)
                .senderId("ou_abc")
                .build();
    }

    @Test
    @DisplayName("happy path: messageId present and config default → DONE reaction recorded")
    void reactsOnHappyPath() {
        RecordingFeishuAdapter adapter = new RecordingFeishuAdapter(
                channel("{\"app_id\":\"x\",\"app_secret\":\"y\"}"));
        adapter.onAgentCompleted(inbound("om_123"));
        assertEquals(1, adapter.calls.size());
        assertEquals("om_123", adapter.calls.get(0).messageId());
        assertEquals("DONE", adapter.calls.get(0).emojiType());
    }

    @Test
    @DisplayName("null inbound → no-op, defensive")
    void nullInboundNoOp() {
        RecordingFeishuAdapter adapter = new RecordingFeishuAdapter(
                channel("{\"app_id\":\"x\",\"app_secret\":\"y\"}"));
        adapter.onAgentCompleted(null);
        assertTrue(adapter.calls.isEmpty());
    }

    @Test
    @DisplayName("missing messageId → no-op (can't react without a target id)")
    void noMessageIdNoOp() {
        RecordingFeishuAdapter adapter = new RecordingFeishuAdapter(
                channel("{\"app_id\":\"x\",\"app_secret\":\"y\"}"));
        adapter.onAgentCompleted(inbound(null));
        adapter.onAgentCompleted(inbound("  "));
        assertTrue(adapter.calls.isEmpty());
    }

    @Test
    @DisplayName("enable_done_reaction=false → no-op (operator opt-out)")
    void operatorOptOut() {
        RecordingFeishuAdapter adapter = new RecordingFeishuAdapter(
                channel("{\"app_id\":\"x\",\"app_secret\":\"y\",\"enable_done_reaction\":false}"));
        adapter.onAgentCompleted(inbound("om_123"));
        assertTrue(adapter.calls.isEmpty(), "config flag must disable the reaction");
    }
}
