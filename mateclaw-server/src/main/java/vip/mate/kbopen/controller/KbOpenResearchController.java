package vip.mate.kbopen.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.channel.web.Utf8SseEmitter;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.kbopen.auth.KbApiKeyContext;
import vip.mate.kbopen.auth.RequireKbScope;
import vip.mate.kbopen.research.KbResearchSessionRegistry;
import vip.mate.kbopen.research.KbResearchSessionRegistry.Session;
import vip.mate.kbopen.research.KbResearchSessionRegistry.Status;
import vip.mate.kbopen.research.KbResearchSessionRegistry.TooManyConcurrentException;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiResearchService;
import vip.mate.wiki.service.WikiResearchService.ResearchResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KB Open API — Deep Research endpoints.
 *
 * <p>Unlike the synchronous read endpoints, research is async (multi-step LLM
 * pipeline) with SSE progress. The start endpoint returns a sessionId; the
 * caller subscribes to SSE for progress, or polls status for the final result.
 *
 * <p>The SSE endpoint uses a {@code ?token=} query param because browser
 * EventSource cannot set Authorization headers. The {@code KbOpenApiAuthFilter}
 * already falls back to query param tokens.
 *
 * <h3>Cost &amp; lifecycle controls</h3>
 * <ul>
 *   <li>Cancel is <b>cooperative</b>: it calls {@link ChatStreamTracker#requestStop}
 *       so {@link WikiResearchService} bails at the next stage boundary — the
 *       draft fan-out and compose LLM calls are skipped, not run to completion.</li>
 *   <li>CANCELLED is a <b>sticky terminal</b>: a late complete() after cancel is
 *       a no-op in the registry, so the user never sees a COMPLETED report.</li>
 *   <li>Per-key <b>concurrency cap</b> ({@code mate.kbopen.research.max-concurrent-per-key})
 *       stops one key from spawning unbounded parallel pipelines → 429.</li>
 * </ul>
 */
@Slf4j
@Tag(name = "KB Open API — Deep Research")
@RestController
@RequestMapping("/api/v1/open/kb")
@RequiredArgsConstructor
public class KbOpenResearchController {

    private final WikiResearchService researchService;
    private final WikiKnowledgeBaseService kbService;
    private final ChatStreamTracker streamTracker;
    private final KbResearchSessionRegistry sessionRegistry;

    private static final ExecutorService RESEARCH_EXEC = Executors.newVirtualThreadPerTaskExecutor();

    // ── POST /{kbId}/research — start ─────────────────────────────────────

    @RequireKbScope("kb:search")
    @PostMapping("/{kbId}/research")
    @Operation(summary = "Start Deep Research (async, returns sessionId)")
    public R<Map<String, Object>> startResearch(
            @PathVariable Long kbId,
            @RequestBody ResearchRequest req,
            HttpServletRequest request) {
        KbApiKeyContext ctx = requireContext(request);
        String topic = req.topic();
        if (topic == null || topic.isBlank()) {
            throw new MateClawException(400, "topic is required");
        }
        if (kbService.getById(kbId) == null) {
            throw new MateClawException(404, "Knowledge base not found: " + kbId);
        }

        String sessionId = "open-research-" + UUID.randomUUID();
        streamTracker.register(sessionId);
        streamTracker.incrementFlux(sessionId);
        // Per-key concurrency cap (DoS / runaway-cost guard on top of the
        // per-minute rate limiter). Throws → 429 below.
        try {
            sessionRegistry.startIfAllowed(sessionId, ctx.keyId(), kbId, topic);
        } catch (TooManyConcurrentException e) {
            try { streamTracker.complete(sessionId); } catch (Exception ignored) {}
            throw new MateClawException(429, e.getMessage());
        }

        RESEARCH_EXEC.submit(() -> {
            try {
                ResearchResult result = researchService.research(kbId, topic, sessionId, req.topKPerQuestion());
                // complete() is a no-op if the user already cancelled — the
                // sticky CANCELLED terminal wins over a late COMPLETED.
                sessionRegistry.complete(sessionId, result);
            } catch (Exception e) {
                log.error("[KbOpenResearch] Failed sessionId={}: {}", sessionId, e.getMessage(), e);
                sessionRegistry.fail(sessionId, e.getMessage());
            } finally {
                try { streamTracker.broadcast(sessionId, "done", "{}"); } catch (Exception ignored) {}
                try { streamTracker.complete(sessionId); } catch (Exception ignored) {}
            }
        });

        return R.ok(Map.of(
                "sessionId", sessionId,
                "kbId", kbId,
                "topic", topic,
                "streamUrl", "/api/v1/open/kb/" + kbId + "/research/" + sessionId + "/stream"));
    }

    public record ResearchRequest(String topic, Integer topKPerQuestion) {}

    // ── GET /{kbId}/research/{sessionId}/stream — SSE ─────────────────────

    @RequireKbScope("kb:search")
    @GetMapping(value = "/{kbId}/research/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to research SSE progress (use ?token= for EventSource)")
    public SseEmitter stream(
            @PathVariable Long kbId,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        requireSessionOwnership(request, kbId, sessionId);

        SseEmitter emitter = new Utf8SseEmitter(10 * 60 * 1000L);
        boolean attached = streamTracker.attach(sessionId, emitter);
        if (!attached) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"session not found or already ended\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
        }
        emitter.onCompletion(() -> streamTracker.detach(sessionId, emitter));
        emitter.onTimeout(() -> streamTracker.detach(sessionId, emitter));
        emitter.onError(err -> streamTracker.detach(sessionId, emitter));
        return emitter;
    }

    // ── GET /{kbId}/research/{sessionId}/status — query result ────────────

    @RequireKbScope("kb:search")
    @GetMapping("/{kbId}/research/{sessionId}/status")
    @Operation(summary = "Query research status / final result")
    public R<Map<String, Object>> status(
            @PathVariable Long kbId,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        Session session = requireSessionOwnership(request, kbId, sessionId);
        Status status = session.status();
        ResearchResult result = session.result();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("status", status.name().toLowerCase());
        data.put("topic", session.topic());
        if (result != null) {
            data.put("report", result.report());
            data.put("sections", result.sections().size());
        }
        if (session.error() != null) {
            data.put("error", session.error());
        }
        return R.ok(data);
    }

    // ── POST /{kbId}/research/{sessionId}/cancel — cancel ─────────────────

    @RequireKbScope("kb:search")
    @PostMapping("/{kbId}/research/{sessionId}/cancel")
    @Operation(summary = "Cancel a running research session")
    public R<Map<String, Object>> cancel(
            @PathVariable Long kbId,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        Session session = requireSessionOwnership(request, kbId, sessionId);
        if (session.status() != Status.RUNNING) {
            throw new MateClawException(409, "Session is not running (status: " + session.status() + ")");
        }
        // Cooperative cancellation: signal the running pipeline to bail at the
        // next stage boundary (plan→draft, draft→compose, and inside the draft
        // fan-out) rather than running LLM calls to completion.
        streamTracker.requestStop(sessionId);
        sessionRegistry.cancel(sessionId);
        // Close the SSE stream so subscribers detach immediately.
        try {
            streamTracker.broadcast(sessionId, "cancelled", "{\"message\":\"cancelled by user\"}");
            streamTracker.broadcast(sessionId, "done", "{}");
            streamTracker.complete(sessionId);
        } catch (Exception ignored) {}
        return R.ok(Map.of("sessionId", sessionId, "status", "cancelled"));
    }

    // ── Auth helpers ──────────────────────────────────────────────────────

    private KbApiKeyContext requireContext(HttpServletRequest request) {
        KbApiKeyContext ctx = (KbApiKeyContext) request.getAttribute(KbApiKeyContext.ATTR);
        if (ctx == null) {
            throw new MateClawException(401, "Authentication required");
        }
        return ctx;
    }

    private Session requireSessionOwnership(HttpServletRequest request, Long kbId, String sessionId) {
        KbApiKeyContext ctx = requireContext(request);
        Optional<Session> session = sessionRegistry.get(sessionId);
        if (session.isEmpty()) {
            throw new MateClawException(404, "Research session not found: " + sessionId);
        }
        // A caller can only access sessions they started
        if (!session.get().keyId().equals(ctx.keyId())) {
            throw new MateClawException(403, "Session does not belong to this API key");
        }
        // The session must also belong to the KB named in the path, so a session
        // started under one KB cannot be addressed via another — even when the
        // caller's key happens to be bound to both.
        if (!session.get().kbId().equals(kbId)) {
            throw new MateClawException(404, "Research session not found: " + sessionId);
        }
        return session.get();
    }
}
