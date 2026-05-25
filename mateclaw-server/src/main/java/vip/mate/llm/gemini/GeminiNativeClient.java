package vip.mate.llm.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import vip.mate.exception.MateClawException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Native client for the Gemini {@code generateContent} API
 * ({@code generativelanguage.googleapis.com}).
 *
 * <p>Translates Spring AI {@link Message} lists into Gemini {@code contents}
 * (with {@code systemInstruction}, {@code functionCall} / {@code functionResponse}
 * parts and inline image data), and parses the {@code streamGenerateContent}
 * SSE stream back into {@link StreamEvent}s. Mirrors the role this project's
 * {@code ChatGPTResponsesClient} plays for the OpenAI Responses API.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiNativeClient {

    private final ObjectMapper objectMapper;
    private final WebClient webClient = WebClient.create();

    /**
     * One streamed delta from Gemini. {@code text} carries an output-text
     * chunk; {@code tool_call} carries a complete function call (Gemini does
     * not stream call arguments incrementally); {@code done} carries final
     * token usage.
     */
    public record StreamEvent(String type, String text, String toolCallId, String toolName,
                              String toolArgs, Integer inputTokens, Integer outputTokens,
                              Integer totalTokens) {
        public static StreamEvent text(String delta) {
            return new StreamEvent("text", delta, null, null, null, null, null, null);
        }
        public static StreamEvent toolCall(String id, String name, String args) {
            return new StreamEvent("tool_call", null, id, name, args, null, null, null);
        }
        public static StreamEvent done(Integer in, Integer out, Integer total) {
            return new StreamEvent("done", null, null, null, null, in, out, total);
        }
    }

    /** Connection parameters resolved from the provider + model rows. */
    public record GeminiCall(String baseUrl, String apiKey, String model, List<Message> messages,
                             Double temperature, Integer maxTokens, List<ToolDefinition> tools) {
    }

    /**
     * Stream a {@code generateContent} call. Emits text deltas, complete tool
     * calls, and a terminal {@code done} event with token usage.
     */
    public Flux<StreamEvent> streamEvents(GeminiCall call) {
        ObjectNode body = buildRequestBody(call);
        String url = endpoint(call, "streamGenerateContent") + "&alt=sse";
        log.info("[Gemini] stream: model={}, messages={}, tools={}", call.model(),
                call.messages().size(), call.tools() != null ? call.tools().size() : 0);

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body.toString())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(errorBody -> {
                                    log.error("[Gemini] API error {}: {}", response.statusCode(), errorBody);
                                    return new MateClawException("err.llm.gemini_error",
                                            "Gemini API " + response.statusCode() + ": "
                                                    + extractErrorMessage(errorBody));
                                }))
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.startsWith("data: ") ? line.substring(6) : line.substring(5))
                .filter(line -> !line.isBlank())
                .concatMapIterable(this::parseChunk)
                .onErrorMap(e -> e instanceof MateClawException ? e
                        : new MateClawException("err.llm.gemini_stream_failed",
                        "Gemini 流式调用失败: " + e.getMessage()));
    }

    /** Non-streaming {@code generateContent} call — returns the parsed response. */
    public JsonNode generate(GeminiCall call) {
        ObjectNode body = buildRequestBody(call);
        String url = endpoint(call, "generateContent");
        log.info("[Gemini] call: model={}, messages={}, tools={}", call.model(),
                call.messages().size(), call.tools() != null ? call.tools().size() : 0);

        String response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(errorBody -> {
                                    log.error("[Gemini] API error {}: {}", resp.statusCode(), errorBody);
                                    return new MateClawException("err.llm.gemini_error",
                                            "Gemini API " + resp.statusCode() + ": "
                                                    + extractErrorMessage(errorBody));
                                }))
                .bodyToMono(String.class)
                .block();
        try {
            return objectMapper.readTree(response == null ? "{}" : response);
        } catch (Exception e) {
            throw new MateClawException("err.llm.gemini_error", "Gemini 响应解析失败: " + e.getMessage());
        }
    }

    private String endpoint(GeminiCall call, String method) {
        String base = call.baseUrl();
        if (base == null || base.isBlank()) {
            base = "https://generativelanguage.googleapis.com";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1beta/models/" + call.model() + ":" + method + "?key=" + call.apiKey();
    }

    // ==================== request building ====================

    ObjectNode buildRequestBody(GeminiCall call) {
        ObjectNode body = objectMapper.createObjectNode();

        // System instruction — first SYSTEM message becomes systemInstruction.
        for (Message msg : call.messages()) {
            if (msg.getMessageType() == MessageType.SYSTEM) {
                String sys = msg.getText();
                if (sys != null && !sys.isBlank()) {
                    ObjectNode systemInstruction = objectMapper.createObjectNode();
                    ArrayNode sysParts = systemInstruction.putArray("parts");
                    sysParts.addObject().put("text", sys);
                    body.set("systemInstruction", systemInstruction);
                }
                break;
            }
        }

        // contents — user / model / tool turns.
        ArrayNode contents = body.putArray("contents");
        for (Message msg : call.messages()) {
            switch (msg.getMessageType()) {
                case SYSTEM -> { /* hoisted into systemInstruction */ }
                case USER -> contents.add(buildUserContent(msg));
                case ASSISTANT -> {
                    ObjectNode modelContent = buildAssistantContent((AssistantMessage) msg);
                    if (modelContent != null) {
                        contents.add(modelContent);
                    }
                }
                case TOOL -> contents.add(buildToolContent((ToolResponseMessage) msg));
            }
        }

        // tools — functionDeclarations.
        if (call.tools() != null && !call.tools().isEmpty()) {
            ArrayNode toolsArr = body.putArray("tools");
            ObjectNode toolEntry = toolsArr.addObject();
            ArrayNode declarations = toolEntry.putArray("functionDeclarations");
            for (ToolDefinition tool : call.tools()) {
                ObjectNode decl = declarations.addObject();
                decl.put("name", tool.name());
                if (tool.description() != null) {
                    decl.put("description", tool.description());
                }
                JsonNode params = parseSchema(tool.inputSchema());
                decl.set("parameters", GeminiSchemaSanitizer.sanitizeToolParameters(params, objectMapper));
            }
        }

        // generationConfig.
        ObjectNode genConfig = body.putObject("generationConfig");
        if (call.temperature() != null) {
            genConfig.put("temperature", call.temperature());
        }
        if (call.maxTokens() != null && call.maxTokens() > 0) {
            genConfig.put("maxOutputTokens", call.maxTokens());
        }
        return body;
    }

    private ObjectNode buildUserContent(Message msg) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        String text = msg.getText();
        if (text != null && !text.isBlank()) {
            parts.addObject().put("text", text);
        }
        if (msg instanceof UserMessage userMsg) {
            for (Media media : userMsg.getMedia()) {
                appendInlineMedia(parts, media);
            }
        }
        if (parts.isEmpty()) {
            parts.addObject().put("text", "");
        }
        return content;
    }

    private void appendInlineMedia(ArrayNode parts, Media media) {
        try {
            byte[] data = media.getDataAsByteArray();
            if (data == null || data.length == 0) {
                return;
            }
            String mime = media.getMimeType() != null ? media.getMimeType().toString() : "image/png";
            ObjectNode inlineData = parts.addObject().putObject("inlineData");
            inlineData.put("mimeType", mime);
            inlineData.put("data", Base64.getEncoder().encodeToString(data));
        } catch (Exception e) {
            // URI-backed media (no inline bytes) — skip rather than fail the turn.
            log.debug("[Gemini] skipping non-inline media: {}", e.getMessage());
        }
    }

    private ObjectNode buildAssistantContent(AssistantMessage msg) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("role", "model");
        ArrayNode parts = content.putArray("parts");
        String text = msg.getText();
        if (text != null && !text.isBlank()) {
            parts.addObject().put("text", text);
        }
        if (msg.hasToolCalls()) {
            for (AssistantMessage.ToolCall tc : msg.getToolCalls()) {
                ObjectNode functionCall = parts.addObject().putObject("functionCall");
                functionCall.put("name", tc.name());
                functionCall.set("args", parseArgs(tc.arguments()));
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return content;
    }

    private ObjectNode buildToolContent(ToolResponseMessage msg) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        for (ToolResponseMessage.ToolResponse response : msg.getResponses()) {
            ObjectNode functionResponse = parts.addObject().putObject("functionResponse");
            functionResponse.put("name", response.name());
            functionResponse.set("response", wrapToolResponse(response.responseData()));
        }
        if (parts.isEmpty()) {
            parts.addObject().putObject("functionResponse").put("name", "unknown");
        }
        return content;
    }

    /** Gemini requires {@code functionResponse.response} to be a JSON object. */
    private ObjectNode wrapToolResponse(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            ObjectNode empty = objectMapper.createObjectNode();
            empty.put("result", "");
            return empty;
        }
        try {
            JsonNode parsed = objectMapper.readTree(responseData);
            if (parsed.isObject()) {
                return (ObjectNode) parsed;
            }
            ObjectNode wrapped = objectMapper.createObjectNode();
            wrapped.set("result", parsed);
            return wrapped;
        } catch (Exception e) {
            ObjectNode wrapped = objectMapper.createObjectNode();
            wrapped.put("result", responseData);
            return wrapped;
        }
    }

    private JsonNode parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            return parsed.isObject() ? parsed : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode parseSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(inputSchema);
        } catch (Exception e) {
            log.warn("[Gemini] failed to parse tool schema: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    // ==================== response parsing ====================

    /** Parse one SSE data chunk into zero or more {@link StreamEvent}s. */
    private List<StreamEvent> parseChunk(String json) {
        List<StreamEvent> events = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(json);

            JsonNode error = node.get("error");
            if (error != null && !error.isNull()) {
                throw new MateClawException("err.llm.gemini_error",
                        "Gemini 返回错误: " + error.path("message").asText("unknown"));
            }

            boolean terminal = false;
            for (JsonNode candidate : node.path("candidates")) {
                if (!candidate.path("finishReason").asText("").isBlank()) {
                    terminal = true;
                }
                for (JsonNode part : candidate.path("content").path("parts")) {
                    // Skip thinking-summary parts — they are not visible output.
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
                        events.add(StreamEvent.toolCall(id, name, args));
                        continue;
                    }
                    JsonNode text = part.get("text");
                    if (text != null && text.isTextual() && !text.asText().isEmpty()) {
                        events.add(StreamEvent.text(text.asText()));
                    }
                }
            }

            // Token usage — emitted once, on the terminal chunk only. Gemini may
            // repeat cumulative usageMetadata on every chunk; emitting per-chunk
            // would double-count tokens in the agent's usage ledger.
            JsonNode usage = node.get("usageMetadata");
            if (terminal && usage != null && !usage.isNull()) {
                Integer in = usage.has("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null;
                Integer out = usage.has("candidatesTokenCount")
                        ? usage.get("candidatesTokenCount").asInt() : null;
                Integer total = usage.has("totalTokenCount")
                        ? usage.get("totalTokenCount").asInt()
                        : (in != null && out != null ? in + out : null);
                if (in != null || out != null || total != null) {
                    events.add(StreamEvent.done(in, out, total));
                }
            }
        } catch (MateClawException e) {
            throw e;
        } catch (Exception e) {
            log.debug("[Gemini] skipping unparseable chunk: {}", e.getMessage());
        }
        return events;
    }

    private String extractErrorMessage(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return "(empty body)";
        }
        try {
            JsonNode node = objectMapper.readTree(errorBody);
            JsonNode message = node.path("error").path("message");
            if (message.isTextual()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return errorBody.length() > 300 ? errorBody.substring(0, 300) + "..." : errorBody;
    }
}
