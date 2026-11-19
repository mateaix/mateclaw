package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vip.mate.llm.model.ModelFamily;
import vip.mate.llm.model.ModelProviderEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Provider policies applied to the complete SDK request without rebuilding SDK DTOs. */
final class OpenAiRequestRewriter {
    static final String RELAY_FIELD = "__mateclaw_thinking_relay";
    private OpenAiRequestRewriter() {}

    static ObjectNode rewrite(ObjectNode source, ModelProviderEntity provider, boolean kimiSearchEnabled) {
        ObjectNode request = source.deepCopy();
        JsonNode embedded = request.remove(RELAY_FIELD);
        AssistantThinkingRelay.RelayEntry relay = null;
        if (embedded != null && embedded.isObject()) {
            List<String> values = new ArrayList<>();
            embedded.path("thinkings").forEach(value -> values.add(value.asText()));
            relay = new AssistantThinkingRelay.RelayEntry(values, embedded.path("originalUser").isTextual()
                    ? embedded.path("originalUser").asText() : null);
        } else relay = AssistantThinkingRelay.take(request.path("user").asText(null));
        if (relay != null) {
            if (relay.originalUser() == null) request.remove("user"); else request.put("user", relay.originalUser());
        } else if (AssistantThinkingRelay.isToken(request.path("user").asText(null))) request.remove("user");
        ModelFamily family = ModelFamily.detect(request.path("model").asText());
        String providerId = provider == null ? null : provider.getProviderId();
        if (!Set.of("openai", "azure-openai").contains(providerId == null ? "" : providerId.toLowerCase(Locale.ROOT))
                || !family.supportsReasoningEffort()) request.remove("reasoning_effort");
        boolean thinking = family.isThinking() || request.has("reasoning_effort") || relay != null;
        JsonNode messages = request.path("messages");
        for (JsonNode message : messages) if (message.has("reasoning_content")) thinking = true;
        if (thinking && messages.isArray()) {
            boolean crossTurn = "deepseek".equalsIgnoreCase(providerId) || "xiaomi-mimo".equalsIgnoreCase(providerId);
            int lastUser = -1;
            for (int i = 0; i < messages.size(); i++) if ("user".equals(messages.get(i).path("role").asText())) lastUser = i;
            int assistantIndex = 0;
            for (int i = 0; i < messages.size(); i++) {
                if (!(messages.get(i) instanceof ObjectNode message) || !"assistant".equals(message.path("role").asText())) continue;
                String value = relay != null && assistantIndex < relay.thinkings().size() ? relay.thinkings().get(assistantIndex) : null;
                assistantIndex++;
                if (!message.path("reasoning_content").asText("").isBlank()) continue;
                if (i < lastUser && !crossTurn) continue;
                boolean hasTools = message.path("tool_calls").isArray() && !message.path("tool_calls").isEmpty();
                if (!hasTools && !crossTurn) continue;
                if (value == null || value.isEmpty()) {
                    List<String> ids = new ArrayList<>();
                    message.path("tool_calls").forEach(call -> ids.add(call.path("id").asText()));
                    value = i <= lastUser ? ReasoningContentCache.get(ids) : null;
                }
                message.put("reasoning_content", value == null || value.isEmpty() ? " " : value);
            }
        }
        if (request.path("model").asText().trim().toLowerCase(Locale.ROOT).startsWith("gpt-5") && !request.path("tools").isEmpty()) request.remove("reasoning_effort");
        if ("auto".equals(request.path("tool_choice").asText())) request.remove("tool_choice");
        for (JsonNode message : messages) {
            if (!"user".equals(message.path("role").asText())) continue;
            for (JsonNode item : message.path("content")) if (item instanceof ObjectNode content
                    && "image_url".equals(content.path("type").asText())
                    && content.path("image_url").path("url").asText().startsWith("data:video/")) {
                String url = content.path("image_url").path("url").asText();
                content.remove("image_url"); content.put("type", "video_url"); content.put("video_url", url);
            }
        }
        if (kimiSearchEnabled) {
            ArrayNode tools = request.path("tools").isArray() ? (ArrayNode) request.get("tools") : request.putArray("tools");
            tools.addObject().put("type", "builtin_function").putObject("function").put("name", "$web_search");
        }
        // fix：https://github.com/mateaix/mateclaw/issues/640
        // OpenAI/Azure reject tool_choice and parallel_tool_calls on a request that
        // carries no tools (400 "'tool_choice' is only allowed when 'tools' are
        // specified"). Lenient gateways silently ignore the orphan fields, which is
        // why this only surfaced on Azure. TaskFinalizationCoordinator builds
        // finalization/summary options with cleared tool callbacks AND
        // toolChoice("none") — that belt-and-braces only makes sense while tools
        // actually reach the wire, so keep it when they do and drop the orphan
        // fields when they don't. An empty tools array is itself spec-invalid
        // ("minimum length 1"); drop it too.
        JsonNode toolsNode = request.path("tools");
        boolean hasTools = toolsNode.isArray() && !toolsNode.isEmpty();
        if (toolsNode.isArray() && toolsNode.isEmpty()) request.remove("tools");
        if (!hasTools) {
            request.remove("tool_choice");
            request.remove("parallel_tool_calls");
        }
        return request;
    }
}
