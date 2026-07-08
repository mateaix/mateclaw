package vip.mate.llm.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Context-window probe for self-hosted OpenAI-compatible servers (vLLM,
 * LM Studio, llama.cpp server, MLX, …) via {@code GET /v1/models}.
 *
 * <p>vLLM exposes {@code max_model_len} per model entry; other stacks expose
 * {@code context_length} or {@code max_context_length}. Only endpoints whose
 * host is local / private are probed — {@link LocalEndpoints#isLocal} gates
 * that, so no probe traffic is ever sent to a cloud provider.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleContextProbe implements LocalContextProbe {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ContextProbeProperties properties;

    @Override
    public boolean supports(ModelProviderEntity provider, ModelConfigEntity model) {
        if (provider == null || model == null) {
            return false;
        }
        // Ollama has a richer native endpoint handled by its dedicated probe.
        if ("ollama".equalsIgnoreCase(provider.getProviderId())) {
            return false;
        }
        if (ModelProtocol.fromChatModel(provider.getChatModel()) != ModelProtocol.OPENAI_COMPATIBLE) {
            return false;
        }
        return LocalEndpoints.isLocal(provider.getBaseUrl());
    }

    @Override
    public Optional<Integer> probeContextLength(ModelProviderEntity provider, ModelConfigEntity model) {
        String baseUrl = normalizeBaseUrl(provider.getBaseUrl());
        try {
            RestClient client = RestClient.builder()
                    .requestFactory(requestFactory())
                    .baseUrl(baseUrl)
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build();
            RestClient.RequestHeadersSpec<?> spec = client.get().uri("/v1/models");
            String apiKey = provider.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
            }
            String body = spec.retrieve().body(String.class);
            OptionalInt parsed = parseModelsResponse(body, model.getModelName());
            return parsed.isPresent() ? Optional.of(parsed.getAsInt()) : Optional.empty();
        } catch (Exception e) {
            log.debug("[ContextProbe] OpenAI-compatible probe failed for {} at {}: {}",
                    model.getModelName(), baseUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Find the entry matching {@code modelName} in a {@code /v1/models}
     * response and read its context-length field. Package-private for tests.
     */
    static OptionalInt parseModelsResponse(String body, String modelName) {
        if (body == null || body.isBlank() || modelName == null || modelName.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            JsonNode data = OBJECT_MAPPER.readTree(body).path("data");
            if (!data.isArray()) {
                return OptionalInt.empty();
            }
            for (JsonNode node : data) {
                if (!modelName.equals(node.path("id").asText(""))) {
                    continue;
                }
                for (String field : new String[]{"max_model_len", "context_length", "max_context_length"}) {
                    int value = node.path(field).asInt(0);
                    if (value > 0) {
                        return OptionalInt.of(value);
                    }
                }
                return OptionalInt.empty();
            }
        } catch (Exception e) {
            return OptionalInt.empty();
        }
        return OptionalInt.empty();
    }

    private JdkClientHttpRequestFactory requestFactory() {
        // HTTP/1.1 pinned: Uvicorn-style local stacks reject the h2c upgrade.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        return factory;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }
}
