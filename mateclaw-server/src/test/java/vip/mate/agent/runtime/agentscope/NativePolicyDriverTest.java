package vip.mate.agent.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ToolSchema;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import reactor.core.publisher.Flux;

import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.RunStep;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.tool.builtin.ToolExecutionContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class NativePolicyDriverTest {
    @Test
    void nativeRuntimeDoesNotLinkToForRemovalHookApi() throws Exception {
        var types = new java.util.ArrayDeque<Class<?>>();
        types.add(NativePolicyDriver.class);
        while (!types.isEmpty()) {
            Class<?> type = types.removeFirst();
            types.addAll(List.of(type.getDeclaredClasses()));
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertNotNull(bytes);
                String constants = new String(bytes.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(constants.contains("io/agentscope/core/hook/"),
                        type.getName() + " must use supported middleware rather than for-removal hooks");
            }
        }
    }

    private static final List<ToolSchema> SCHEMAS =
            List.of(
                    ToolSchema.builder()
                            .name("real_tool")
                            .description("Schema only")
                            .parameters(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of("number", Map.of("type", "integer"))))
                            .build());

    private static final class Fixture {
        final String mode;
        final AtomicInteger reasoning = new AtomicInteger(),
                rawLlm = new AtomicInteger(),
                actions = new AtomicInteger(),
                observations = new AtomicInteger(),
                summaries = new AtomicInteger(),
                finals = new AtomicInteger(),
                goals = new AtomicInteger(),
                scopeOpened = new AtomicInteger(),
                scopeClosed = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                closedAfterCancel = new CountDownLatch(1);
        final ChatOrigin origin =
                ChatOrigin.EMPTY
                        .withConversationId("captured-conversation")
                        .withWorkspace(9L, "/captured-workspace");

        Fixture(String mode) {
            this.mode = mode;
        }

        RunState initial() {
            var values = new LinkedHashMap<String, Object>();
            values.put(MESSAGES, List.of(new UserMessage("original user")));
            values.put(CHAT_ORIGIN, origin);
            values.put("requested_thinking_level", "high");
            if (mode.equals("forced"))
                values.put(
                        FORCED_TOOL_CALL,
                        "{\"id\":\"forced-original\",\"type\":\"function\",\"name\":\"real_tool\",\"arguments\":\"{"
                            + " \\\"number\\\" : 7 }\"}");
            return new RunState(values);
        }

        NativePolicyDriver driver(int cap) {
            NativePolicyDriver.Policies policies =
                    new NativePolicyDriver.Policies(
                            state -> {
                                scoped();
                                int round = reasoning.incrementAndGet();
                                List<Message> history = state.value(MESSAGES, List.<Message>of());
                                assertTrue(
                                        history.getFirst().getText().equals("original user"),
                                        "App prompt history is authority");
                                if (mode.equals("cancel")) {
                                    entered.countDown();
                                    while (release.getCount() != 0) {
                                        try {
                                            release.await();
                                        } catch (InterruptedException ignored) {
                                        }
                                    }
                                }
                                String forced = state.value(FORCED_TOOL_CALL, "");
                                boolean tool =
                                        (mode.equals("tool")
                                                                || mode.equals("approval")
                                                                || mode.equals("direct")
                                                                || mode.startsWith("owner-")
                                                                || mode.equals("stale-tool")
                                                                || mode.startsWith(
                                                                        "requested-limit")
                                                                || mode.equals("tool-goal")
                                                                || mode.equals("tool-soft-limit")
                                                                || mode.equals("nullable")
                                                                || mode.startsWith("invalid"))
                                                        && round == 1
                                                || !forced.isBlank();
                                List<AssistantMessage.ToolCall> calls;
                                if (!forced.isBlank())
                                    calls =
                                            List.of(
                                                    new ObjectMapper()
                                                            .readValue(
                                                                    forced,
                                                                    AssistantMessage.ToolCall
                                                                            .class));
                                else {
                                    rawLlm.incrementAndGet();
                                    String arguments =
                                            switch (mode) {
                                                case "invalid-null" -> "null";
                                                case "invalid-array" -> "[]";
                                                case "owner-malformed", "invalid-json" -> "{";
                                                case "invalid-duplicate" ->
                                                        "{\"number\":1,\"number\":2}";
                                                case "nullable" ->
                                                        "{\"nullable\":null,\"number\":7}";
                                                default -> "{ \"number\" : 7 }";
                                            };
                                    calls =
                                            tool
                                                    ? List.of(
                                                            new AssistantMessage.ToolCall(
                                                                    "original-id",
                                                                    "function",
                                                                    mode.equals("owner-unknown")
                                                                            ? "unknown"
                                                                            : "real_tool",
                                                                    arguments))
                                                    : List.of();
                                }
                                if ((mode.equals("soft-limit") || mode.equals("tool-soft-limit"))
                                        && round == 2)
                                    assertTrue(
                                            state.value(CURRENT_ITERATION, -1) == 0,
                                            "Goal grant resets business iteration before next"
                                                    + " reasoning");
                                String route =
                                        tool
                                                ? (mode.startsWith("requested-limit")
                                                        ? LIMIT_EXCEEDED_NODE
                                                        : ACTION_NODE)
                                                : mode.equals("soft-limit") && round == 1
                                                        ? LIMIT_EXCEEDED_NODE
                                                        : mode.equals("finite")
                                                                        || (mode.equals("continue")
                                                                                        || mode
                                                                                                .equals(
                                                                                                        "empty-continue"))
                                                                                && round == 1
                                                                ? REASONING_NODE
                                                                : mode.equals("summary")
                                                                                && round == 1
                                                                        ? SUMMARIZING_NODE
                                                                        : FINAL_ANSWER_NODE;
                                var patch = new LinkedHashMap<String, Object>();
                                if (!(mode.equals("stale-tool") && round > 1))
                                    patch.put(TOOL_CALLS, calls);
                                patch.put(FORCED_TOOL_CALL, "");
                                patch.put("route", route);
                                patch.put(
                                        MESSAGES,
                                        List.of(
                                                AssistantMessage.builder()
                                                        .content(
                                                                tool
                                                                                || (mode.equals(
                                                                                                        "empty-continue")
                                                                                                || mode
                                                                                                        .equals(
                                                                                                                "empty-final"))
                                                                                        && round
                                                                                                == 1
                                                                        ? ""
                                                                        : "answer-" + round)
                                                        .toolCalls(calls)
                                                        .build()));
                                return patch;
                            },
                            state -> {
                                scoped();
                                return state.value("route", FINAL_ANSWER_NODE);
                            },
                            state -> {
                                scoped();
                                actions.incrementAndGet();
                                List<AssistantMessage.ToolCall> calls =
                                        state.value(
                                                TOOL_CALLS, List.<AssistantMessage.ToolCall>of());
                                assertTrue(calls.size() == 1, "Whole batch owner");
                                var call = calls.getFirst();
                                assertTrue(
                                        mode.equals("owner-malformed")
                                                ? call.arguments().equals("{")
                                                : mode.equals("nullable")
                                                        ? call.arguments()
                                                                .equals(
                                                                        "{\"nullable\":null,\"number\":7}")
                                                        : call.arguments()
                                                                .equals("{ \"number\" : 7 }"),
                                        "Original raw JSON");
                                String stop =
                                        mode.equals("approval")
                                                ? "awaiting_approval"
                                                : mode.equals("direct") ? "direct_output" : null;
                                var result =
                                        new GuardedToolBatchResult(
                                                List.of(
                                                        new ToolResultBlock(
                                                                call.id(),
                                                                call.name(),
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text(
                                                                                        "product-payload")
                                                                                .build()),
                                                                Map.of(
                                                                        "structured",
                                                                        Map.of("answer", 42)),
                                                                stop == null
                                                                                && !mode.startsWith(
                                                                                        "owner-")
                                                                        ? ToolResultState.SUCCESS
                                                                        : ToolResultState.ERROR)),
                                                Map.of(
                                                        "authoritative-result",
                                                        "full-product-result"),
                                                stop);
                                return Map.of(
                                        NativePolicyDriver.BATCH_RESULT,
                                        result,
                                        MESSAGES,
                                        List.of(
                                                ToolResponseMessage.builder()
                                                        .responses(
                                                                List.of(
                                                                        new ToolResponseMessage
                                                                                .ToolResponse(
                                                                                call.id(),
                                                                                call.name(),
                                                                                "product-payload")))
                                                        .build()));
                            },
                            state -> {
                                scoped();
                                observations.incrementAndGet();
                                return Map.of("observation", "observed");
                            },
                            state -> {
                                scoped();
                                if (mode.equals("tool-soft-limit")) return LIMIT_EXCEEDED_NODE;
                                return mode.equals("forced")
                                                || mode.equals("approval")
                                                || mode.equals("direct")
                                                || mode.equals("tool-goal")
                                        ? FINAL_ANSWER_NODE
                                        : SUMMARIZING_NODE;
                            },
                            state -> {
                                scoped();
                                summaries.incrementAndGet();
                                return Map.of("summary", "app-only-summary");
                            },
                            state -> {
                                scoped();
                                return Map.of("ceiling-hit", true);
                            },
                            state -> {
                                scoped();
                                finals.incrementAndGet();
                                return Map.of(FINAL_ANSWER, "authoritative-final-" + finals.get());
                            },
                            state -> {
                                scoped();
                                return (mode.equals("requested-limit-goal")
                                                        || mode.equals("goal")
                                                        || mode.equals("tool-goal")
                                                        || mode.equals("soft-limit")
                                                        || mode.equals("tool-soft-limit"))
                                                && goals.get() == 0
                                        ? GOAL_EVALUATION_NODE
                                        : NativePolicyDriver.END;
                            },
                            state -> {
                                scoped();
                                goals.incrementAndGet();
                                return Map.of("goal-followup", true, CURRENT_ITERATION, 0);
                            },
                            state -> {
                                scoped();
                                return REASONING_NODE;
                            });
            return new NativePolicyDriver(
                    policies,
                    SCHEMAS,
                    cap,
                    state -> {
                        String previous = ThinkingLevelHolder.get();
                        ChatOrigin previousOrigin = ToolExecutionContext.origin();
                        scopeOpened.incrementAndGet();
                        ThinkingLevelHolder.set(state.value("requested_thinking_level", ""));
                        ToolExecutionContext.setOrigin(state.value(CHAT_ORIGIN, ChatOrigin.EMPTY));
                        return () -> {
                            if (previous == null) ThinkingLevelHolder.clear();
                            else ThinkingLevelHolder.set(previous);
                            ToolExecutionContext.setOrigin(previousOrigin);
                            assertTrue(
                                    Objects.equals(previous, ThinkingLevelHolder.get()),
                                    "Worker thinking restored");
                            assertTrue(
                                    previousOrigin == ToolExecutionContext.origin(),
                                    "Worker origin restored");
                            scopeClosed.incrementAndGet();
                            if (mode.equals("cancel")) closedAfterCancel.countDown();
                        };
                    },
                    mode.startsWith("owner-")
                            ? NativePolicyDriver.ToolNamePolicy.APPLICATION_OWNER
                            : NativePolicyDriver.ToolNamePolicy.FAIL_CLOSED);
        }

        void scoped() {
            assertTrue(
                    "high".equals(ThinkingLevelHolder.get()),
                    "Captured thinking visible on async policy worker");
            assertTrue(
                    origin.equals(ToolExecutionContext.origin()),
                    "Captured ChatOrigin visible on worker");
        }

        List<RunStep> run(int cap) {
            List<RunStep> steps =
                    driver(cap).run(initial()).collectList().block(Duration.ofSeconds(5));
            assertTrue(scopeOpened.get() == scopeClosed.get(), "All policy scopes closed");
            return steps;
        }
    }

    private static void nodes(List<RunStep> steps, String... expected) {
        assertTrue(
                steps.stream().map(RunStep::node).toList().equals(List.of(expected)),
                "Ordered RunSteps: " + steps.stream().map(RunStep::node).toList());
    }

    @Test
    void repeatedToolIdStopsBeforeSdkSilentlySuppressesTheNextBatch() {
        AtomicInteger rounds = new AtomicInteger(), owners = new AtomicInteger();
        var call = new AssistantMessage.ToolCall("repeated-id", "function", "real_tool", "{}");
        var policies =
                NativePolicyDriver.Policies.singleTask(
                        state -> {
                            rounds.incrementAndGet();
                            return Map.of(TOOL_CALLS, List.of(call));
                        },
                        state -> ACTION_NODE,
                        state -> {
                            owners.incrementAndGet();
                            return Map.of(
                                    NativePolicyDriver.BATCH_RESULT,
                                    new GuardedToolBatchResult(
                                            List.of(
                                                    new ToolResultBlock(
                                                            call.id(),
                                                            call.name(),
                                                            List.of(
                                                                    TextBlock.builder()
                                                                            .text("result")
                                                                            .build()),
                                                            Map.of(),
                                                            ToolResultState.SUCCESS)),
                                            Map.of(),
                                            null));
                        },
                        state -> Map.of(),
                        state -> REASONING_NODE,
                        state -> Map.of(),
                        state -> Map.of(),
                        state -> Map.of());
        var driver = new NativePolicyDriver(policies, SCHEMAS, 4, state -> () -> {});
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                driver.run(new RunState())
                                        .collectList()
                                        .block(Duration.ofSeconds(5)));
        assertTrue(failure.getMessage().contains("repeated-id"));
        assertEquals(2, rounds.get());
        assertEquals(1, owners.get());
    }

    @Test
    void omittedNextBatchResultStopsBeforeObservationOrFinal() {
        AtomicInteger rounds = new AtomicInteger(),
                owners = new AtomicInteger(),
                finals = new AtomicInteger(),
                observations = new AtomicInteger();
        var call = new AssistantMessage.ToolCall("repeated-id", "function", "real_tool", "{}");
        var policies =
                NativePolicyDriver.Policies.singleTask(
                        state -> {
                            int round = rounds.incrementAndGet();
                            return Map.of(
                                    TOOL_CALLS,
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "round-" + round,
                                                    "function",
                                                    "real_tool",
                                                    "{}")));
                        },
                        state -> ACTION_NODE,
                        state ->
                                owners.incrementAndGet() == 1
                                        ? Map.of(
                                                NativePolicyDriver.BATCH_RESULT,
                                                new GuardedToolBatchResult(
                                                        List.of(
                                                                new ToolResultBlock(
                                                                        "round-1",
                                                                        call.name(),
                                                                        List.of(
                                                                                TextBlock.builder()
                                                                                        .text(
                                                                                                "first-result")
                                                                                        .build()),
                                                                        Map.of(),
                                                                        ToolResultState.SUCCESS)),
                                                        Map.of(),
                                                        null))
                                        : Map.of(),
                        state -> {
                            observations.incrementAndGet();
                            return Map.of();
                        },
                        state -> REASONING_NODE,
                        state -> Map.of(),
                        state -> Map.of(),
                        state -> {
                            finals.incrementAndGet();
                            return Map.of();
                        });
        var driver = new NativePolicyDriver(policies, SCHEMAS, 4, state -> () -> {});
        assertThrows(
                NoSuchElementException.class,
                () -> driver.run(new RunState()).collectList().block(Duration.ofSeconds(5)));
        assertEquals(1, observations.get());
        assertEquals(2, owners.get());
        assertEquals(2, rounds.get());
        assertEquals(0, finals.get());
    }

    @Test
    void productOwnerReceivesUnknownNameOnceAndModelCanRetry() {
        Fixture fixture = new Fixture("owner-unknown");
        nodes(
                fixture.run(4),
                REASONING_NODE,
                ACTION_NODE,
                OBSERVATION_NODE,
                SUMMARIZING_NODE,
                REASONING_NODE,
                FINAL_ANSWER_NODE);
        assertEquals(1, fixture.actions.get());
        assertEquals(2, fixture.reasoning.get());
    }

    @Test
    void productOwnerReceivesMalformedRawJsonOnceAndModelCanRetry() {
        Fixture fixture = new Fixture("owner-malformed");
        nodes(
                fixture.run(4),
                REASONING_NODE,
                ACTION_NODE,
                OBSERVATION_NODE,
                SUMMARIZING_NODE,
                REASONING_NODE,
                FINAL_ANSWER_NODE);
        assertEquals(1, fixture.actions.get());
        assertEquals(2, fixture.reasoning.get());
    }

    @Test
    void finalPatchWithoutToolCallsDoesNotReplayPreviousBatch() {
        Fixture fixture = new Fixture("stale-tool");
        nodes(
                fixture.run(4),
                REASONING_NODE,
                ACTION_NODE,
                OBSERVATION_NODE,
                SUMMARIZING_NODE,
                REASONING_NODE,
                FINAL_ANSWER_NODE);
        assertEquals(1, fixture.actions.get());
        assertEquals(2, fixture.reasoning.get());
    }

    @Test
    void requestedToolsAtSoftLimitAreNotExecuted() {
        Fixture fixture = new Fixture("requested-limit");
        nodes(fixture.run(4), REASONING_NODE, LIMIT_EXCEEDED_NODE, FINAL_ANSWER_NODE);
        assertEquals(0, fixture.actions.get());
    }

    @Test
    void requestedToolsAtSoftLimitAllowGoalFollowupWithoutExecution() {
        Fixture fixture = new Fixture("requested-limit-goal");
        nodes(
                fixture.run(4),
                REASONING_NODE,
                LIMIT_EXCEEDED_NODE,
                FINAL_ANSWER_NODE,
                GOAL_EVALUATION_NODE,
                REASONING_NODE,
                FINAL_ANSWER_NODE);
        assertEquals(0, fixture.actions.get());
        assertEquals(2, fixture.reasoning.get());
    }

    @Test
    void toolRoundUsesOneBatchAndDetachedApplicationHistory() {
        Fixture tool = new Fixture("tool");
        var toolSteps = tool.run(4);
        nodes(
                toolSteps,
                REASONING_NODE,
                ACTION_NODE,
                OBSERVATION_NODE,
                SUMMARIZING_NODE,
                REASONING_NODE,
                FINAL_ANSWER_NODE);
        assertTrue(
                tool.actions.get() == 1 && tool.observations.get() == 1 && tool.rawLlm.get() == 2,
                "One batch / observation / next reasoning");
        List<Message> appHistory = toolSteps.getLast().state().value(MESSAGES, List.<Message>of());
        assertTrue(
                appHistory.size() == 4,
                "App history appended once only; SDK scratch not persisted");
        assertTrue(
                toolSteps.getFirst().state().<List<Message>>value(MESSAGES).orElseThrow().size()
                        == 2,
                "RunStep is immutable snapshot");
    }

    @Test
    void noToolContinuationUsesNativeLoop() {
        Fixture continued = new Fixture("continue");
        nodes(continued.run(4), REASONING_NODE, REASONING_NODE, FINAL_ANSWER_NODE);
        assertTrue(
                continued.rawLlm.get() == 2 && continued.actions.get() == 0,
                "Native no-tool continuation");
    }

    @Test
    void emptyRoundContinuesWithoutPersistingInternalMarker() {
        Fixture empty = new Fixture("empty-continue");
        var emptySteps = empty.run(4);
        nodes(emptySteps, REASONING_NODE, REASONING_NODE, FINAL_ANSWER_NODE);
        assertTrue(
                empty.rawLlm.get() == 2,
                "No visible text can still continue without invented tool");
        assertTrue(
                emptySteps.getLast().state().<List<Message>>value(MESSAGES).orElseThrow().stream()
                        .noneMatch(
                                message -> message.getText().contains("Application policy round")),
                "Internal SDK marker never stored in app history");
    }

    @Test
    void emptyRoundStillRunsFinalPolicy() {
        Fixture emptyFinal = new Fixture("empty-final");
        nodes(emptyFinal.run(4), REASONING_NODE, FINAL_ANSWER_NODE);
        assertTrue(
                emptyFinal.rawLlm.get() == 1,
                "Fully empty policy round still reaches final policy");
    }

    @Test
    void summaryPolicyReturnsToNativeReasoning() {
        Fixture summary = new Fixture("summary");
        nodes(summary.run(4), REASONING_NODE, SUMMARIZING_NODE, REASONING_NODE, FINAL_ANSWER_NODE);
    }

    @Test
    void hardCeilingPreventsSdkSummaryCalls() {
        Fixture finite = new Fixture("finite");
        nodes(
                finite.run(2),
                REASONING_NODE,
                REASONING_NODE,
                LIMIT_EXCEEDED_NODE,
                FINAL_ANSWER_NODE);
        assertTrue(finite.rawLlm.get() == 2, "No SDK summary beyond hard ceiling");
    }

    @Test
    void approvalAndDirectStopWithoutAnotherModel() {
        for (String mode : List.of("approval", "direct")) {
            Fixture stopped = new Fixture(mode);
            var steps = stopped.run(1);
            nodes(steps, REASONING_NODE, ACTION_NODE, OBSERVATION_NODE, FINAL_ANSWER_NODE);
            assertTrue(
                    stopped.rawLlm.get() == 1 && stopped.actions.get() == 1,
                    "Authoritative stop no extra model/action");
        }
    }

    @Test
    void goalFollowupContinuesNatively() {
        Fixture goal = new Fixture("goal");
        nodes(
                goal.run(4),
                REASONING_NODE,
                FINAL_ANSWER_NODE,
                GOAL_EVALUATION_NODE,
                REASONING_NODE,
                FINAL_ANSWER_NODE);
        assertTrue(
                goal.goals.get() == 1 && goal.rawLlm.get() == 2,
                "Goal follow-up uses native continuation");
    }

    @Test
    void goalFollowupAfterToolFinalContinuesNatively() {
        Fixture toolGoal = new Fixture("tool-goal");
        nodes(
                toolGoal.run(4),
                REASONING_NODE,
                ACTION_NODE,
                OBSERVATION_NODE,
                FINAL_ANSWER_NODE,
                GOAL_EVALUATION_NODE,
                REASONING_NODE,
                FINAL_ANSWER_NODE);
        assertTrue(
                toolGoal.goals.get() == 1
                        && toolGoal.rawLlm.get() == 2
                        && toolGoal.actions.get() == 1,
                "Goal follows tool batch final exactly once");
    }

    @Test
    void softLimitGoalGrantResetsBusinessBudget() {
        Fixture soft = new Fixture("soft-limit");
        nodes(
                soft.run(4),
                REASONING_NODE,
                LIMIT_EXCEEDED_NODE,
                FINAL_ANSWER_NODE,
                GOAL_EVALUATION_NODE,
                REASONING_NODE,
                FINAL_ANSWER_NODE);
        assertTrue(
                soft.rawLlm.get() == 2 && soft.goals.get() == 1,
                "Soft limit permits goal grant continuation");
    }

    @Test
    void toolSoftLimitGoalGrantContinuesNatively() {
        Fixture toolSoft = new Fixture("tool-soft-limit");
        nodes(
                toolSoft.run(4),
                REASONING_NODE,
                ACTION_NODE,
                OBSERVATION_NODE,
                LIMIT_EXCEEDED_NODE,
                FINAL_ANSWER_NODE,
                GOAL_EVALUATION_NODE,
                REASONING_NODE,
                FINAL_ANSWER_NODE);
        assertTrue(
                toolSoft.actions.get() == 1 && toolSoft.rawLlm.get() == 2,
                "After-tool soft limit permits goal continuation");
    }

    @Test
    void goalGrantCannotBypassNativeHardCeiling() {
        Fixture hardGoal = new Fixture("soft-limit");
        nodes(
                hardGoal.run(1),
                REASONING_NODE,
                LIMIT_EXCEEDED_NODE,
                FINAL_ANSWER_NODE,
                GOAL_EVALUATION_NODE);
        assertTrue(
                hardGoal.rawLlm.get() == 1 && hardGoal.goals.get() == 1,
                "Goal cannot bypass global native hard ceiling");
    }

    @Test
    void nullableObjectArgumentsPreserveRawJson() {
        Fixture nullable = new Fixture("nullable");
        nullable.run(4);
        assertTrue(
                nullable.actions.get() == 1 && nullable.rawLlm.get() == 2,
                "Nullable JSON object properties retain raw args and execute once");
    }

    @Test
    void invalidArgumentsFailBeforeEffects() {
        for (String mode :
                List.of("invalid-null", "invalid-array", "invalid-json", "invalid-duplicate")) {
            Fixture invalid = new Fixture(mode);
            assertThrows(RuntimeException.class, () -> invalid.run(4));
            assertTrue(
                    invalid.actions.get() == 0 && invalid.reasoning.get() == 1,
                    "Invalid JSON never reaches effects or repeats reasoning");
        }
    }

    @Test
    void subscriptionsOwnIndependentStateAndNativeAgents() {
        Fixture cold = new Fixture("forced");
        RunState untouched = cold.initial();
        Flux<RunStep> reusable = cold.driver(2).run(untouched);
        assertTrue(cold.reasoning.get() == 0, "Cold policy driver");
        reusable.collectList().block(Duration.ofSeconds(5));
        reusable.collectList().block(Duration.ofSeconds(5));
        assertTrue(
                cold.actions.get() == 2
                        && cold.rawLlm.get() == 0
                        && !untouched.value(FORCED_TOOL_CALL, "").isBlank()
                        && untouched.<List<Message>>value(MESSAGES).orElseThrow().size() == 1,
                "Each subscription owns new SDK agent and app state");
    }

    @Test
    void forcedReplaySkipsUnderlyingModel() {
        Fixture forced = new Fixture("forced");
        nodes(forced.run(2), REASONING_NODE, ACTION_NODE, OBSERVATION_NODE, FINAL_ANSWER_NODE);
        assertTrue(
                forced.rawLlm.get() == 0
                        && forced.reasoning.get() == 1
                        && forced.actions.get() == 1,
                "Forced tool policy skips underlying model and preserves call ID");
    }

    @Test
    void cancellationClosesScopeAndStopsDownstreamPolicies() throws Exception {
        Fixture cancelled = new Fixture("cancel");
        List<RunStep> emitted = new CopyOnWriteArrayList<>();
        var subscription =
                cancelled.driver(4).run(cancelled.initial()).subscribe(emitted::add, error -> {});
        assertTrue(cancelled.entered.await(3, TimeUnit.SECONDS), "Reasoning starts");
        subscription.dispose();
        cancelled.release.countDown();
        assertTrue(
                cancelled.closedAfterCancel.await(3, TimeUnit.SECONDS),
                "Cancelled policy scope closes");
        assertTrue(
                emitted.isEmpty() && cancelled.actions.get() == 0 && cancelled.finals.get() == 0,
                "Cancel prevents downstream policies and snapshots");
        assertTrue(
                ThinkingLevelHolder.get() == null && ToolExecutionContext.origin() == null,
                "Caller holders remain untouched");
    }

    @Test
    void agentRuntimeStreamAndInvokeKeepInputHistoryDetached() {
        Fixture fixture = new Fixture("forced");
        RunState initial = fixture.initial();
        NativePolicyDriver driver = fixture.driver(2);
        var steps = driver.stream(initial.data()).collectList().block(Duration.ofSeconds(5));
        nodes(steps, REASONING_NODE, ACTION_NODE, OBSERVATION_NODE, FINAL_ANSWER_NODE);
        assertEquals(
                "authoritative-final-2",
                driver.invoke(initial.data()).orElseThrow().value(FINAL_ANSWER, ""));
        assertEquals(2, fixture.actions.get());
        assertEquals(0, fixture.rawLlm.get());
        assertEquals(1, initial.<List<Message>>value(MESSAGES).orElseThrow().size());
        assertFalse(initial.value(FORCED_TOOL_CALL, "").isBlank());
    }

    @Test
    void requiredPolicyCallbacksAndFiniteCeilingAreValidated() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new NativePolicyDriver.Policies(
                                null,
                                state -> NativePolicyDriver.END,
                                state -> Map.of(),
                                state -> Map.of(),
                                state -> NativePolicyDriver.END,
                                state -> Map.of(),
                                state -> Map.of(),
                                state -> Map.of(),
                                state -> NativePolicyDriver.END,
                                state -> Map.of(),
                                state -> NativePolicyDriver.END));
        var policies =
                NativePolicyDriver.Policies.singleTask(
                        state -> Map.of(),
                        state -> NativePolicyDriver.END,
                        state -> Map.of(),
                        state -> Map.of(),
                        state -> NativePolicyDriver.END,
                        state -> Map.of(),
                        state -> Map.of(),
                        state -> Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativePolicyDriver(policies, SCHEMAS, 0, state -> () -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NativePolicyDriver(
                                policies, SCHEMAS, Integer.MAX_VALUE, state -> () -> {}));
    }
}
