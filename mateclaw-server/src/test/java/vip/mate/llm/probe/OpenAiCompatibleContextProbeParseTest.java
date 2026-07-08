package vip.mate.llm.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parse-level tests for {@link OpenAiCompatibleContextProbe} against
 * {@code /v1/models} response shapes — no HTTP involved.
 */
class OpenAiCompatibleContextProbeParseTest {

    @Test
    @DisplayName("vLLM exposes max_model_len per model entry")
    void vllmMaxModelLen() {
        String body = """
                {"object": "list", "data": [
                  {"id": "Qwen/Qwen2.5-7B-Instruct", "object": "model", "max_model_len": 32768},
                  {"id": "other-model", "object": "model", "max_model_len": 4096}
                ]}
                """;
        assertEquals(OptionalInt.of(32768),
                OpenAiCompatibleContextProbe.parseModelsResponse(body, "Qwen/Qwen2.5-7B-Instruct"));
    }

    @Test
    @DisplayName("context_length / max_context_length variants are read too")
    void contextLengthVariants() {
        String contextLength = "{\"data\": [{\"id\": \"m1\", \"context_length\": 16384}]}";
        assertEquals(OptionalInt.of(16384),
                OpenAiCompatibleContextProbe.parseModelsResponse(contextLength, "m1"));

        String maxContextLength = "{\"data\": [{\"id\": \"m2\", \"max_context_length\": 8192}]}";
        assertEquals(OptionalInt.of(8192),
                OpenAiCompatibleContextProbe.parseModelsResponse(maxContextLength, "m2"));
    }

    @Test
    @DisplayName("unknown model id or missing fields → empty")
    void unknownModelOrMissingField() {
        String body = "{\"data\": [{\"id\": \"m1\", \"max_model_len\": 32768}]}";
        assertTrue(OpenAiCompatibleContextProbe.parseModelsResponse(body, "not-there").isEmpty());
        assertTrue(OpenAiCompatibleContextProbe.parseModelsResponse(
                "{\"data\": [{\"id\": \"m1\"}]}", "m1").isEmpty());
        assertTrue(OpenAiCompatibleContextProbe.parseModelsResponse("not json", "m1").isEmpty());
        assertTrue(OpenAiCompatibleContextProbe.parseModelsResponse(null, "m1").isEmpty());
    }

    @Test
    @DisplayName("local endpoint heuristic: loopback and private ranges yes, public hosts no")
    void localEndpointHeuristic() {
        assertTrue(LocalEndpoints.isLocal("http://localhost:8000"));
        assertTrue(LocalEndpoints.isLocal("http://127.0.0.1:8000/v1"));
        assertTrue(LocalEndpoints.isLocal("http://192.168.1.20:1234"));
        assertTrue(LocalEndpoints.isLocal("http://10.0.0.3:8000"));
        assertTrue(LocalEndpoints.isLocal("http://172.16.0.9:8000"));
        assertTrue(LocalEndpoints.isLocal("http://host.docker.internal:11434"));
        assertTrue(LocalEndpoints.isLocal("http://mymac.local:1234"));

        assertFalse(LocalEndpoints.isLocal("https://api.openai.com/v1"));
        assertFalse(LocalEndpoints.isLocal("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertFalse(LocalEndpoints.isLocal("http://172.32.0.1:8000")); // outside 172.16/12
        assertFalse(LocalEndpoints.isLocal(null));
        assertFalse(LocalEndpoints.isLocal(""));
        assertFalse(LocalEndpoints.isLocal("not a url"));
    }
}
