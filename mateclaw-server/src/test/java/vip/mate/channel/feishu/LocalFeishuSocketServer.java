package vip.mate.channel.feishu;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only HTTP endpoint + RFC6455 peer; never contacts Feishu or a model. */
final class LocalFeishuSocketServer implements AutoCloseable {
    final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    final BlockingQueue<Socket> connections = new LinkedBlockingQueue<>();
    final AtomicInteger handshakes = new AtomicInteger();
    final ExecutorService threads = Executors.newCachedThreadPool();
    volatile long handshakeDelayMs;
    volatile boolean rejectHandshake;
    volatile boolean replyToPing = true;
    volatile boolean stallEndpoint;
    final CountDownLatch endpointRequested = new CountDownLatch(1);

    LocalFeishuSocketServer() throws IOException {
        threads.submit(() -> {
            try {
                while (!listener.isClosed()) {
                    Socket socket = listener.accept(); sockets.add(socket);
                    threads.submit(() -> serve(socket));
                }
            } catch (IOException ignored) { }
        });
    }
    String domain() { return "http://127.0.0.1:" + listener.getLocalPort(); }

    private void serve(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                int next = in.read(); if (next < 0) return; header.write(next);
                if (header.size() > 16_384) throw new IOException("oversize request");
            }
            String headers = header.toString(StandardCharsets.US_ASCII);
            if (headers.startsWith("POST ")) {
                endpointRequested.countDown();
                if (stallEndpoint) { while (in.read() >= 0) {} return; }
                String body = "{\"code\":0,\"data\":{\"URL\":\"ws://127.0.0.1:" + listener.getLocalPort()
                        + "/socket?device_id=local&service_id=1\",\"ClientConfig\":{\"ReconnectCount\":0,\"ReconnectInterval\":0,\"ReconnectNonce\":0,\"PingInterval\":30}}}";
                write(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body);
                return;
            }
            if (handshakeDelayMs > 0) Thread.sleep(handshakeDelayMs);
            if (rejectHandshake) { write(socket, "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); return; }
            String key = Arrays.stream(headers.split("\r\n")).filter(s -> s.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                    .findFirst().orElseThrow().split(":",2)[1].trim();
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            write(socket,"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
            handshakes.incrementAndGet(); connections.add(socket);
            while (true) {
                int first=in.read(); if(first<0) return;
                int second=in.read(); if(second<0) return;
                int length=second&127;
                if(length==126) length=(in.read()<<8)|in.read();
                if(length==127) throw new IOException("unexpected test frame length");
                byte[] mask=(second&128)!=0 ? in.readNBytes(4) : new byte[0];
                byte[] payload=in.readNBytes(length);
                for(int i=0;i<payload.length && mask.length==4;i++) payload[i]^=mask[i%4];
                int opcode=first&15;
                if(opcode==8) { frame(socket,8,payload);return; }
                if(opcode==9 && replyToPing) frame(socket,10,payload);
            }
        } catch (Exception ignored) {
            // Tests assert observed handshake/readiness/failure. Peer close is expected.
        } finally { sockets.remove(socket); }
    }
    Socket awaitConnection() throws InterruptedException {
        Socket socket=connections.poll(3,TimeUnit.SECONDS);
        if(socket==null) throw new AssertionError("No WebSocket handshake");
        return socket;
    }
    static void frame(Socket socket,int opcode,byte[] payload) throws IOException {
        synchronized(socket) {
            OutputStream out=socket.getOutputStream();out.write(128|opcode);out.write(payload.length);out.write(payload);out.flush();
        }
    }
    private static void write(Socket socket,String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
    }
    @Override public void close() throws Exception {
        listener.close();for(Socket s:sockets) s.close();threads.shutdownNow();
        if(!threads.awaitTermination(3,TimeUnit.SECONDS)) throw new AssertionError("Fixture threads did not stop");
    }
}
