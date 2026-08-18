package vip.mate.tts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsResponseDiagnosticsTest {

    @Test
    @DisplayName("failureMessage includes provider, endpoint, status and sanitized body snippet")
    void failureMessageIncludesActionableDiagnostics() {
        String message = TtsResponseDiagnostics.failureMessage(
                "DashScope TTS",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/audio/speech",
                400,
                """
                        {"code":"InvalidParameter","message":"voice does not exist"}
                        """);

        assertTrue(message.contains("DashScope TTS 失败"));
        assertTrue(message.contains("endpoint=https://dashscope.aliyuncs.com/compatible-mode/v1/audio/speech"));
        assertTrue(message.contains("status=400"));
        assertTrue(message.contains("body={\"code\":\"InvalidParameter\",\"message\":\"voice does not exist\"}"));
    }

    @Test
    @DisplayName("failureMessage truncates long response bodies")
    void failureMessageTruncatesLongBodies() {
        String body = "x".repeat(800);

        String message = TtsResponseDiagnostics.failureMessage(
                "OpenAI TTS",
                "https://api.openai.com/v1/audio/speech",
                500,
                body);

        assertTrue(message.contains("OpenAI TTS 失败"));
        assertTrue(message.contains("status=500"));
        assertTrue(message.contains("body="));
        assertTrue(message.endsWith("..."));
        assertTrue(message.length() < 420);
    }

    @Test
    @DisplayName("snippet redacts bearer tokens and collapses whitespace")
    void snippetRedactsSecretsAndCollapsesWhitespace() {
        String snippet = TtsResponseDiagnostics.snippet("""
                Authorization: Bearer sk-abcdef123456
                gateway    failed
                """);

        assertEquals("Authorization: Bearer [REDACTED] gateway failed", snippet);
        assertFalse(snippet.contains("sk-abcdef"));
    }
}
