package vip.mate.wiki.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixture-driven {@link ChatModel} mock for wiki tests.
 *
 * <p>Loads a JSON document of {@code prompt-prefix → canned-response} pairs
 * from {@code classpath:fixtures/llm-responses.json}. When asked, the mock
 * looks at the first line of the user's prompt and returns the response of
 * the longest matching prefix; if nothing matches, the {@code __default__}
 * fixture is used (or, absent that, a generic placeholder).
 *
 * <p>Construction:
 * <pre>{@code
 *   ChatModel mock = MockLlmChatModel.fromClasspath("/fixtures/llm-responses.json");
 *   ChatResponse resp = mock.call(new Prompt("Generate a structured analysis ..."));
 * }</pre>
 *
 * <p>Tests can also pass an explicit {@code Map<String,String>} (skipping the
 * JSON load) to keep fixtures co-located with the test class.
 *
 * @author MateClaw Team
 */
public class MockLlmChatModel implements ChatModel {

    private static final String DEFAULT_KEY = "__default__";
    private static final String FALLBACK_RESPONSE = "[mock] no fixture matched";

    private final Map<String, String> fixtures;

    public MockLlmChatModel(Map<String, String> fixtures) {
        // Preserve insertion order — longest-prefix match honors user's intent.
        this.fixtures = new LinkedHashMap<>(fixtures);
    }

    /** Loads fixtures from the given classpath resource (e.g. {@code "/fixtures/llm-responses.json"}). */
    public static MockLlmChatModel fromClasspath(String resourcePath) {
        Map<String, String> fixtures = new LinkedHashMap<>();
        try (InputStream is = Objects.requireNonNull(
                MockLlmChatModel.class.getResourceAsStream(resourcePath),
                "Fixture resource not found: " + resourcePath)) {
            JsonNode root = new ObjectMapper().readTree(is);
            root.fields().forEachRemaining(e -> fixtures.put(e.getKey(), e.getValue().asText()));
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("Failed to load LLM fixture file: " + resourcePath, e);
        }
        return new MockLlmChatModel(fixtures);
    }

    /** Convenience for tests that want an empty mock that always returns the fallback string. */
    public static MockLlmChatModel empty() {
        return new MockLlmChatModel(new HashMap<>());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String body = matchResponse(prompt);
        return new ChatResponse(List.of(
                new Generation(new AssistantMessage(body),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    /** Picks the longest fixture key that prefixes the prompt's first instruction line. */
    private String matchResponse(Prompt prompt) {
        if (prompt.getInstructions().isEmpty()) {
            return fixtures.getOrDefault(DEFAULT_KEY, FALLBACK_RESPONSE);
        }
        String text = prompt.getInstructions().get(0).getText();
        if (text == null) {
            return fixtures.getOrDefault(DEFAULT_KEY, FALLBACK_RESPONSE);
        }
        String firstLine = text.split("\n", 2)[0];

        String best = null;
        int bestLen = -1;
        for (String key : fixtures.keySet()) {
            if (DEFAULT_KEY.equals(key)) continue;
            if (firstLine.startsWith(key) && key.length() > bestLen) {
                best = key;
                bestLen = key.length();
            }
        }
        if (best != null) {
            return fixtures.get(best);
        }
        return fixtures.getOrDefault(DEFAULT_KEY, FALLBACK_RESPONSE);
    }
}
