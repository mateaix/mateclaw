package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.ws.Client;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FeishuWebSocketLifecycleTest {
    @Test void permanentlyClosingAdapterTerminatesSdkThreads() throws Exception {
        ChannelEntity entity = new ChannelEntity(); entity.setId(42L); entity.setConfigJson("{}");
        FeishuChannelAdapter adapter = new FeishuChannelAdapter(entity, mock(ChannelMessageRouter.class), new ObjectMapper());
        Client sdk = new Client.Builder("local-test", "local-test").autoReconnect(false).build();
        var f = Client.class.getDeclaredField("executor"); f.setAccessible(true);
        ExecutorService executor = (ExecutorService) f.get(sdk);
        executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        var c = FeishuChannelAdapter.class.getDeclaredField("wsClient"); c.setAccessible(true); c.set(adapter, new FeishuWebSocketClient(sdk));
        var stop = FeishuChannelAdapter.class.getDeclaredMethod("stopWebSocket"); stop.setAccessible(true);
        try {
            stop.invoke(adapter);
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS), "old SDK executor must terminate on permanent stop");
        } finally { sdk.close(); executor.shutdownNow(); }
    }
}
