package vip.mate.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.channel.model.ChannelEntity;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class WeComUploadAckTest {
    @Test void successfulChunkAckMayOmitBody() throws Exception { check("{\"errcode\":0,\"headers\":{\"req_id\":\"chunk\"}}", false); }
    @Test void successfulChunkAckMayHaveNullBody() throws Exception { check("{\"errcode\":0,\"headers\":{\"req_id\":\"chunk\"},\"body\":null}", false); }
    @Test void rejectedChunkAckStillFails() throws Exception { check("{\"errcode\":400,\"headers\":{\"req_id\":\"chunk\"}}", true); }
    @SuppressWarnings("unchecked")
    private void check(String json, boolean rejected) throws Exception {
        ChannelEntity entity = new ChannelEntity();
        entity.setId(1L); entity.setChannelType("wecom"); entity.setConfigJson("{}");
        var adapter = new WeComChannelAdapter(entity, null, new ObjectMapper(), null, null, null);
        var field = WeComChannelAdapter.class.getDeclaredField("pendingAcks"); field.setAccessible(true);
        var pending = (Map<String, CompletableFuture<Map<String,Object>>>) field.get(adapter);
        var ack = new CompletableFuture<Map<String,Object>>(); pending.put("chunk", ack);
        var method = WeComChannelAdapter.class.getDeclaredMethod("handleWebSocketFrame", String.class); method.setAccessible(true);
        method.invoke(adapter, json);
        assertTrue(ack.isDone()); assertEquals(rejected, ack.isCompletedExceptionally());
        assertFalse(pending.containsKey("chunk"));
        if (!rejected) assertEquals(0, ack.join().get("errcode"));
    }
}
