package vip.mate.agent.runtime.dsh;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public final class DshBridgeConnection implements AutoCloseable {
    private static final int MAX_LINE_BYTES = 1_048_576;

    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final DshBridgeProtocol protocol;
    private final DshBridgeAuthenticator authenticator;
    private boolean authenticated;

    public DshBridgeConnection(InputStream input, OutputStream output,
                               DshBridgeProtocol protocol,
                               DshBridgeAuthenticator authenticator) {
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.protocol = protocol;
        this.authenticator = authenticator;
    }

    public boolean authenticate(String token) {
        authenticated = authenticator.accepts(token);
        return authenticated;
    }

    public DshBridgeMessage receive() throws IOException {
        requireAuthenticated();
        String line = reader.readLine();
        if (line == null) throw new IOException("DSH bridge closed");
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            throw new IOException("DSH bridge message exceeds size limit");
        }
        return protocol.decode(line);
    }

    public void send(DshBridgeMessage message) throws IOException {
        requireAuthenticated();
        String encoded = protocol.encode(message);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            throw new IOException("DSH bridge message exceeds size limit");
        }
        writer.write(encoded);
        writer.flush();
    }

    private void requireAuthenticated() throws IOException {
        if (!authenticated) throw new IOException("DSH bridge authentication required");
    }

    @Override
    public void close() throws IOException {
        reader.close();
        writer.close();
        authenticated = false;
    }
}
