package vip.mate.execution.evidence.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.execution.evidence.ExecutionEvidenceProperties;
import vip.mate.execution.evidence.model.AttemptState;
import vip.mate.execution.evidence.model.EffectOutcome;
import vip.mate.execution.evidence.model.EvidenceKind;
import vip.mate.execution.evidence.model.EvidenceObservation;
import vip.mate.execution.evidence.model.EvidenceResult;
import vip.mate.execution.evidence.model.ExecutionAttempt;
import vip.mate.execution.evidence.model.SourceLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Observes the actual callback boundary. It never interprets returned text as a check result. */
@Service
public class ExecutionEvidenceRecorder {
    private static final Logger log = LoggerFactory.getLogger(ExecutionEvidenceRecorder.class);
    private final ExecutionEvidenceStore store;
    private final ExecutionIdentityResolver identities;
    private final ExecutionEvidenceProperties properties;
    private final MeterRegistry metrics;

    public ExecutionEvidenceRecorder(ExecutionEvidenceStore store, ExecutionIdentityResolver identities,
            ExecutionEvidenceProperties properties, MeterRegistry metrics) {
        this.store = store;
        this.identities = identities;
        this.properties = properties;
        this.metrics = metrics;
        metrics.gauge("mateclaw.execution.evidence.attempts.unresolved", store, ExecutionEvidenceStore::countUnresolved);
        if (properties.getMode() == ExecutionEvidenceProperties.Mode.ENFORCE) {
            throw new IllegalStateException("Execution evidence enforcement requires managed verification scopes; use observe or off");
        }
    }

    public String invoke(ToolCallback callback, String arguments, ToolContext context,
                         String invocationKey, String providerCallId) {
        if (properties.getMode() == ExecutionEvidenceProperties.Mode.OFF) return callback.call(arguments, context);
        ExecutionAttempt attempt = null;
        boolean duplicate = false;
        long began = System.nanoTime();
        try {
            var identity = identities.resolve(ChatOrigin.from(context), invocationKey, providerCallId,
                    callback.getToolDefinition().name());
            if (identity != null) {
                var reservation = store.reserve(identity);
                attempt = reservation.attempt();
                duplicate = !reservation.created();
            }
            else metrics.counter("mateclaw.execution.evidence.unattributed").increment();
        } catch (IllegalStateException conflict) {
            throw conflict;
        } catch (RuntimeException failure) {
            failure("begin");
        } finally {
            metrics.timer("mateclaw.execution.evidence.capture.latency", "phase", "begin")
                    .record(System.nanoTime() - began, TimeUnit.NANOSECONDS);
        }
        // An existing receipt is not a license to repeat an approved side effect.
        if (duplicate) {
            throw new IllegalStateException("Execution already observed; recover the existing approval result");
        }
        boolean direct = callback.getToolMetadata() != null && callback.getToolMetadata().returnDirect();
        var sink = new ExecutionObservationSink(direct, properties.getMaxObservations());
        try {
            ToolContext observedContext = context;
            if (attempt != null) {
                var values = new HashMap<String, Object>(context.getContext());
                ChatOrigin canonical = ChatOrigin.from(context).withWorkspace(attempt.identity().workspaceId(),
                        ChatOrigin.from(context).workspaceBasePath());
                values.put(ChatOrigin.CTX_KEY, canonical);
                observedContext = sink.attach(new ToolContext(values));
            }
            String result = callback.call(arguments, observedContext);
            finish(attempt, sink, null, "Tool callback returned");
            return result;
        } catch (RuntimeException | Error error) {
            AttemptState state = error instanceof CancellationException || Thread.currentThread().isInterrupted()
                    ? AttemptState.CANCELLED : AttemptState.FAILED;
            finish(attempt, sink, state, "Tool callback did not complete normally");
            throw error;
        }
    }

    private void finish(ExecutionAttempt attempt, ExecutionObservationSink sink, AttemptState overrideState, String summary) {
        var captured = sink.sealAndSnapshot();
        AttemptState state = overrideState != null ? overrideState : captured.state();
        if (attempt == null) return;
        long began = System.nanoTime();
        try {
            if (!identities.isCurrent(attempt.identity())) {
                failure("owner_lost");
                return;
            }
            var observations = new ArrayList<>(captured.observations());
            observations.add(new EvidenceObservation("callback", EvidenceKind.TOOL_RETURNED,
                    state == AttemptState.SUCCEEDED ? EvidenceResult.OBSERVED
                            : state == AttemptState.UNKNOWN || state == AttemptState.CANCELLED
                                    ? EvidenceResult.UNKNOWN : EvidenceResult.FAIL,
                    SourceLevel.PLATFORM_OBSERVED, summary));
            store.finish(attempt.id(), attempt.identity().ownerFence(), state,
                    state == AttemptState.BLOCKED ? EffectOutcome.NONE : EffectOutcome.UNCERTAIN, observations);
            for (var observation : observations) {
                metrics.counter("mateclaw.execution.evidence.observations", "kind", observation.kind().name()).increment();
            }
        } catch (RuntimeException failure) {
            // Preserve STARTED as uncertain; the existing recovery authority owns any retry.
            failure("finish");
        } finally {
            metrics.timer("mateclaw.execution.evidence.capture.latency", "phase", "finish")
                    .record(System.nanoTime() - began, TimeUnit.NANOSECONDS);
        }
    }

    private void failure(String phase) {
        metrics.counter("mateclaw.execution.evidence.capture.failures", "phase", phase).increment();
        log.warn("Execution evidence capture unavailable (phase={}); consult execution recovery state", phase);
    }
}
