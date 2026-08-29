package vip.mate.agent.runtime.dsh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class DshBridgeAuthenticator {
    private final byte[] expectedToken;

    public DshBridgeAuthenticator(String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalArgumentException("bridge token is required");
        }
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    public boolean accepts(String providedToken) {
        if (providedToken == null) return false;
        return MessageDigest.isEqual(expectedToken,
                providedToken.getBytes(StandardCharsets.UTF_8));
    }
}
