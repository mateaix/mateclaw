package vip.mate.interop.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/a2a")
@RequiredArgsConstructor
public class A2aJsonRpcController {

    private static final int ERR_INVALID_REQUEST = -32600;
    private static final int ERR_METHOD_NOT_FOUND = -32601;
    private static final int ERR_INVALID_PARAMS = -32602;
    private static final int ERR_TASK_NOT_FOUND = -32001;
    private static final int ERR_DUPLICATE_TASK = -32009;

    private final ObjectMapper objectMapper;
    private final A2aProperties properties;
    private final A2aTaskStore store;
    private final A2aExecutionBridge bridge;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PostMapping
    public ResponseEntity<Object> handle(@RequestBody JsonNode body, Authentication authentication) {
        if (!properties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "A2A is disabled"));
        }
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(null, -32050, "unauthorized"));
        }
        Object rpcId = rpcId(body == null ? null : body.get("id"));
        if (rpcId == InvalidRpcId.INSTANCE) {
            return ResponseEntity.ok(error(null, ERR_INVALID_REQUEST, "JSON-RPC id must be a string, number, or null"));
        }
        if (body == null || !body.isObject() || !"2.0".equals(text(body.get("jsonrpc")))) {
            return ResponseEntity.ok(error(rpcId, ERR_INVALID_REQUEST, "invalid JSON-RPC request"));
        }
        String method = text(body.get("method"));
        JsonNode params = body.get("params");
        String tenant = tenant(params);
        String rpcKey = rpcId == null ? null : String.valueOf(rpcId);
        if (rpcKey != null) {
            var existing = store.rpcSnapshot(tenant, rpcKey);
            if (existing.isPresent()) {
                return ResponseEntity.ok(result(rpcId, existing.get()));
            }
        }

        try {
            return switch (method) {
                case "message/send" -> ResponseEntity.ok(handleSend(rpcId, tenant, params, authentication));
                case "message/stream" -> stream(rpcId, tenant, params, authentication);
                case "tasks/get" -> ResponseEntity.ok(handleGet(rpcId, tenant, params));
                case "tasks/cancel" -> ResponseEntity.ok(handleCancel(rpcId, tenant, params));
                default -> ResponseEntity.ok(error(rpcId, ERR_METHOD_NOT_FOUND, "method not found"));
            };
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(error(rpcId, -32051, e.getMessage()));
        }
    }

    private Map<String, Object> handleSend(Object rpcId, String tenant, JsonNode params, Authentication auth) {
        try {
            A2aExecutionRequest request = executionRequest(tenant, params, auth);
            A2aTask submitted = A2aTask.submitted(request.taskId(), request.contextId(), tenant);
            if (!store.putIfAbsent(tenant, submitted)) {
                return error(rpcId, ERR_DUPLICATE_TASK, "task id already exists");
            }
            A2aTask working = store.update(tenant, request.taskId(),
                    task -> task.withStatus("working", null, false)).orElse(submitted);
            boolean blocking = !params.has("configuration")
                    || !params.get("configuration").has("blocking")
                    || params.get("configuration").get("blocking").asBoolean(true);
            A2aTask responseTask = blocking ? executeWithTimeout(tenant, request, working) : working;
            Map<String, Object> snapshot = responseTask.toMap();
            store.rememberRpcSnapshot(tenant, rpcId == null ? null : String.valueOf(rpcId), snapshot);
            return result(rpcId, snapshot);
        } catch (IllegalArgumentException e) {
            return error(rpcId, ERR_INVALID_PARAMS, e.getMessage());
        }
    }

    private ResponseEntity<Object> stream(Object rpcId, String tenant, JsonNode params, Authentication auth) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean done = new AtomicBoolean(false);
        streamExecutor.execute(() -> heartbeat(emitter, done));
        streamExecutor.execute(() -> {
            try {
                A2aExecutionRequest request = executionRequest(tenant, params, auth);
                A2aTask submitted = A2aTask.submitted(request.taskId(), request.contextId(), tenant);
                if (!store.putIfAbsent(tenant, submitted)) {
                    send(emitter, "error", error(rpcId, ERR_DUPLICATE_TASK, "task id already exists"));
                    emitter.complete();
                    return;
                }
                send(emitter, "task", submitted.toMap());
                A2aTask working = store.update(tenant, request.taskId(),
                        task -> task.withStatus("working", null, false)).orElse(submitted);
                send(emitter, "status-update", working.toMap());
                A2aExecutionBridge.ExecutionResult out = bridge.executeBlocking(request);
                A2aTask withArtifact = store.update(tenant, request.taskId(),
                        task -> task.withArtifact(out.text(), true)).orElse(working);
                send(emitter, "artifact-update", withArtifact.artifacts().getLast());
                A2aTask completed = store.update(tenant, request.taskId(),
                        task -> task.withStatus(out.terminal() ? "completed" : "working", out.text(), out.terminal()))
                        .orElse(withArtifact);
                send(emitter, "status-update", completed.toMap());
                emitter.complete();
            } catch (Exception e) {
                try {
                    send(emitter, "error", error(rpcId, ERR_INVALID_PARAMS, e.getMessage()));
                } catch (IOException ignored) {
                    // The client may already have disconnected.
                }
                emitter.completeWithError(e);
            } finally {
                done.set(true);
            }
        });
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private Map<String, Object> handleGet(Object rpcId, String tenant, JsonNode params) {
        String taskId = taskId(params);
        return store.get(tenant, taskId)
                .<Map<String, Object>>map(task -> result(rpcId, task.toMap()))
                .orElseGet(() -> error(rpcId, ERR_TASK_NOT_FOUND, "task not found"));
    }

    private Map<String, Object> handleCancel(Object rpcId, String tenant, JsonNode params) {
        String taskId = taskId(params);
        return store.update(tenant, taskId, task -> task.terminal()
                        ? task
                        : task.withStatus("canceled", "Task canceled by caller.", true))
                .<Map<String, Object>>map(task -> result(rpcId, task.toMap()))
                .orElseGet(() -> error(rpcId, ERR_TASK_NOT_FOUND, "task not found"));
    }

    private A2aTask executeWithTimeout(String tenant, A2aExecutionRequest request, A2aTask current) {
        CompletableFuture<A2aExecutionBridge.ExecutionResult> future =
                CompletableFuture.supplyAsync(() -> bridge.executeBlocking(request));
        try {
            A2aExecutionBridge.ExecutionResult out = future.get(properties.getCallTimeoutMs(), TimeUnit.MILLISECONDS);
            A2aTask withArtifact = store.update(tenant, request.taskId(),
                    task -> task.withArtifact(out.text(), true)).orElse(current);
            return store.update(tenant, request.taskId(),
                    task -> task.withStatus(out.terminal() ? "completed" : "working", out.text(), out.terminal()))
                    .orElse(withArtifact);
        } catch (TimeoutException e) {
            return current;
        } catch (Exception e) {
            return store.update(tenant, request.taskId(),
                    task -> task.withStatus("failed", e.getMessage(), true)).orElse(current);
        }
    }

    private A2aExecutionRequest executionRequest(String tenant, JsonNode params, Authentication auth) {
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("params object is required");
        }
        JsonNode message = params.get("message");
        if (message == null || !message.isObject()) {
            throw new IllegalArgumentException("message object is required");
        }
        String taskId = firstText(message.get("taskId"), params.get("id"));
        if (taskId.isBlank()) {
            taskId = "task-" + UUID.randomUUID();
        }
        String contextId = firstText(message.get("contextId"), params.get("contextId"));
        if (contextId.isBlank()) {
            contextId = taskId;
        }
        String text = extractText(message.get("parts"));
        if (text.isBlank()) {
            throw new IllegalArgumentException("message text is required");
        }
        Long agentId = agentId(message.get("metadata"));
        Long workspaceId = longOrDefault(params.get("workspaceId"), 1L);
        Long userId = auth.getDetails() instanceof Number n ? n.longValue() : null;
        return new A2aExecutionRequest(taskId, contextId, text, agentId, workspaceId, auth.getName(), userId);
    }

    private static Long agentId(JsonNode metadata) {
        String skillId = metadata == null ? "" : text(metadata.get("skillId"));
        if (skillId.isBlank()) {
            throw new IllegalArgumentException("message.metadata.skillId is required");
        }
        try {
            return Long.parseLong(skillId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("message.metadata.skillId must be a numeric agent id");
        }
    }

    private static String extractText(JsonNode parts) {
        if (parts == null || !parts.isArray()) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode part : parts) {
            String text = text(part.get("text"));
            if (!text.isBlank()) {
                texts.add(text);
            }
        }
        return String.join("\n", texts);
    }

    private static String taskId(JsonNode params) {
        String id = firstText(params == null ? null : params.get("id"),
                params == null ? null : params.get("taskId"));
        if (id.isBlank()) {
            throw new IllegalArgumentException("task id is required");
        }
        return id;
    }

    private static String tenant(JsonNode params) {
        return text(params == null ? null : params.get("tenant"));
    }

    private static Long longOrDefault(JsonNode node, Long fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            return Long.parseLong(node.asText());
        }
        return fallback;
    }

    private static Object rpcId(JsonNode id) {
        if (id == null || id.isNull()) {
            return null;
        }
        if (id.isTextual()) {
            return id.asText();
        }
        if (id.isNumber()) {
            return id.numberValue();
        }
        return InvalidRpcId.INSTANCE;
    }

    private static String firstText(JsonNode first, JsonNode second) {
        String value = text(first);
        return value.isBlank() ? text(second) : value;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("result", result);
        return out;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of("code", code, "message", message == null ? "" : message));
        return out;
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(event)
                .data(objectMapper.writeValueAsString(data)));
    }

    private void heartbeat(SseEmitter emitter, AtomicBoolean done) {
        while (!done.get()) {
            try {
                Thread.sleep(15_000L);
                if (!done.get()) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                }
            } catch (Exception e) {
                done.set(true);
            }
        }
    }

    private enum InvalidRpcId {
        INSTANCE
    }
}
