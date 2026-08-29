package vip.mate.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class A2aPeerAdapter {

    private final ObjectMapper objectMapper;
    private final Policy policy;
    private final HttpClient httpClient;

    public A2aPeerAdapter(ObjectMapper objectMapper, Policy policy) {
        this.objectMapper = objectMapper;
        this.policy = policy == null ? Policy.defaults() : policy;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.policy.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public PeerResult sendBlocking(String url, String message, String contextId, String skillId,
                                   Map<String, String> headers) throws IOException, InterruptedException {
        URI uri = resolveRpcUri(url, headers == null ? Map.of() : headers);
        Map<String, Object> body = rpcBody("message/send", message, contextId, skillId);
        CappedBody response = post(uri, body, headers == null ? Map.of() : headers);
        CappedBody finalBody = pollIfRunning(uri, response, headers == null ? Map.of() : headers);
        return new PeerResult(finalBody.body(), List.of(), response.truncated() || finalBody.truncated());
    }

    public PeerResult stream(String url, String message, String contextId, String skillId,
                             Map<String, String> headers) throws IOException, InterruptedException {
        URI uri = resolveRpcUri(url, headers == null ? Map.of() : headers);
        Map<String, Object> body = rpcBody("message/stream", message, contextId, skillId);
        CappedBody response = post(uri, body, headers == null ? Map.of() : headers);
        return new PeerResult(response.body(), SseFrames.parse(response.body()), response.truncated());
    }

    private CappedBody pollIfRunning(URI rpcUri, CappedBody initial, Map<String, String> headers)
            throws IOException, InterruptedException {
        JsonNode task = taskNode(initial.body());
        String taskId = task == null ? "" : text(task.get("id"));
        if (taskId.isBlank() || isTerminalState(task)) {
            return initial;
        }
        long deadline = System.nanoTime() + policy.timeout().toNanos();
        CappedBody latest = initial;
        while (System.nanoTime() < deadline) {
            Thread.sleep(Math.min(1_000L, Math.max(100L, policy.timeout().toMillis())));
            latest = post(rpcUri, taskGetBody(taskId), headers);
            task = taskNode(latest.body());
            if (task == null || isTerminalState(task)) {
                return latest;
            }
        }
        return latest;
    }

    private Map<String, Object> taskGetBody(String taskId) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", "rpc-" + UUID.randomUUID(),
                "method", "tasks/get",
                "params", Map.of("id", taskId)
        );
    }

    private JsonNode taskNode(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode result = root.get("result");
            if (result == null || result.isNull()) {
                return null;
            }
            if (result.has("task")) {
                return result.get("task");
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isTerminalState(JsonNode task) {
        JsonNode status = task.get("status");
        String state = status == null ? "" : text(status.get("state"));
        return "completed".equalsIgnoreCase(state)
                || "canceled".equalsIgnoreCase(state)
                || "cancelled".equalsIgnoreCase(state)
                || "failed".equalsIgnoreCase(state)
                || "TASK_STATE_COMPLETED".equals(state)
                || "TASK_STATE_CANCELED".equals(state)
                || "TASK_STATE_FAILED".equals(state);
    }

    private URI resolveRpcUri(String endpoint, Map<String, String> headers) {
        URI configured = safeUri(endpoint);
        for (URI candidate : cardCandidates(configured)) {
            try {
                CappedBody card = get(candidate, headers);
                String url = rpcUrlFromCard(card.body());
                if (!url.isBlank()) {
                    return safeUri(url);
                }
            } catch (Exception ignored) {
                // Discovery is best-effort; the configured endpoint remains valid.
            }
        }
        return configured;
    }

    private List<URI> cardCandidates(URI endpoint) {
        List<URI> out = new ArrayList<>();
        String raw = endpoint.toString();
        if (raw.endsWith(".json")) {
            out.add(endpoint);
        }
        out.add(endpoint.resolve(trimTrailingSlash(endpoint.getPath()) + "/card"));
        out.add(endpoint.resolve("/.well-known/agent-card.json"));
        return out;
    }

    private String rpcUrlFromCard(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode interfaces = root.get("supportedInterfaces");
        if (interfaces != null && interfaces.isArray()) {
            for (JsonNode iface : interfaces) {
                if ("JSONRPC".equalsIgnoreCase(text(iface.get("protocolBinding")))) {
                    String url = text(iface.get("url"));
                    if (!url.isBlank()) {
                        return url;
                    }
                }
            }
        }
        return text(root.get("url"));
    }

    private CappedBody get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(safeUri(uri.toString()))
                .timeout(policy.timeout())
                .GET();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() != null && header.getValue() != null) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            throw new IOException("redirects are not allowed");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("peer returned HTTP " + response.statusCode());
        }
        return cap(response.body());
    }

    private Map<String, Object> rpcBody(String method, String message, String contextId, String skillId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (skillId != null && !skillId.isBlank()) {
            metadata.put("skillId", skillId);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("messageId", "msg-" + UUID.randomUUID());
        if (contextId != null && !contextId.isBlank()) {
            msg.put("contextId", contextId);
        }
        msg.put("parts", List.of(Map.of("kind", "text", "text", message == null ? "" : message)));
        msg.put("metadata", metadata);
        return Map.of(
                "jsonrpc", "2.0",
                "id", "rpc-" + UUID.randomUUID(),
                "method", method,
                "params", Map.of("message", msg, "configuration", Map.of("blocking", true))
        );
    }

    private CappedBody post(URI uri, Map<String, Object> body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(policy.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() != null && header.getValue() != null) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            throw new IOException("redirects are not allowed");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("peer returned HTTP " + response.statusCode());
        }
        return cap(response.body());
    }

    private CappedBody cap(byte[] body) {
        byte[] bytes = body == null ? new byte[0] : body;
        boolean truncated = bytes.length > policy.maxResponseBytes();
        int length = truncated ? policy.maxResponseBytes() : bytes.length;
        return new CappedBody(new String(bytes, 0, length, StandardCharsets.UTF_8), truncated);
    }

    private URI safeUri(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only HTTP and HTTPS A2A URLs are supported");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("A2A URL host is required");
        }
        if (!policy.allowPrivateNetwork()) {
            rejectPrivateAddress(uri.getHost());
        }
        return uri;
    }

    private static void rejectPrivateAddress(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                byte[] raw = address.getAddress();
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || isReserved(raw)) {
                    throw new IllegalArgumentException("A2A URL resolves to a private or reserved address");
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("A2A URL host could not be resolved", e);
        }
    }

    private static boolean isReserved(byte[] raw) {
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            return first == 0
                    || first == 10
                    || first == 127
                    || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first >= 224;
        }
        if (raw.length == 16) {
            int first = raw[0] & 0xff;
            return first == 0
                    || first == 0xfc
                    || first == 0xfd
                    || first == 0xfe;
        }
        return true;
    }

    private static String trimTrailingSlash(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.replaceAll("/+$", "");
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    public record Policy(Duration timeout, int maxResponseBytes, boolean allowPrivateNetwork) {
        public static Policy defaults() {
            return new Policy(Duration.ofSeconds(120), 1_048_576, false);
        }
    }

    public record PeerResult(String body, List<SseFrames.Frame> frames, boolean truncated) {
        public PeerResult {
            frames = frames == null ? List.of() : List.copyOf(frames);
        }
    }

    private record CappedBody(String body, boolean truncated) {
    }
}
