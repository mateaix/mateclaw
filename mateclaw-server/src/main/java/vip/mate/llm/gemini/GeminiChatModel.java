package vip.mate.llm.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import vip.mate.llm.gemini.GeminiNativeClient.GeminiCall;
import vip.mate.llm.gemini.GeminiNativeClient.StreamEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI {@link ChatModel} backed by the native Gemini
 * {@code generateContent} API. Extracts tool callbacks from the prompt
 * options, delegates message translation + transport to
 * {@link GeminiNativeClient}, and adapts Gemini's whole-at-once function calls
 * into the start/args-delta {@link ChatResponse} shape the streaming agent
 * pipeline expects.
 */
@Slf4j
public class GeminiChatModel implements ChatModel {

    private final GeminiNativeClient client;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;

    public GeminiChatModel(GeminiNativeClient client, String baseUrl, String apiKey,
                           String modelName, Double temperature, Integer maxTokens) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        GeminiCall call = buildCall(prompt);
        JsonNode response = client.generate(call);

        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        String finishReason = "stop";

        for (JsonNode candidate : response.path("candidates")) {
            String reason = candidate.path("finishReason").asText("");
            if (!reason.isBlank()) {
                finishReason = reason;
            }
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.path("thought").asBoolean(false)) {
                    continue;
                }
                JsonNode functionCall = part.get("functionCall");
                if (functionCall != null && !functionCall.isNull()) {
                    String name = functionCall.path("name").asText("");
                    String id = functionCall.has("id")
                            ? functionCall.get("id").asText()
                            : "call_" + name + "_" + System.nanoTime();
                    String args = functionCall.has("args")
                            ? functionCall.get("args").toString() : "{}";
                    toolCalls.add(new AssistantMessage.ToolCall(id, "function", name, args));
                    continue;
                }
                JsonNode partText = part.get("text");
                if (partText != null && partText.isTextual()) {
                    text.append(partText.asText());
                }
            }
        }

        AssistantMessage assistantMessage = toolCalls.isEmpty()
                ? new AssistantMessage(text.toString())
                : AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls).build();
        Generation generation = new Generation(assistantMessage,
                ChatGenerationMetadata.builder().finishReason(finishReason).build());

        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model(call.model());
        Usage usage = extractUsage(response.get("usageMetadata"));
        if (usage != null) {
            metadata.usage(usage);
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        GeminiCall call = buildCall(prompt);
        return client.streamEvents(call)
                .concatMapIterable(event -> toChatResponses(event, call.model()));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .build();
    }

    /**
     * Adapt one {@link StreamEvent} into 0-2 {@link ChatResponse}s. A tool call
     * becomes a start frame (id + name, empty args) followed by an args-delta
     * frame (empty id, full args) so the streaming accumulator builds it the
     * same way it does for OpenAI's incremental tool calls.
     */
    private List<ChatResponse> toChatResponses(StreamEvent event, String model) {
        switch (event.type()) {
            case "text" -> {
                Generation gen = new Generation(new AssistantMessage(event.text()),
                        ChatGenerationMetadata.builder().finishReason(null).build());
                return List.of(new ChatResponse(List.of(gen),
                        ChatResponseMetadata.builder().model(model).build()));
            }
            case "tool_call" -> {
                AssistantMessage startMsg = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                event.toolCallId(), "function", event.toolName(), "")))
                        .build();
                AssistantMessage deltaMsg = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "", "function", "", event.toolArgs())))
                        .build();
                ChatResponseMetadata md = ChatResponseMetadata.builder().model(model).build();
                return List.of(
                        new ChatResponse(List.of(new Generation(startMsg,
                                ChatGenerationMetadata.builder().finishReason(null).build())), md),
                        new ChatResponse(List.of(new Generation(deltaMsg,
                                ChatGenerationMetadata.builder().finishReason(null).build())), md));
            }
            case "done" -> {
                int in = event.inputTokens() != null ? event.inputTokens() : 0;
                int out = event.outputTokens() != null ? event.outputTokens() : 0;
                int total = event.totalTokens() != null ? event.totalTokens() : (in + out);
                Usage usage = new DefaultUsage(in, out, total);
                Generation gen = new Generation(new AssistantMessage(""),
                        ChatGenerationMetadata.builder().finishReason("stop").build());
                return List.of(new ChatResponse(List.of(gen),
                        ChatResponseMetadata.builder().model(model).usage(usage).build()));
            }
            default -> {
                return List.of();
            }
        }
    }

    private GeminiCall buildCall(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        String model = resolveModel(prompt);
        Double temp = resolveTemperature(prompt);
        List<ToolDefinition> tools = extractToolDefinitions(prompt);
        return new GeminiCall(baseUrl, apiKey, model, messages, temp, maxTokens, tools);
    }

    private Usage extractUsage(JsonNode usageMetadata) {
        if (usageMetadata == null || usageMetadata.isNull()) {
            return null;
        }
        int in = usageMetadata.path("promptTokenCount").asInt(0);
        int out = usageMetadata.path("candidatesTokenCount").asInt(0);
        int total = usageMetadata.has("totalTokenCount")
                ? usageMetadata.get("totalTokenCount").asInt() : in + out;
        return new DefaultUsage(in, out, total);
    }

    private List<ToolDefinition> extractToolDefinitions(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options == null) {
            return List.of();
        }
        List<ToolCallback> callbacks = null;
        if (options instanceof ToolCallingChatOptions tcOpts) {
            callbacks = tcOpts.getToolCallbacks();
        } else {
            try {
                var method = options.getClass().getMethod("getToolCallbacks");
                @SuppressWarnings("unchecked")
                var result = (List<ToolCallback>) method.invoke(options);
                callbacks = result;
            } catch (Exception ignored) {
                // options type carries no tool callbacks
            }
        }
        if (callbacks == null || callbacks.isEmpty()) {
            return List.of();
        }
        return callbacks.stream().map(ToolCallback::getToolDefinition).toList();
    }

    private String resolveModel(Prompt prompt) {
        if (prompt.getOptions() != null && prompt.getOptions().getModel() != null) {
            return prompt.getOptions().getModel();
        }
        return modelName;
    }

    private Double resolveTemperature(Prompt prompt) {
        if (prompt.getOptions() != null && prompt.getOptions().getTemperature() != null) {
            return prompt.getOptions().getTemperature();
        }
        return temperature;
    }
}
