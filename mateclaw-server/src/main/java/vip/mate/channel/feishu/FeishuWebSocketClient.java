package vip.mate.channel.feishu;

import com.lark.oapi.okhttp.OkHttpClient;
import com.lark.oapi.okhttp.WebSocket;
import com.lark.oapi.ws.Client;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one oapi-sdk 2.7.1 WebSocket client for its entire lifetime.
 * The SDK's close() does not release its executors and exposes neither its
 * transport nor an HTTP-client builder. Keep this version-bound compatibility
 * bridge here; its field contract and real socket lifecycle are regression tested.
 * Only the adapter reconnects (SDK autoReconnect must be false).
 */
final class FeishuWebSocketClient implements AutoCloseable {
    private static final Field EXECUTOR = field("executor", ExecutorService.class);
    private static final Field HTTP_CLIENT = field("httpClient", OkHttpClient.class);
    private static final Field CONNECTION = field("conn", WebSocket.class);

    private final Client client;
    private final ExecutorService executor;
    private final OkHttpClient httpClient;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private volatile Thread starter;
    private volatile boolean ready;

    FeishuWebSocketClient(Client client) {
        this(client, 20_000);
    }

    // Short protocol ping interval is used only by local socket tests.
    FeishuWebSocketClient(Client client, long pingIntervalMs) {
        this.client = client;
        this.executor = (ExecutorService) read(EXECUTOR, client);
        OkHttpClient original = (OkHttpClient) read(HTTP_CLIENT, client);
        this.httpClient = original.newBuilder()
                .pingInterval(pingIntervalMs, TimeUnit.MILLISECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build();
        try {
            HTTP_CLIENT.set(client, httpClient);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unsupported Feishu SDK HTTP client contract", e);
        }
    }

    void start() throws Exception {
        synchronized (lifecycleLock) {
            if (closed.get()) throw new IllegalStateException("WebSocket client already closed");
            starter = Thread.currentThread();
            try {
                // start() initiates an asynchronous handshake; returning is NOT a disconnect.
                client.start();
                client.awaitReady(10_000);
                if (closed.get()) throw new IllegalStateException("WebSocket client closed during startup");
                ready = true;
            } catch (Exception e) {
                close();
                throw e;
            } finally {
                starter = null;
            }
        }
    }

    boolean isConnected() {
        // SDK 2.7.1 conn is non-volatile; disconnect() clears it under this monitor.
        synchronized (client) {
            return ready && !closed.get() && read(CONNECTION, client) != null;
        }
    }

    @Override
    public void close() {
        closed.set(true);
        ready = false;
        // Unblock endpoint requests/handshake before acquiring the lifecycle lock.
        httpClient.dispatcher().cancelAll();
        Thread thread = starter;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        synchronized (lifecycleLock) {
            WebSocket connection = null;
            try {
                synchronized (client) {
                    connection = (WebSocket) read(CONNECTION, client);
                    client.close();
                }
            } finally {
                try {
                    // SDK close() only enqueues a graceful close. Permanent disposal must
                    // abort the socket even when the peer never completes the handshake.
                    // Cancel outside the SDK monitor so failure callbacks can acquire it.
                    if (connection != null) connection.cancel();
                } finally {
                    executor.shutdownNow();
                    httpClient.dispatcher().executorService().shutdownNow();
                    httpClient.connectionPool().evictAll();
                }
            }
        }
    }

    private static Field field(String name, Class<?> expectedType) {
        try {
            Field field = Client.class.getDeclaredField(name);
            if (!expectedType.isAssignableFrom(field.getType())) {
                throw new IllegalStateException("Unsupported Feishu SDK field: " + name);
            }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Feishu SDK lifecycle contract: " + name, e);
        }
    }

    private static Object read(Field field, Client client) {
        try {
            return field.get(client);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access Feishu SDK lifecycle resource", e);
        }
    }
}
