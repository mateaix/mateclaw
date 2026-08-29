package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DshBridgeProtocolTest {
    private final DshBridgeProtocol protocol = new DshBridgeProtocol(new ObjectMapper());

    @Test
    void requestRoundTripsAsJsonLine() {
        DshBridgeMessage request = DshBridgeMessage.request(
                "7", "session/open", Map.of("sessionId", "s-1"));

        DshBridgeMessage decoded = protocol.decode(protocol.encode(request));

        assertEquals(request, decoded);
        assertFalse(protocol.isNotification(decoded));
    }

    @Test
    void notificationHasNoRequestId() {
        DshBridgeMessage notification = DshBridgeMessage.notification(
                "tool/cancel", Map.of("callId", "call-1"));

        assertTrue(protocol.isNotification(notification));
        assertEquals(notification, protocol.decode(protocol.encode(notification)));
    }

    @Test
    void tokenAuthenticatorAcceptsOnlyExactToken() {
        DshBridgeAuthenticator authenticator = new DshBridgeAuthenticator("secret");

        assertTrue(authenticator.accepts("secret"));
        assertFalse(authenticator.accepts("Secret"));
        assertFalse(authenticator.accepts(null));
    }

    @Test
    void lineConnectionRequiresAuthentication() throws Exception {
        DshBridgeMessage message = DshBridgeMessage.notification("ready", Map.of());
        ByteArrayInputStream input = new ByteArrayInputStream(protocol.encode(message)
                .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DshBridgeConnection connection = new DshBridgeConnection(
                input, output, protocol, new DshBridgeAuthenticator("secret"));

        assertFalse(connection.authenticate("wrong"));
        assertTrue(connection.authenticate("secret"));
        assertEquals(message, connection.receive());
        connection.send(message);
        assertEquals(protocol.encode(message), output.toString(StandardCharsets.UTF_8));
        connection.close();
    }

    @Test
    void malformedMessagesAndUnknownMethodsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> protocol.decode("{}"));
        assertThrows(IllegalArgumentException.class, () -> protocol.decode("not-json"));
        assertFalse(DshBridgeMethods.isSupported("unknown/method"));
        assertTrue(DshBridgeMethods.isSupported("session/prompt"));
    }
}
