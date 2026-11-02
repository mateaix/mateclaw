package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import vip.mate.llm.model.ModelProviderEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiAzureWirePolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void stripsOrphanToolOptionsAndEmptyToolsWithoutMutatingSource() throws Exception {
        for (String tools : List.of("", ",\"tools\":[]")) {
            ObjectNode source = (ObjectNode) JSON.readTree("{\"model\":\"gpt-4o\",\"messages\":[],"
                    + "\"tool_choice\":\"none\",\"parallel_tool_calls\":false" + tools + "}");
            var out = OpenAiRequestRewriter.rewrite(source, provider(), false);
            assertFalse(out.has("tools"));
            assertFalse(out.has("tool_choice"));
            assertFalse(out.has("parallel_tool_calls"));
            assertTrue(source.has("tool_choice"));
        }
    }

    @Test
    void preservesToolDisableGuardWhenToolsExist() throws Exception {
        ObjectNode source = (ObjectNode) JSON.readTree("""
                {"model":"gpt-4o","messages":[],"tools":[{"type":"function","function":{"name":"lookup"}}],
                 "tool_choice":"none","parallel_tool_calls":false}
                """);
        assertEquals(source, OpenAiRequestRewriter.rewrite(source, provider(), false));
    }

    @Test
    void keepsOptionsForInjectedKimiSearchTool() throws Exception {
        ObjectNode source = (ObjectNode) JSON.readTree("{\"model\":\"kimi-k2\",\"messages\":[],\"tool_choice\":\"none\"}");
        var out = OpenAiRequestRewriter.rewrite(source, provider(), true);
        assertEquals("none", out.path("tool_choice").asText());
        assertEquals(1, out.path("tools").size());
    }

    @Test
    void unifiedAzureDropsSdkVersionButPreservesOtherParameters() throws Exception {
        var url = wireUrl("https://demo.openai.azure.com/openai/v1/chat/completions",
                "https://demo.openai.azure.com/openai/deployments/gpt/chat/completions?api-version=2025-01-01&trace=a&trace=b");
        assertNull(url.queryParameter("api-version"));
        assertEquals(List.of("a", "b"), url.queryParameterValues("trace"));
    }

    @Test
    void legacyAzureKeepsVersion() throws Exception {
        var url = wireUrl("https://demo.openai.azure.com/openai/deployments/gpt/chat/completions",
                "https://demo.openai.azure.com/openai/deployments/gpt/chat/completions?api-version=2025-01-01");
        assertEquals("2025-01-01", url.queryParameter("api-version"));
    }

    @Test
    void legacyConfiguredVersionWinsWithoutDuplicate() throws Exception {
        var url = wireUrl("https://demo.openai.azure.com/openai/deployments/gpt/chat/completions?api-version=custom",
                "https://demo.openai.azure.com/openai/deployments/gpt/chat/completions?api-version=sdk");
        assertEquals(List.of("custom"), url.queryParameterValues("api-version"));
    }

    @Test
    void nonAzureGatewayKeepsVersionParameter() throws Exception {
        var url = wireUrl("https://gateway.example/v1/chat/completions",
                "https://gateway.example/v1/chat/completions?api-version=custom");
        assertEquals("custom", url.queryParameter("api-version"));
    }

    private static HttpUrl wireUrl(String endpoint, String original) throws Exception {
        AtomicReference<HttpUrl> sent = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new OpenAiCompatibleHttpInterceptor(provider(), endpoint, Map.of(), false, Duration.ofSeconds(3)))
                .addInterceptor(chain -> {
                    sent.set(chain.request().url());
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                            .message("OK").body(ResponseBody.create("{}", MediaType.get("application/json"))).build();
                }).build();
        Request request = new Request.Builder().url(original)
                .post(RequestBody.create("{\"model\":\"gpt-4o\",\"messages\":[]}", MediaType.get("application/json"))).build();
        try (Response ignored = client.newCall(request).execute()) { return sent.get(); }
    }

    private static ModelProviderEntity provider() {
        var provider = new ModelProviderEntity();
        provider.setProviderId("azure-openai");
        return provider;
    }
}
