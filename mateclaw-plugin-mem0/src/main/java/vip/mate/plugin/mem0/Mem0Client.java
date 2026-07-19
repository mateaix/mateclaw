package vip.mate.plugin.mem0;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for a self-hosted Mem0 REST API.
 * <p>
 * Covers the two endpoints used by {@link Mem0Provider}:
 * <ul>
 *   <li>{@code POST /memories/} — add a turn (user + assistant message) for extraction</li>
 *   <li>{@code POST /memories/search/} — semantic recall by query + user_id</li>
 * </ul>
 *
 * <p>Failure semantics: every call either returns a parsed result or throws
 * {@link Mem0Exception}. Callers are expected to catch and degrade gracefully
 * (return empty recall / log sync failures).
 *
 * @author MateClaw Team
 */
class Mem0Client {

    private final Mem0Config config;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    Mem0Client(Mem0Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMs()))
                .build();
    }

    /**
     * Push a conversation turn to Mem0 for extraction.
     *
     * @param userId         Mem0 user_id, typically MateClaw's ownerKey
     * @param agentId        Mem0 agent_id, typically MateClaw's agentId
     * @param conversationId optional conversation identifier (stored as metadata)
     * @param userMessage    user's message text
     * @param assistantReply assistant's reply text
     */
    void addMemories(String userId, String agentId, String conversationId,
                     String userMessage, String assistantReply) {
        ObjectNode body = mapper.createObjectNode();
        body.put("user_id", userId);
        if (agentId != null && !agentId.isBlank()) {
            body.put("agent_id", agentId);
        }
        ArrayNode messages = body.putArray("messages");
        if (userMessage != null && !userMessage.isBlank()) {
            ObjectNode m = messages.addObject();
            m.put("role", "user");
            m.put("content", userMessage);
        }
        if (assistantReply != null && !assistantReply.isBlank()) {
            ObjectNode m = messages.addObject();
            m.put("role", "assistant");
            m.put("content", assistantReply);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            ObjectNode meta = body.putObject("metadata");
            meta.put("conversation_id", conversationId);
        }

        post("/memories/", body);
    }

    /**
     * Semantic recall.
     *
     * @param userId  Mem0 user_id (ownerKey)
     * @param agentId Mem0 agent_id
     * @param query   user query text
     * @return list of memory strings, possibly empty; never null
     */
    List<String> searchMemories(String userId, String agentId, String query) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query);
        body.put("user_id", userId);
        if (agentId != null && !agentId.isBlank()) {
            body.put("agent_id", agentId);
        }
        body.put("limit", config.maxResults());

        JsonNode resp = post("/memories/search/", body);
        JsonNode results = resp.path("results");
        List<String> out = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode r : results) {
                String mem = r.path("memory").asText("");
                if (!mem.isBlank()) {
                    out.add(mem);
                }
            }
        }
        return out;
    }

    /**
     * Shared POST helper. Returns the parsed JSON body on 2xx.
     *
     * @throws Mem0Exception on non-2xx response or IO error
     */
    private JsonNode post(String path, ObjectNode body) {
        String url = config.normalizedBaseUrl() + path;
        try {
            String payload = mapper.writeValueAsString(body);
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (config.apiKey() != null && !config.apiKey().isBlank()) {
                req.header("Authorization", "Bearer " + config.apiKey());
            }

            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new Mem0Exception("Mem0 " + path + " returned HTTP " + code
                        + ": " + truncate(resp.body(), 500));
            }
            return mapper.readTree(resp.body() == null ? "{}" : resp.body());
        } catch (Mem0Exception e) {
            throw e;
        } catch (Exception e) {
            throw new Mem0Exception("Mem0 " + path + " request failed: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * Test-only accessor for verifying configuration wiring.
     */
    Mem0Config config() {
        return config;
    }

    /**
     * Test-only helper to inspect what would be POSTed without sending.
     * Builds the same payload as {@link #addMemories} and returns it as a Map.
     */
    Map<String, Object> buildAddPayload(String userId, String agentId, String conversationId,
                                        String userMessage, String assistantReply) {
        ObjectNode body = mapper.createObjectNode();
        body.put("user_id", userId);
        if (agentId != null && !agentId.isBlank()) {
            body.put("agent_id", agentId);
        }
        ArrayNode messages = body.putArray("messages");
        if (userMessage != null && !userMessage.isBlank()) {
            ObjectNode m = messages.addObject();
            m.put("role", "user");
            m.put("content", userMessage);
        }
        if (assistantReply != null && !assistantReply.isBlank()) {
            ObjectNode m = messages.addObject();
            m.put("role", "assistant");
            m.put("content", assistantReply);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            ObjectNode meta = body.putObject("metadata");
            meta.put("conversation_id", conversationId);
        }
        return mapper.convertValue(body, Map.class);
    }
}
