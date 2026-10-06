package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.okhttp.OkHttpClient;
import com.lark.oapi.ws.Client;
import org.junit.jupiter.api.Test;
import vip.mate.channel.AbstractChannelAdapter.ConnectionState;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.model.ChannelEntity;

import java.net.Socket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FeishuWebSocketTransportTest {
    static Object field(Object target, String name) throws Exception {
        var f=Client.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static void await(BooleanSupplier predicate) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!predicate.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
        assertTrue(predicate.getAsBoolean(),"condition did not become true");
    }
    static class TestAdapter extends FeishuChannelAdapter {
        final LocalFeishuSocketServer server;
        final List<Client> sdks=new CopyOnWriteArrayList<>();
        TestAdapter(LocalFeishuSocketServer server) {
            super(entity(),mock(ChannelMessageRouter.class),new ObjectMapper());this.server=server;
            backoff=new ExponentialBackoff(50,100,2,-1);
        }
        static ChannelEntity entity() {
            ChannelEntity e=new ChannelEntity();e.setId(73L);e.setName("local transport");
            e.setConfigJson("{\"app_id\":\"local\",\"app_secret\":\"local\"}");return e;
        }
        @Override protected void doStart() { startWebSocket("local","local"); }
        @Override FeishuWebSocketClient createWsClient(String id,String secret) {
            Client sdk=new Client.Builder(id,secret).domain(server.domain()).autoReconnect(false).build();
            sdks.add(sdk);return new FeishuWebSocketClient(sdk,100);
        }
    }
    static void assertReleased(Client sdk) throws Exception {
        ExecutorService pool=(ExecutorService)field(sdk,"executor");
        OkHttpClient http=(OkHttpClient)field(sdk,"httpClient");
        assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));
        assertTrue(http.dispatcher().executorService().awaitTermination(3,TimeUnit.SECONDS));
        assertEquals(0,http.connectionPool().connectionCount());
    }
    @Test void delayedHandshakeStaysReconnectingThenUsesOnlyOneClient() throws Exception {
        try(var server=new LocalFeishuSocketServer()) {
            server.handshakeDelayMs=250;
            var adapter=new TestAdapter(server);
            try {
                adapter.start();assertEquals(ConnectionState.RECONNECTING,adapter.getConnectionState().get());
                await(()->adapter.getConnectionState().get()==ConnectionState.CONNECTED);
                adapter.getLastEventTimeMs().set(System.currentTimeMillis()-Duration.ofHours(3).toMillis());
                for(int i=0;i<10;i++) adapter.checkWebSocketTransport();
                assertEquals(1,adapter.sdks.size());assertEquals(1,server.handshakes.get());
            } finally { adapter.stop(); }
            assertReleased(adapter.sdks.getFirst());
        }
    }
    @Test void closeBetweenHandshakeAndSuccessDoesNotGetStuckReconnecting() throws Exception {
        try(var server=new LocalFeishuSocketServer()) {
            var successReached=new CountDownLatch(1);
            var allowSuccess=new CountDownLatch(1);
            var adapter=new TestAdapter(server) {
                @Override protected void onReconnectSuccess() {
                    successReached.countDown();
                    try { assertTrue(allowSuccess.await(3,TimeUnit.SECONDS)); }
                    catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    super.onReconnectSuccess();
                }
            };
            try {
                adapter.start();assertTrue(successReached.await(3,TimeUnit.SECONDS));
                server.awaitConnection().close();
                await(()->{try{return field(adapter.sdks.getFirst(),"conn")==null;}catch(Exception e){throw new RuntimeException(e);}});
                allowSuccess.countDown();
                await(()->adapter.sdks.size()==2 && adapter.getConnectionState().get()==ConnectionState.CONNECTED);
                assertReleased(adapter.sdks.getFirst());
            } finally { allowSuccess.countDown();adapter.stop(); }
        }
    }
    @Test void tcpAbortReconnectsOnceAndReleasesOldClient() throws Exception { reconnect(false); }
    @Test void peerCloseReconnectsOnceAndReleasesOldClient() throws Exception { reconnect(true); }
    private void reconnect(boolean graceful) throws Exception {
        try(var server=new LocalFeishuSocketServer()) {
            var adapter=new TestAdapter(server);
            try {
                adapter.start();await(()->adapter.getConnectionState().get()==ConnectionState.CONNECTED);
                Socket old=server.awaitConnection();
                if(graceful) LocalFeishuSocketServer.frame(old,8,new byte[]{3,(byte)232});
                else old.setSoLinger(true,0);
                old.close();
                await(()->{adapter.checkWebSocketTransport();return adapter.sdks.size()==2 && adapter.getConnectionState().get()==ConnectionState.CONNECTED;});
                assertEquals(2,server.handshakes.get());assertReleased(adapter.sdks.getFirst());
            } finally { adapter.stop(); }
            for(Client sdk:adapter.sdks) assertReleased(sdk);
        }
    }
    @Test void protocolPingDetectsHalfOpenSocketWithoutBusinessMessages() throws Exception {
        try(var server=new LocalFeishuSocketServer()) {
            var adapter=new TestAdapter(server);
            try {
                adapter.start();await(()->adapter.getConnectionState().get()==ConnectionState.CONNECTED);
                server.replyToPing=false;
                await(()->{adapter.checkWebSocketTransport();return adapter.sdks.size()>=2;});
                server.replyToPing=true;
                await(()->adapter.getConnectionState().get()==ConnectionState.CONNECTED);
                assertReleased(adapter.sdks.getFirst());
            } finally { adapter.stop(); }
        }
    }
    @Test void rejectedHandshakeIsRetriedWithoutLeakingTheFailedClient() throws Exception {
        try(var server=new LocalFeishuSocketServer()) {
            server.rejectHandshake=true;
            var adapter=new TestAdapter(server);
            try {
                adapter.start();await(()->adapter.sdks.size()>=2);
                server.rejectHandshake=false;
                await(()->adapter.getConnectionState().get()==ConnectionState.CONNECTED);
                assertReleased(adapter.sdks.getFirst());
            } finally { adapter.stop(); }
            for(Client sdk:adapter.sdks) assertReleased(sdk);
        }
    }
    @Test void repeatedRealStartStopDoesNotAccumulateExecutorThreads() throws Exception {
        try(var server=new LocalFeishuSocketServer()) {
            for(int i=0;i<25;i++) {
                var adapter=new TestAdapter(server);
                try { adapter.start();await(()->adapter.getConnectionState().get()==ConnectionState.CONNECTED); }
                finally { adapter.stop(); }
                assertEquals(1,adapter.sdks.size());assertReleased(adapter.sdks.getFirst());
            }
            assertEquals(25,server.handshakes.get());
        }
    }
    @Test void stopDuringDelayedHandshakeReturnsWithinManagerDeadline() throws Exception {
        try(var server=new LocalFeishuSocketServer()) {
            server.handshakeDelayMs=2_000;
            var adapter=new TestAdapter(server);adapter.start();
            assertTrue(server.endpointRequested.await(2,TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(3),adapter::stop);
            Thread.sleep(100);
            assertFalse(adapter.isRunning());assertEquals(ConnectionState.DISCONNECTED,adapter.getConnectionState().get());
            for(Client sdk:adapter.sdks) assertReleased(sdk);
        }
    }
    @Test void stopCancelsStalledEndpointRequest() throws Exception {
        try(var server=new LocalFeishuSocketServer()) {
            server.stallEndpoint=true;
            var adapter=new TestAdapter(server);adapter.start();
            assertTrue(server.endpointRequested.await(2,TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(3),adapter::stop);
            assertFalse(adapter.isRunning());for(Client sdk:adapter.sdks) assertReleased(sdk);
        }
    }
    @Test void closeBeforeStartCannotReopenSdkOrCreateThreads() throws Exception {
        try(var server=new LocalFeishuSocketServer()) {
            Client sdk=new Client.Builder("local","local").domain(server.domain()).autoReconnect(false).build();
            var client=new FeishuWebSocketClient(sdk);client.close();client.close();
            assertThrows(IllegalStateException.class,client::start);
            assertEquals(0,server.handshakes.get());assertReleased(sdk);
        }
    }
}
