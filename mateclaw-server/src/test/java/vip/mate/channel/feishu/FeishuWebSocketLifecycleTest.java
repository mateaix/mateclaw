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
    @Test
    void transportStatusReadsUnderSdkMonitor() throws Exception {
        try (var server = new LocalFeishuSocketServer()) {
            Client sdk = new Client.Builder("local", "local")
                    .domain(server.domain()).autoReconnect(false).build();
            try (var client = new FeishuWebSocketClient(sdk)) {
                client.start();
                var result = new java.util.concurrent.atomic.AtomicReference<Boolean>();
                Thread observer = new Thread(() -> result.set(client.isConnected()), "transport-status-reader");
                try {
                    synchronized (sdk) {
                        observer.start();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                        while (observer.getState() != Thread.State.BLOCKED
                                && observer.getState() != Thread.State.TERMINATED
                                && System.nanoTime() < deadline) {
                            Thread.sleep(5);
                        }
                        assertEquals(Thread.State.BLOCKED, observer.getState(),
                                "conn is non-volatile: reads must acquire the monitor used by SDK disconnect()");
                        assertNull(result.get(), "status read must not bypass the SDK monitor");
                    }
                } finally {
                    observer.join(3_000);
                }
                assertFalse(observer.isAlive());
                assertEquals(Boolean.TRUE, result.get());
            }
        }
    }
    @Test
    void permanentCloseCancelsTransportWithoutWaitingForPeerClose() throws Exception {
        Client sdk = new Client.Builder("local", "local").autoReconnect(false).build();
        var socket = mock(com.lark.oapi.okhttp.WebSocket.class);
        var connection = Client.class.getDeclaredField("conn");
        connection.setAccessible(true);
        connection.set(sdk, socket);
        try (var client = new FeishuWebSocketClient(sdk)) {
            client.close();
            org.mockito.Mockito.verify(socket).cancel();
        }
    }
    @Test
    void sdkCloseFailureStillCancelsSocketAndReleasesExecutors() throws Exception {
        Client sdk = new Client.Builder("local", "local").autoReconnect(false).build();
        var socket = mock(com.lark.oapi.okhttp.WebSocket.class);
        org.mockito.Mockito.when(socket.close(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("close failed"));
        var connection = Client.class.getDeclaredField("conn");
        connection.setAccessible(true);
        connection.set(sdk, socket);
        try (var client = new FeishuWebSocketClient(sdk)) {
            assertThrows(IllegalStateException.class, client::close);
            org.mockito.Mockito.verify(socket).cancel();
            FeishuWebSocketTransportTest.assertReleased(sdk);
        }
    }
}
