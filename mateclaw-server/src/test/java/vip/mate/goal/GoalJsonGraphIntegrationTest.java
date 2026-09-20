package vip.mate.goal;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import vip.mate.MateClawApplication;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.graph.StateGraphReActAgent;
import vip.mate.goal.model.*;
import vip.mate.goal.service.*;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.tool.ToolRegistry;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real compiled graph and callback executor; model choices are a deterministic offline fixture. */
@SpringBootTest(classes = MateClawApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:json_graph_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key", "spring.main.web-application-type=none",
        "mateclaw.goal.enabled=true", "mateclaw.plugin.enabled=false", "mateclaw.skill.workspace.auto-init=false",
        "mateclaw.skill.workspace.root=${java.io.tmpdir}/mateclaw-json-graph-skills-${random.uuid}"
})
class GoalJsonGraphIntegrationTest {
    @MockBean private MemoryManager memory;
    @MockBean private GoalEvaluationService evaluator;
    @MockBean private GoalContinuationSupervisor supervisor;
    @Autowired private GoalService goals;
    @Autowired private GoalJsonAcceptanceService requirements;
    @Autowired private GoalJsonBindingService bindings;
    @Autowired private GoalContinuationStore continuations;
    @Autowired private GoalRunCoordinator coordinator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private AgentGraphBuilder builder;
    @Autowired private ToolRegistry tools;
    @Autowired private ConversationService conversations;
    @Autowired private ConversationWindowManager window;
    @Autowired private vip.mate.planning.service.PlanningService planning;
    @Autowired private vip.mate.agent.progress.ProgressLedgerService progress;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
            "false,false,true", "false,true,true", "true,false,true", "true,true,true"})
    void realGraphsPreserveAuthenticatedOriginThroughReadPublishCheckAndComplete(boolean plan, boolean scheduled, boolean automatic) throws Exception {
        Fixture fixture = configuredGoal(scheduled);
        String username = fixture.username(); String conversation = fixture.conversation();
        GoalEntity goal = fixture.goal(); GoalRunCoordinator.ClaimedRun run = fixture.run(); ChatOrigin origin = fixture.origin();
        when(evaluator.evaluate(any(), anyList(), anyString())).thenReturn(automatic
                ? new GoalEvaluationResult(1, "offline graph semantic verdict", "completed", true, "fixture", 1, 0,
                    List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "fixture only")), null)
                : GoalEvaluationResult.fallback("offline_graph_fixture"));
        var toolSet = tools.getEnabledToolSet().withAllowedToolsOnly(Set.of("getManagedGoalJsonSlots", "publishManagedGoalJson", "checkManagedGoalJson", "completeGoal"));
        assertEquals(4, toolSet.callbacks().size());
        ChatModel model = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> revision = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.stubbing.Answer<ChatResponse> script = invocation -> {
            Prompt prompt = invocation.getArgument(0);
            int step = calls.getAndIncrement();
            if (plan && step == 0) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"needs_planning\":true,\"steps\":[\"Produce, publish, check and complete the managed JSON report\"]}"))));
            }
            if (plan) step--;
            List<ToolResponseMessage.ToolResponse> responses = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(m -> m.getResponses().stream()).toList();
            JsonNode last = responses.isEmpty() ? null : json.readTree(responses.getLast().responseData());
            String name; String arguments = "{}";
            switch (step) {
                case 0 -> name = "completeGoal";
                case 1 -> { assertTrue(last.path("error").asBoolean(), String.valueOf(last)); name = "getManagedGoalJsonSlots"; }
                case 2 -> {
                    assertTrue(last.path("required").asBoolean(), String.valueOf(last));
                    revision.set(last.path("requirements").get(0).path("revision").asText());
                    name = "publishManagedGoalJson";
                    arguments = json.writeValueAsString(Map.of("artifactSlot", "report", "expectedGeneration", "0", "jsonContent", "{\"summary\":false}"));
                }
                case 3 -> {
                    assertEquals(scheduled ? "goal-attempt" : "account-runtime", last.path("producerKind").asText(), String.valueOf(last));
                    name = "checkManagedGoalJson";
                    arguments = json.writeValueAsString(Map.of("criterionKey", "r", "expectedRequirementRevision", revision.get(),
                            "artifactId", last.path("artifactId").asText(), "expectedGeneration", last.path("generation").asText()));
                }
                case 4 -> {
                    assertTrue(last.path("acceptanceEligible").asBoolean(), String.valueOf(last));
                    if (automatic) return new ChatResponse(List.of(new Generation(new AssistantMessage("Managed JSON is ready for final validation."))));
                    name = "completeGoal";
                }
                default -> {
                    if (!automatic) {
                        if (last != null) assertEquals("completed", last.path("status").asText(), String.valueOf(last));
                        assertEquals(GoalStatus.COMPLETED, goals.getById(goal.getId()).getStatus());
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Managed JSON fixture completed."))));
                }
            }
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("json-" + step, "function", name, arguments))).build())));
        };
        when(model.call(any(Prompt.class))).thenAnswer(script);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(script.answer(invocation)));
        var agent = graphAgent(plan, toolSet, model);
        ChatOriginHolder.set(origin);
        try {
            if (automatic) {
                var deltas = structured(agent, "Produce and check the managed JSON report.", conversation);
                var completed = deltas.stream().filter(d -> "goal_completed".equals(d.eventType())).toList();
                assertEquals(1, completed.size(), "One committed completion event");
                var snapshot = (Map<?, ?>) completed.getFirst().eventData().get("goal");
                assertEquals(Boolean.TRUE, snapshot.get("jsonAcceptanceRequired"), "SSE must preserve the selected acceptance protocol");
            } else assertNotNull(agent.chat("Produce and check the managed JSON report.", conversation));
        }
        finally { ChatOriginHolder.clear(); }
        assertEquals(GoalStatus.COMPLETED, goals.getById(goal.getId()).getStatus());
        assertTrue(bindings.state(goal.getId(), username).getFirst().acceptanceEligible());
        if (scheduled) {
            assertTrue(coordinator.settle(run, new SegmentOutcome.Complete("graph fixture"), java.time.LocalDateTime.now()));
            assertEquals("completed", continuations.get(goal.getId()).state());
        }
        assertFalse(progress.load(conversation).asMap().containsKey("auto_getManagedGoalJsonSlots"),
                "Current acceptance reads must not become a permanent done step that discourages reloading");
        assertFalse(progress.load(conversation).asMap().containsKey("auto_checkManagedGoalJson"),
                "A time-bound JSON binding must not become a done step that discourages rechecking in this tool loop");
        assertTrue(calls.get() >= (automatic ? 5 : 6) && calls.get() <= 10, "Bounded scripted model calls: " + calls.get());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void automaticGraphCannotPromoteAPassingSemanticVerdictWithoutManagedBytes(boolean plan, boolean scheduled) throws Exception {
        Fixture fixture = configuredGoal(scheduled);
        when(evaluator.evaluate(any(), anyList(), anyString())).thenReturn(new GoalEvaluationResult(
                1, "PASS from offline semantic fixture", "completed", true, "fixture", 1, 0,
                List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "fixture only")), null));
        var toolSet = tools.getEnabledToolSet().withAllowedToolsOnly(Set.of("getManagedGoalJsonSlots", "publishManagedGoalJson", "checkManagedGoalJson", "completeGoal"));
        ChatModel model = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        org.mockito.stubbing.Answer<ChatResponse> script = invocation -> {
            int step = calls.getAndIncrement();
            if (plan && step == 0) return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"needs_planning\":true,\"steps\":[\"Read current managed JSON requirements and report status\"]}"))));
            if (plan) step--;
            if (step == 0) return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("read-current", "function", "getManagedGoalJsonSlots", "{}"))).build())));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("PASS. All requirements are completed."))));
        };
        when(model.call(any(Prompt.class))).thenAnswer(script);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(script.answer(invocation)));
        var agent = graphAgent(plan, toolSet, model);
        ChatOriginHolder.set(fixture.origin());
        try {
            var deltas = structured(agent, "Read the current managed JSON requirements and report status.", fixture.conversation());
            assertTrue(deltas.stream().noneMatch(d -> "goal_completed".equals(d.eventType())));
            assertTrue(deltas.stream().anyMatch(d -> "goal_evaluated".equals(d.eventType())
                    && Boolean.TRUE.equals(d.eventData().get("skipped"))
                    && "terminal_write_failed".equals(d.eventData().get("reason"))), "The retry consumer receives a failed completion event");
        }
        finally { ChatOriginHolder.clear(); }
        verify(evaluator, atLeastOnce()).evaluate(any(), anyList(), anyString());
        assertEquals(GoalStatus.ACTIVE, goals.getById(fixture.goal().getId()).getStatus());
        assertTrue(goals.getById(fixture.goal().getId()).isJsonAcceptanceRequired());
        assertEquals("NO_ARTIFACT", bindings.state(fixture.goal().getId(), fixture.username()).getFirst().status());
        assertTrue(goals.listEvents(fixture.goal().getId(), 30).stream().noneMatch(e -> "completed".equals(e.getEventType())));
        if (scheduled) {
            assertTrue(coordinator.settle(fixture.run(), new SegmentOutcome.Retry("evaluation", "evaluation_unavailable"), java.time.LocalDateTime.now()));
            assertEquals("retry", continuations.get(fixture.goal().getId()).state());
        }
        assertTrue(calls.get() < 10, "Bounded offline rejection flow: " + calls.get());
    }

    private List<vip.mate.agent.AgentService.StreamDelta> structured(vip.mate.agent.BaseAgent agent, String prompt, String conversation) {
        var stream = agent instanceof StateGraphReActAgent react
                ? react.chatStructuredStream(prompt, conversation)
                : ((vip.mate.agent.graph.plan.StateGraphPlanExecuteAgent) agent).chatStructuredStream(prompt, conversation);
        var deltas = stream.collectList().block(java.time.Duration.ofSeconds(30));
        assertNotNull(deltas);
        return deltas;
    }

    private record Fixture(String username, String conversation, GoalEntity goal,
                           GoalRunCoordinator.ClaimedRun run, ChatOrigin origin) { }

    private Fixture configuredGoal(boolean scheduled) {
        String username = "graph-" + UUID.randomUUID();
        long userId = IdWorker.getId();
        String conversation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", userId, username, "unused");
        jdbc.update("INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,create_time,update_time,deleted) VALUES (?,?,?,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId(), conversation, username);
        var request = new GoalCreateRequest(); request.setConversationId(conversation); request.setAgentId(1L); request.setWorkspaceId(1L);
        request.setTitle("Managed graph fixture"); request.setDescription("Produce JSON"); request.setPersistentExecution(scheduled); request.setAutoFollowupEnabled(false);
        GoalEntity goal = goals.create(request, username);
        goals.appendCriterion(goal.getId(), "Produce the report", username);
        goals.recordEvaluation(goal.getId(), new GoalEvaluationResult(1, "offline semantic fixture", "completed", true,
                "fixture", 1, 0, List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "fixture only")), null), 1, 1);
        requirements.configure(goal.getId(), "r", new GoalJsonAcceptanceService.ConfigureRequest(0L, "report", List.of("summary")), username);
        GoalRunCoordinator.ClaimedRun run = null;
        ChatOrigin origin = ChatOrigin.web(conversation, username, 1L, null, null, userId).withAgent(1L);
        if (scheduled) {
            jdbc.update("UPDATE mate_agent_goal SET auto_followup_enabled=TRUE WHERE id=?", goal.getId());
            var now = java.time.LocalDateTime.now(); continuations.discover(now);
            run = coordinator.claim(continuations.get(goal.getId()), goals.getById(goal.getId()), now);
            assertNotNull(run); assertTrue(coordinator.markRunning(run, now));
            origin = ChatOrigin.web(conversation, username, 1L, null).withAgent(1L)
                    .withExecutionAttribution(new vip.mate.agent.context.ExecutionAttribution(goal.getId(), run.attempt().id(), null, null, run.attempt().leaseToken()));
        }
        return new Fixture(username, conversation, goal, run, origin);
    }

    private vip.mate.agent.BaseAgent graphAgent(boolean plan, vip.mate.agent.AgentToolSet toolSet, ChatModel model) {
        CompiledGraph graph = ReflectionTestUtils.invokeMethod(builder, plan ? "buildPlanExecuteGraph" : "buildReActGraph", toolSet, model, 12, null);
        assertNotNull(graph);
        vip.mate.agent.BaseAgent agent = plan
                ? new vip.mate.agent.graph.plan.StateGraphPlanExecuteAgent(mock(ChatClient.class), conversations, graph, planning, model, window, toolSet)
                : new StateGraphReActAgent(mock(ChatClient.class), conversations, graph, model, window, toolSet);
        ReflectionTestUtils.setField(agent, "agentId", "1");
        ReflectionTestUtils.setField(agent, "agentName", "JSON graph fixture");
        ReflectionTestUtils.setField(agent, "systemPrompt", "Follow the user's managed JSON requirements.");
        ReflectionTestUtils.setField(agent, "goalService", goals);
        return agent;
    }

}
