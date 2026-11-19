package vip.mate.agent.runtime.agentscope;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.messages.AssistantMessage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import vip.mate.agent.runtime.AgentRuntime;
import vip.mate.agent.runtime.state.EdgeAction;
import vip.mate.agent.runtime.state.NodeAction;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.RunStep;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Narrow policy callbacks; SDK ReActAgent owns all reasoning/acting iteration. */
@Slf4j
public final class NativePolicyDriver implements AgentRuntime {
    private static final ObjectMapper TOOL_JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final String BATCH_RESULT = "native_guarded_batch_result";
    public static final String END = "end";

    public record Policies(
            NodeAction reasoning,
            EdgeAction afterReasoning,
            NodeAction action,
            NodeAction observation,
            EdgeAction afterObservation,
            NodeAction summarizing,
            NodeAction limitExceeded,
            NodeAction finalAnswer,
            EdgeAction afterFinal,
            NodeAction goalEvaluation,
            EdgeAction afterGoal) {
        public Policies {
            Objects.requireNonNull(reasoning, "reasoning");
            Objects.requireNonNull(afterReasoning, "afterReasoning");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(afterObservation, "afterObservation");
            Objects.requireNonNull(summarizing, "summarizing");
            Objects.requireNonNull(limitExceeded, "limitExceeded");
            Objects.requireNonNull(finalAnswer, "finalAnswer");
            Objects.requireNonNull(afterFinal, "afterFinal");
            Objects.requireNonNull(goalEvaluation, "goalEvaluation");
            Objects.requireNonNull(afterGoal, "afterGoal");
        }

        /**
         * Single application task; outer plan orchestration owns goal evaluation and persistence.
         */
        public static Policies singleTask(
                NodeAction reasoning,
                EdgeAction afterReasoning,
                NodeAction action,
                NodeAction observation,
                EdgeAction afterObservation,
                NodeAction summarizing,
                NodeAction limitExceeded,
                NodeAction terminal) {
            return new Policies(
                    reasoning,
                    afterReasoning,
                    action,
                    observation,
                    afterObservation,
                    summarizing,
                    limitExceeded,
                    terminal,
                    state -> END,
                    state -> Map.of(),
                    state -> END);
        }
    }

    @FunctionalInterface
    public interface ScopeFactory {
        AutoCloseable open(RunState state) throws Exception;
    }

    public enum ToolNamePolicy {
        FAIL_CLOSED,
        APPLICATION_OWNER
    }

    private final ToolNamePolicy toolNamePolicy;
    private final ScopeFactory scopes;
    private final Policies policies;
    private final List<ToolSchema> schemas;
    private final int hardRoundCeiling;

    public NativePolicyDriver(
            Policies policies,
            List<ToolSchema> schemas,
            int hardRoundCeiling,
            ScopeFactory scopes) {
        this(policies, schemas, hardRoundCeiling, scopes, ToolNamePolicy.FAIL_CLOSED);
    }

    public NativePolicyDriver(
            Policies policies,
            List<ToolSchema> schemas,
            int hardRoundCeiling,
            ScopeFactory scopes,
            ToolNamePolicy toolNamePolicy) {
        this.toolNamePolicy = Objects.requireNonNull(toolNamePolicy);
        this.scopes = Objects.requireNonNull(scopes);
        this.policies = Objects.requireNonNull(policies);
        this.schemas = List.copyOf(schemas);
        if (hardRoundCeiling < 1 || hardRoundCeiling == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Finite positive native policy ceiling required");
        this.hardRoundCeiling = hardRoundCeiling;
    }

    @Override
    public Flux<RunStep> stream(Map<String, Object> inputs) {
        return run(new RunState(inputs));
    }

    /**
     * The supplied application history is copied; SDK scratch messages are never persisted here.
     */
    public Flux<RunStep> run(RunState initial) {
        return Flux.defer(
                () -> {
                    Session session = new Session(initial.snapshot());
                    RuntimeContext context = RuntimeContext.empty();
                    var toolkit =
                            new ApplicationGuardedToolkit(
                                    schemas,
                                    (calls, ignored) -> session.act(calls),
                                    toolNamePolicy == ToolNamePolicy.APPLICATION_OWNER
                                            ? ApplicationGuardedToolkit.UnknownToolPolicy
                                                    .APPLICATION_OWNER
                                            : ApplicationGuardedToolkit.UnknownToolPolicy
                                                    .FAIL_CLOSED);
                    var agent =
                            ReActAgent.builder()
                                    .name("native-policy-driver")
                                    .model(session.model())
                                    .toolkit(toolkit)
                                    .middleware(toolkit.boundaryMiddleware())
                                    .middleware(session.middleware())
                                    .maxIters(hardRoundCeiling + 1)
                                    .maxRetries(1)
                                    .enableMetaTool(false)
                                    .enablePendingToolRecovery(false)
                                    .build();
                    Flux<RunStep> execution =
                            agent.streamEvents("Internal orchestration only", context)
                                    .doOnComplete(() -> session.steps.tryEmitComplete())
                                    .thenMany(Flux.empty());
                    // The execution publisher owns errors. Emitting the same error on steps
                    // as well races the two terminal signals in merge.
                    return Flux.merge(session.steps.asFlux(), execution)
                            .doFinally(
                                    signal -> {
                                        session.cancelled.set(true);
                                        agent.close();
                                    });
                });
    }

    private final class Session {
        private final RunState state;
        private final Sinks.Many<RunStep> steps = Sinks.many().unicast().onBackpressureBuffer();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile boolean terminal;
        private int rounds;
        private String reasoningRoute;
        private final Set<String> dispatchedCallIds = new HashSet<>();
        private final Map<String, String> scratchArguments = new LinkedHashMap<>();

        private Session(RunState state) {
            this.state = state;
        }

        private void ensureRunning() {
            if (cancelled.get() || Thread.currentThread().isInterrupted())
                throw new CancellationException("Application run cancelled");
        }

        private void node(String name, NodeAction action) throws Exception {
            ensureRunning();
            long started = System.nanoTime();
            String traceId = state.value(TRACE_ID, "");
            log.info(
                    "[ReAct] node={} event=start iteration={} traceId={}",
                    name,
                    state.value(CURRENT_ITERATION, 0),
                    traceId);
            try {
                Map<String, Object> patch;
                try (AutoCloseable ignored = scopes.open(state)) {
                    patch = action.apply(state);
                }
                ensureRunning();
                state.applyPatch(patch);
                log.info(
                        "[ReAct] node={} event=complete iteration={} durationMs={} toolCallCount={}"
                                + " finishReason={} traceId={}",
                        name,
                        state.value(CURRENT_ITERATION, 0),
                        (System.nanoTime() - started) / 1_000_000,
                        state.value(TOOL_CALL_COUNT, 0),
                        state.value(FINISH_REASON, ""),
                        traceId);
                steps.tryEmitNext(new RunStep(name, state));
            } catch (Exception failure) {
                log.error(
                        "[ReAct] node={} event=error iteration={} traceId={}",
                        name,
                        state.value(CURRENT_ITERATION, 0),
                        traceId,
                        failure);
                throw failure;
            }
        }

        private String edge(EdgeAction edge) throws Exception {
            ensureRunning();
            try (AutoCloseable ignored = scopes.open(state)) {
                return edge.apply(state);
            }
        }

        private List<AssistantMessage.ToolCall> calls() {
            return state.value(TOOL_CALLS, List.<AssistantMessage.ToolCall>of());
        }

        private Model model() {
            return new Model() {
                @Override
                public String getModelName() {
                    return "application-reasoning-policy";
                }

                @Override
                public Flux<ChatResponse> stream(
                        List<Msg> scratch, List<ToolSchema> schemas, GenerateOptions ignored) {
                    return Mono.fromCallable(
                                    () -> {
                                        ensureRunning();
                                        List<ContentBlock> blocks = new ArrayList<>();
                                        scratchArguments.clear();
                                        if (terminal || !ACTION_NODE.equals(reasoningRoute)) {
                                            throw new IllegalStateException(
                                                    "SDK model requires an application tool batch");
                                        }
                                        for (AssistantMessage.ToolCall call : calls()) {
                                            if (call.id() == null
                                                    || call.id().isBlank()
                                                    || !dispatchedCallIds.add(call.id())) {
                                                throw new IllegalArgumentException(
                                                        "Tool call ID must be nonblank and unique"
                                                                + " within this run: "
                                                                + call.id());
                                            }
                                            if (call.arguments() == null)
                                                throw new IllegalArgumentException(
                                                        "Original raw tool arguments are required");
                                            String scratchContent = call.arguments();
                                            Map<String, Object> arguments;
                                            try {
                                                arguments =
                                                        TOOL_JSON.readValue(
                                                                call.arguments(),
                                                                new TypeReference<>() {});
                                                if (arguments == null)
                                                    throw new IllegalArgumentException(
                                                            "Tool arguments must be a JSON object");
                                            } catch (Exception invalidArguments) {
                                                if (toolNamePolicy == ToolNamePolicy.FAIL_CLOSED)
                                                    throw invalidArguments;
                                                // Scratch input only: the application owner
                                                // receives the exact raw content.
                                                arguments = Map.of();
                                                scratchContent = "{}";
                                            }
                                            scratchArguments.put(call.id(), scratchContent);
                                            blocks.add(
                                                    new ToolUseBlock(
                                                            call.id(),
                                                            call.name(),
                                                            arguments,
                                                            scratchContent,
                                                            null));
                                        }
                                        return ChatResponse.builder()
                                                .content(blocks)
                                                .finishReason("tool_calls")
                                                .build();
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flux();
                }
            };
        }

        private Mono<GuardedToolBatchResult> act(List<ToolUseBlock> batch) {
            return Mono.fromCallable(
                            () -> {
                                ensureRunning();
                                List<AssistantMessage.ToolCall> original = calls();
                                if (!batch.stream()
                                                .map(ToolUseBlock::getId)
                                                .toList()
                                                .equals(
                                                        original.stream()
                                                                .map(AssistantMessage.ToolCall::id)
                                                                .toList())
                                        || !batch.stream()
                                                .map(ToolUseBlock::getName)
                                                .toList()
                                                .equals(
                                                        original.stream()
                                                                .map(
                                                                        AssistantMessage.ToolCall
                                                                                ::name)
                                                                .toList())
                                        || !batch.stream()
                                                .map(ToolUseBlock::getContent)
                                                .toList()
                                                .equals(
                                                        original.stream()
                                                                .map(
                                                                        call ->
                                                                                scratchArguments
                                                                                        .get(
                                                                                                call
                                                                                                        .id()))
                                                                .toList()))
                                    throw new IllegalArgumentException(
                                            "SDK batch must preserve original IDs and raw"
                                                    + " arguments");
                                state.replace(BATCH_RESULT, null);
                                node(ACTION_NODE, policies.action());
                                GuardedToolBatchResult result =
                                        state.value(BATCH_RESULT, GuardedToolBatchResult.class)
                                                .orElseThrow();
                                node(OBSERVATION_NODE, policies.observation());
                                String stop = result.stopReason();
                                String observationRoute = edge(policies.afterObservation());
                                if (stop != null) {
                                    finish(false);
                                    terminal = true;
                                } else routeAfterObservation(observationRoute);
                                return new GuardedToolBatchResult(
                                        result.results(),
                                        result.metadata(),
                                        stop != null
                                                ? stop
                                                : terminal ? "application_terminal" : null);
                            })
                    .subscribeOn(Schedulers.boundedElastic());
        }

        private void routeAfterObservation(String route) throws Exception {
            switch (route) {
                case REASONING_NODE -> {
                    if (rounds >= hardRoundCeiling) ceiling();
                }
                case SUMMARIZING_NODE -> {
                    node(SUMMARIZING_NODE, policies.summarizing());
                    if (rounds >= hardRoundCeiling) ceiling();
                }
                case LIMIT_EXCEEDED_NODE -> {
                    node(LIMIT_EXCEEDED_NODE, policies.limitExceeded());
                    finish(true);
                }
                case FINAL_ANSWER_NODE -> finish(true);
                default ->
                        throw new IllegalArgumentException(
                                "Unsupported observation route: " + route);
            }
        }

        private void ceiling() throws Exception {
            node(LIMIT_EXCEEDED_NODE, policies.limitExceeded());
            finish(false);
            terminal = true;
        }

        /**
         * Returns true only when an application goal follow-up should enter native reasoning again.
         */
        private boolean finish(boolean allowGoalContinuation) throws Exception {
            node(FINAL_ANSWER_NODE, policies.finalAnswer());
            String finalRoute = edge(policies.afterFinal());
            if (GOAL_EVALUATION_NODE.equals(finalRoute)) {
                node(GOAL_EVALUATION_NODE, policies.goalEvaluation());
                String goalRoute = edge(policies.afterGoal());
                if (REASONING_NODE.equals(goalRoute)
                        && allowGoalContinuation
                        && rounds < hardRoundCeiling) return true;
                if (!END.equals(goalRoute) && !REASONING_NODE.equals(goalRoute))
                    throw new IllegalArgumentException("Unsupported goal route: " + goalRoute);
            } else if (!END.equals(finalRoute))
                throw new IllegalArgumentException("Unsupported final route: " + finalRoute);
            terminal = true;
            return false;
        }

        /**
         * Prepare one application policy round before entering the SDK reasoning core. Middleware
         * repeats policy-only routes without fabricating tool calls. Actual tool batches still return
         * to the SDK's native reasoning/acting iteration.
         */
        private boolean prepareReasoning() throws Exception {
            ensureRunning();
            if (terminal) return false;
            if (rounds >= hardRoundCeiling) {
                ceiling();
                return false;
            }
            rounds++;
            state.applyPatch(Map.of(TOOL_CALLS, List.of()));
            node(REASONING_NODE, policies.reasoning());
            reasoningRoute =
                    Objects.requireNonNull(
                            edge(policies.afterReasoning()),
                            "Reasoning route must be captured once");
            switch (reasoningRoute) {
                case ACTION_NODE -> {
                    if (calls().isEmpty())
                        throw new IllegalStateException("Action route requires real tool calls");
                    return false;
                }
                case REASONING_NODE -> {
                    if (rounds >= hardRoundCeiling) ceiling();
                    return !terminal;
                }
                case SUMMARIZING_NODE -> {
                    node(SUMMARIZING_NODE, policies.summarizing());
                    if (rounds >= hardRoundCeiling) ceiling();
                    return !terminal;
                }
                case LIMIT_EXCEEDED_NODE -> {
                    node(LIMIT_EXCEEDED_NODE, policies.limitExceeded());
                    return finish(true);
                }
                case FINAL_ANSWER_NODE -> {
                    return finish(true);
                }
                default ->
                        throw new IllegalArgumentException(
                                "Unsupported reasoning route: " + reasoningRoute);
            }
        }

        private MiddlewareBase middleware() {
            return new MiddlewareBase() {
                @Override
                public Flux<AgentEvent> onAgent(
                        Agent agent,
                        RuntimeContext context,
                        AgentInput input,
                        Function<AgentInput, Flux<AgentEvent>> next) {
                    return next.apply(input).doOnCancel(() -> cancelled.set(true));
                }

                @Override
                public Flux<AgentEvent> onReasoning(
                        Agent agent,
                        RuntimeContext context,
                        ReasoningInput input,
                        Function<ReasoningInput, Flux<AgentEvent>> next) {
                    return Mono.fromCallable(Session.this::prepareReasoning)
                            .subscribeOn(Schedulers.boundedElastic())
                            .repeat(() -> !terminal && !ACTION_NODE.equals(reasoningRoute))
                            .thenMany(Flux.defer(() -> {
                                if (!terminal) return next.apply(input);
                                // FinalAnswerNode already published the application result.
                                return Flux.just(new RequestStopEvent("application_terminal",
                                        GenerateReason.MIDDLEWARE_STOP_REQUESTED));
                            }));
                }
            };
        }
    }
}
