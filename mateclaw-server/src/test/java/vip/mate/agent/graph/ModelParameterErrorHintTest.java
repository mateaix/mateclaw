package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ModelParameterErrorHintTest {
    @Test
    void unsupportedTokenParameterHasActionableHintWithoutEchoingSecrets() {
        String body = "{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead. token=private-secret\"}}";
        var error = WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        String hint = ReflectionTestUtils.invokeMethod(NodeStreamingChatHelper.class,
                "extractUserFriendlyError", new IllegalStateException("400 Bad Request", error));
        assertNotNull(hint);
        assertTrue(hint.contains("max_completion_tokens"));
        assertFalse(hint.contains("private-secret"));
        assertFalse(hint.contains("file format"));
    }

    @Test
    void unsupportedSamplingParameterIsNotAnImageError() {
        String hint = ReflectionTestUtils.invokeMethod(NodeStreamingChatHelper.class,
                "extractUserFriendlyError", new IllegalStateException("400 Bad Request: unsupported parameter: 'temperature'"));
        assertNotNull(hint);
        assertTrue(hint.contains("temperature"));
        assertFalse(hint.contains("file format"));
    }

    @Test
    void actualUnsupportedImageFormatKeepsExistingHint() {
        String hint = ReflectionTestUtils.invokeMethod(NodeStreamingChatHelper.class,
                "extractUserFriendlyError", new IllegalStateException("unsupported image format"));
        assertNotNull(hint);
        assertTrue(hint.contains("PNG/JPG"));
    }
}
