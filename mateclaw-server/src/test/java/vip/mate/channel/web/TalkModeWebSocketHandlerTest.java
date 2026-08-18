package vip.mate.channel.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TalkModeWebSocketHandlerTest {

    @Test
    @DisplayName("copyPayload respects a pooled ByteBuffer's position and limit")
    void copyPayload_respectsReadableRange() {
        ByteBuffer pooled = ByteBuffer.wrap(new byte[]{99, 98, 1, 2, 3, 97});
        pooled.position(2);
        pooled.limit(5);

        assertArrayEquals(new byte[]{1, 2, 3}, TalkModeWebSocketHandler.copyPayload(pooled));
    }
}
