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
import vip.mate.llm.model.ModelProviderEntity;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Context-window probe for Ollama servers via the native model-metadata
 * endpoint ({@code POST /api/show}).
 *
 * <p>Resolution order within the response:
 * <ol>
 *   <li>{@code num_ctx} from the modelfile parameters — the window the server
 *       actually serves with;</li>
 *   <li>the architecture's {@code *.context_length} from {@code model_info} —
 *       an upper bound when no explicit {@code num_ctx} is set.</li>
 * </ol>
 * Explicit per-model configuration always wins upstream in the resolver; this
 * probe only fills the gap when the user configured nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaContextProbe implements LocalContextProbe {

    static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";

    private static final Pattern NUM_CTX_PATTERN = Pattern.compile("num_ctx\\s+(\\d{3,8})");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ContextProbeProperties properties;

    @Override
    public boolean supports(ModelProviderEntity provider, ModelConfigEntity model) {
        return provider != null && model != null
                && "ollama".equalsIgnoreCase(provider.getProviderId());
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
            // Newer Ollama accepts "model", older releases used "name" — send both.
            String body = client.post()
                    .uri("/api/show")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model.getModelName(), "name", model.getModelName()))
                    .retrieve()
                    .body(String.class);
            OptionalInt parsed = parseShowResponse(body);
            return parsed.isPresent() ? Optional.of(parsed.getAsInt()) : Optional.empty();
        } catch (Exception e) {
            log.debug("[ContextProbe] Ollama probe failed for {} at {}: {}",
                    model.getModelName(), baseUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse an {@code /api/show} response body. Package-private for tests.
     */
    static OptionalInt parseShowResponse(String body) {
        if (body == null || body.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            // Serving-time knob wins: it is what the server actually allocates.
            Matcher numCtx = NUM_CTX_PATTERN.matcher(root.path("parameters").asText(""));
            if (numCtx.find()) {
                int value = Integer.parseInt(numCtx.group(1));
                if (value > 0) {
                    return OptionalInt.of(value);
                }
            }
            JsonNode modelInfo = root.path("model_info");
            if (modelInfo.isObject()) {
                for (Iterator<String> it = modelInfo.fieldNames(); it.hasNext(); ) {
                    String field = it.next();
                    if (field.endsWith(".context_length")) {
                        int value = modelInfo.path(field).asInt(0);
                        if (value > 0) {
                            return OptionalInt.of(value);
                        }
                    }
                }
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

    /** Ollama providers are often saved with the OpenAI-compatible {@code /v1} suffix — strip it. */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
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
