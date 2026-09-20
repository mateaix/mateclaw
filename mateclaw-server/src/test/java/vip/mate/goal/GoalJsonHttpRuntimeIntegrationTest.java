package vip.mate.goal;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import vip.mate.MateClawApplication;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.goal.model.*;
import vip.mate.goal.service.*;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP authentication, AgentService and public graph builder; model responses are offline fixtures. */
@SpringBootTest(classes = MateClawApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:json_http_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.dashscope.api-key=offline-fixture-no-provider",
    "mateclaw.goal.enabled=true", "mateclaw.goal.supervisor-poll-ms=3600000", "mateclaw.plugin.enabled=false", "mateclaw.skill.workspace.auto-init=false",
    "mateclaw.skill.workspace.root=${java.io.tmpdir}/mateclaw-json-http-skills-${random.uuid}"
})
class GoalJsonHttpRuntimeIntegrationTest {
    @MockBean private MemoryManager memory;
    @MockBean private GoalEvaluationService evaluator;
    @Autowired private GoalContinuationSupervisor supervisor;
    @MockBean private ProviderChatModelFactory modelFactory;
    @org.springframework.boot.test.mock.mockito.SpyBean private vip.mate.workspace.conversation.ConversationService conversationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private vip.mate.config.LoginRateLimitFilter loginLimiter;
    @Autowired private vip.mate.llm.failover.AvailableProviderPool providerPool;
    @Autowired private ObjectMapper json;
    @Autowired private GoalService goals;
    @Autowired private GoalJsonBindingService bindings;
    @Autowired private ManagedGoalJsonService artifacts;
    @Autowired private GoalContinuationStore continuations;
    @Autowired private GoalRunCoordinator coordinator;
    @Autowired private GoalRecoveryService recovery;
    @Autowired private GoalSegmentRunner runner;
    @Autowired private GoalAttemptStore attempts;
    @Autowired private GoalApprovalRunService approvalRuns;
    @Autowired private vip.mate.approval.ApprovalWorkflowService approvals;
    @Autowired private vip.mate.tool.guard.repository.ToolGuardRuleMapper guardRules;
    @Autowired private vip.mate.tool.guard.engine.ToolGuardRuleRegistry guardRegistry;
    @Autowired private vip.mate.tool.guard.service.ToolGuardConfigService guardConfig;
    @LocalServerPort private int port;

    @org.junit.jupiter.api.BeforeEach
    void isolateLoginRateLimitBetweenIndependentFixtures() {
        // Each parameter is an independent account journey on the same loopback IP.
        var attempts = (com.github.benmanes.caffeine.cache.Cache<?, ?>)
            org.springframework.test.util.ReflectionTestUtils.getField(loginLimiter, "attempts");
        assertNotNull(attempts);
        attempts.invalidateAll();
        // Independent journeys share a context; old retryable fixtures must not be redispatched.
        jdbc.update("UPDATE mate_agent_goal SET auto_followup_enabled=FALSE");
        var backoff = (java.util.concurrent.atomic.AtomicReference<?>)
                org.springframework.test.util.ReflectionTestUtils.getField(supervisor, "providerBackoffUntil");
        assertNotNull(backoff); backoff.set(null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,sync,true", "true,sync,true", "false,stream,true", "true,stream,true",
        "false,scheduled,true", "true,scheduled,true", "false,scheduled-queued,true", "true,scheduled-queued,true",
        "false,scheduled-queued-foreign,true", "true,scheduled-queued-foreign,true",
        "false,scheduled-queued-legacy,true", "true,scheduled-queued-legacy,true",
        "false,scheduled-queued-legacy-new-goal,true", "true,scheduled-queued-legacy-new-goal,true",
        "false,scheduled-queued-unselected,true", "true,scheduled-queued-unselected,true",
        "false,scheduled-queued-terminal-unselected,true", "true,scheduled-queued-terminal-unselected,true",
        "false,scheduled-queued-paused,true", "true,scheduled-queued-paused,true", "false,recovered,true", "true,recovered,true",
        "false,scheduled,false", "true,scheduled,false", "false,recovered,false", "true,recovered,false",
        "false,queued,true", "false,queued-unselected-then-goal,true", "true,queued-unselected-then-goal,true",
        "false,reuse,true", "true,reuse,true", "false,recheck,true", "true,recheck,true",
        "false,supervised,true", "true,supervised,true", "false,supervised-recovered,true", "true,supervised-recovered,true",
        "false,supervised,false", "true,supervised,false", "false,supervised-recovered,false", "true,supervised-recovered,false",
        "false,approval,true", "true,approval,true", "false,scheduled-approval,true", "true,scheduled-approval,true",
        "false,scheduled-double-approval,true", "true,scheduled-double-approval,true",
        "false,detached-approval,true", "true,detached-approval,true",
        "false,scheduled-detached-approval,true", "true,scheduled-detached-approval,true",
        "false,foreign-approval,true", "true,foreign-approval,true",
        "false,scheduled-foreign-approval,true", "true,scheduled-foreign-approval,true",
        "false,scheduled-reassigned-approval,true", "true,scheduled-reassigned-approval,true",
        "false,reassigned-approval,true", "true,reassigned-approval,true",
        "false,terminal-approval,true", "true,terminal-approval,true",
        "false,legacy-terminal-approval,true", "true,legacy-terminal-approval,true",
        "false,originless-terminal-approval,true", "true,originless-terminal-approval,true",
        "false,late-terminal-approval,true", "true,late-terminal-approval,true",
        "false,queued-terminal-approval,true", "true,queued-terminal-approval,true",
        "false,queued-revoked-approval,true", "true,queued-revoked-approval,true"})
    void authenticatedGoalCompletesThroughHttpOrScheduledProductionRuntime(boolean plan, String entry, boolean accepted) throws Exception {
        boolean approval = entry.endsWith("approval");
        boolean doubleApproval = entry.equals("scheduled-double-approval");
        boolean reassigned = entry.contains("reassigned");
        boolean terminal = entry.contains("terminal-");
        boolean lateTerminal = entry.startsWith("late-");
        boolean queuedTerminal = entry.startsWith("queued-terminal-");
        boolean queuedRevoked = entry.startsWith("queued-revoked-");
        boolean queuedPreflightRejected = queuedTerminal || queuedRevoked;
        boolean detached = entry.contains("detached");
        boolean foreign = entry.contains("foreign");
        boolean supervised = entry.startsWith("supervised");
        boolean scheduled = entry.startsWith("scheduled") || entry.equals("recovered") || supervised;
        boolean reuse = entry.equals("reuse");
        boolean recheck = entry.equals("recheck");
        boolean queuedReplacement = entry.equals("queued-unselected-then-goal");
        boolean queued = entry.equals("queued") || queuedReplacement || queuedPreflightRejected;
        boolean recovered = entry.equals("recovered") || entry.equals("supervised-recovered");
        String username = "http-json-" + UUID.randomUUID();
        String conversation = UUID.randomUUID().toString();
        long userId = IdWorker.getId(), agentId = IdWorker.getId();
        providerPool.add("dashscope");
        String password = "OfflineFixtureOnly-20260914";
        jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", userId, username,
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password));
        jdbc.update("INSERT INTO mate_workspace_member(id,workspace_id,user_id,role,create_time,update_time,deleted) VALUES (?,1,?,'member',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId(), userId);
        jdbc.update("UPDATE mate_model_provider SET api_key='offline-fixture', enabled=TRUE WHERE provider_id='dashscope'");
        jdbc.update("INSERT INTO mate_model_config(id,name,provider,model_name,enabled,is_default,max_input_tokens,create_time,update_time,deleted) VALUES (?,'Offline HTTP fixture','dashscope','json-http-fixture',TRUE,FALSE,32000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId());
        jdbc.update("INSERT INTO mate_agent(id,name,agent_type,workspace_id,model_name,max_iterations,enabled,create_time,update_time,deleted) VALUES (?,?,?,1,'json-http-fixture',12,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", agentId, "HTTP JSON fixture " + agentId, plan ? "plan_execute" : "react");
        jdbc.update("INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,model_provider,model_name,create_time,update_time,deleted) VALUES (?,?,?,1,?,'dashscope','json-http-fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId(), conversation, username, agentId);
        var create = new GoalCreateRequest(); create.setConversationId(conversation); create.setAgentId(agentId); create.setWorkspaceId(1L);
        create.setTitle("HTTP managed JSON fixture"); create.setDescription("Produce JSON"); create.setPersistentExecution(scheduled); create.setAutoFollowupEnabled(false);
        GoalEntity goal = goals.create(create, username);
        if (scheduled) {
            goals.appendCriterion(goal.getId(), "Produce the report", username);
            goals.recordEvaluation(goal.getId(), new GoalEvaluationResult(1, "offline semantic fixture", "completed", true,
                "fixture", 1, 0, List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "fixture only")), null), 1, 1);
        }
        when(evaluator.evaluate(any(), anyList(), anyString())).thenReturn(accepted
            ? GoalEvaluationResult.fallback("offline_http_fixture")
            : new GoalEvaluationResult(1, "offline semantic PASS without a managed binding", "completed", true,
                "fixture", 1, 0, List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "fixture only")), null));
        JsonNode login = request("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", password));
        String token = login.path("data").path("token").asText();
        assertFalse(token.isBlank(), login.toString());
        JsonNode configured = request("PUT", "/api/v1/goals/" + goal.getId() + "/json-acceptance/requirements/r", token,
            Map.of("expectedRevision", "0", "artifactSlot", "report", "requiredFields", List.of("summary")));
        assertEquals(200, configured.path("code").asInt(), configured.toString());
        if (!plan && entry.equals("sync") && accepted) {
            var explicitlyUnselected = ChatOrigin.web(conversation, username, 1L, null, null, userId)
                    .withAgent(agentId).withSelectedGoalId(0L);
            assertEquals(goal.getId(), approvalRuns.captureSelectedGoal(explicitlyUnselected).selectedGoalId(),
                    "An ordinary in-flight request may tighten an explicit zero before approval persistence");
            assertFalse(approvalRuns.queuedSelectionStillCurrent(explicitlyUnselected),
                    "An unselected queue snapshot must become stale when a managed Goal appears");
        }
        if (reuse) {
            for (long generation = 0; generation < 32; generation++) {
                artifacts.publish(goal.getId(), "report", new ManagedGoalJsonService.PublishRequest(generation, "{\"summary\":false}"), username);
            }
        }
        GoalRunCoordinator.ClaimedRun run = null;
        if (scheduled) {
            jdbc.update("UPDATE mate_agent_goal SET auto_followup_enabled=TRUE WHERE id=?", goal.getId());
            if (!supervised || recovered) {
                continuations.discover(java.time.LocalDateTime.now());
                run = claim(goal);
            }
            if (recovered) {
                var old = run;
                var staleOrigin = attemptOrigin(goal, old);
                var previous = artifacts.publishForRuntime(staleOrigin, "report",
                    new ManagedGoalJsonService.PublishRequest(0L, "{\"summary\":\"before recovery\"}"));
                assertTrue(coordinator.checkpoint(old, "resolved", "tool_completed", null, java.time.LocalDateTime.now()));
                long expired = java.time.Instant.now().minusSeconds(1).getEpochSecond();
                jdbc.update("UPDATE mate_goal_attempt SET lease_until_epoch_second=? WHERE attempt_id=?", expired, old.attempt().id());
                jdbc.update("UPDATE mate_goal_continuation SET lease_until_epoch_second=? WHERE goal_id=?", expired, goal.getId());
                if (!supervised) {
                    assertEquals(1, recovery.recoverExpired(java.time.Instant.now()));
                    assertEquals("retry", continuations.get(goal.getId()).state());
                    run = claim(goal);
                    assertEquals(old.attempt().id(), run.attempt().parentAttemptId());
                    assertNotEquals(old.attempt().leaseToken(), run.attempt().leaseToken());
                }
                assertFalse(coordinator.renew(old, java.time.LocalDateTime.now()));
                assertThrows(vip.mate.exception.MateClawException.class, () -> artifacts.publishForRuntime(staleOrigin, "report",
                    new ManagedGoalJsonService.PublishRequest(1L, "{\"summary\":\"stale writer\"}")));
                assertTrue(assertThrows(vip.mate.exception.MateClawException.class,
                    () -> goals.markRuntimeCompleted(goal.getId(), null, staleOrigin)).getMessage().contains("owner"));
                assertEquals("{\"summary\":\"before recovery\"}", artifacts.read(goal.getId(), previous.artifactId(), username).jsonContent());
            }
        }
        ChatModel model = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> revision = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> originalCheck = new java.util.concurrent.atomic.AtomicReference<>();
        var planApprovalReplay = new java.util.concurrent.atomic.AtomicBoolean();
        var approvedToolName = new java.util.concurrent.atomic.AtomicReference<>("getManagedGoalJsonSlots");
        org.mockito.stubbing.Answer<ChatResponse> script = invocation -> {
            if (approval && plan && planApprovalReplay.compareAndSet(true, false)) {
                // Plan replay asks again for the persisted approved call; ReAct forces it without an LLM call.
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("approved-" + approvedToolName.get(), "function", approvedToolName.get(), "{}"))).build())));
            }
            Prompt prompt = invocation.getArgument(0);
            int step = calls.getAndIncrement();
            if (recovered && step == (plan ? 1 : 0)) {
                assertTrue(prompt.getInstructions().stream().anyMatch(message -> message.getText()!=null
                    && message.getText().contains("Do not replay side effects whose outcome is unknown")),
                    "Recovered execution must receive the existing-evidence guidance");
            }
            if (plan && step == 0) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"needs_planning\":true,\"steps\":[\"Produce, publish, check and complete the managed JSON report\"]}"))));
            }
            if (plan) step--;
            if (detached && step == 1) {
                // The graph already captured its origin. A provider/thread boundary must not
                // require that the original request ThreadLocal still be present at guard time.
                vip.mate.agent.context.ChatOriginHolder.clear();
            }
            if (foreign && step == 1) {
                vip.mate.agent.context.ChatOriginHolder.set(
                        vip.mate.agent.context.ChatOrigin.web("foreign-conversation", "foreign-requester", 999L, null, null, -1L)
                                .withAgent(999L).withExecutionAttribution(new vip.mate.agent.context.ExecutionAttribution(
                                        999L, "foreign-attempt", null, null, "foreign-fence")));
            }
            if (!accepted) {
                if (step == 0) return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("read-unbound", "function", "getManagedGoalJsonSlots", "{}"))).build())));
                return new ChatResponse(List.of(new Generation(new AssistantMessage("PASS from offline fixture."))));
            }
            List<ToolResponseMessage.ToolResponse> responses = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(m -> m.getResponses().stream()).toList();
            JsonNode last = responses.isEmpty() ? null : json.readTree(responses.getLast().responseData());
            String name; String arguments = "{}";
            if (recheck && step >= 5 && step <= 7) {
                if (step == 5) {
                    assertTrue(last.path("error").asBoolean(), String.valueOf(last));
                    name = "getManagedGoalJsonSlots";
                } else if (step == 6) {
                    assertEquals("GOAL_CHANGED", last.path("checks").get(0).path("status").asText(), String.valueOf(last));
                    assertEquals(1, last.path("versionCount").asInt());
                    name = "checkManagedGoalJson";
                    arguments = originalCheck.get();
                    assertNotNull(arguments);
                } else {
                    assertTrue(last.path("acceptanceEligible").asBoolean(), String.valueOf(last));
                    name = "completeGoal";
                }
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("recheck-" + step, "function", name, arguments))).build())));
            }
            switch (step) {
                case 0 -> name = "completeGoal";
                case 1 -> { assertTrue(last.path("error").asBoolean(), String.valueOf(last)); name = "getManagedGoalJsonSlots"; }
                case 2 -> {
                    assertTrue(last.path("required").asBoolean(), String.valueOf(last));
                    revision.set(last.path("requirements").get(0).path("revision").asText());
                    if (reuse) {
                        assertEquals(32, last.path("versionCount").asInt());
                        JsonNode current = last.path("slots").get(0).path("current");
                        name = "checkManagedGoalJson";
                        arguments = json.writeValueAsString(Map.of("criterionKey", "r", "expectedRequirementRevision", revision.get(),
                            "artifactId", current.path("artifactId").asText(), "expectedGeneration", current.path("generation").asText()));
                    } else {
                        name = "publishManagedGoalJson";
                        arguments = json.writeValueAsString(Map.of("artifactSlot", "report", "expectedGeneration", recovered ? "1" : "0", "jsonContent", "{\"summary\":false}"));
                    }
                }
                case 3 -> {
                    if (reuse) {
                        assertTrue(last.path("acceptanceEligible").asBoolean(), String.valueOf(last));
                        name = "getManagedGoalJsonSlots";
                    } else {
                        assertEquals(scheduled ? "goal-attempt" : "account-runtime", last.path("producerKind").asText(), String.valueOf(last));
                        name = "checkManagedGoalJson";
                        arguments = json.writeValueAsString(Map.of("criterionKey", "r", "expectedRequirementRevision", revision.get(),
                                "artifactId", last.path("artifactId").asText(), "expectedGeneration", last.path("generation").asText()));
                        originalCheck.set(arguments);
                    }
                }
                case 4 -> {
                    assertTrue((reuse ? last.path("checks").get(0) : last).path("acceptanceEligible").asBoolean(), String.valueOf(last));
                    if (recheck) {
                        // Simulate a user definition edit between the first check and completion.
                        var edit = new GoalUpdateRequest(); edit.setDescription("Revised report context");
                        goals.update(goal.getId(), edit, username);
                    }
                    name = "completeGoal";
                }
                default -> {
                    if (last != null) assertEquals("completed", last.path("status").asText(), String.valueOf(last));
                    assertEquals(GoalStatus.COMPLETED, goals.getById(goal.getId()).getStatus());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Managed JSON fixture completed."))));
                }
            }
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("json-" + step, "function", name, arguments))).build())));
        };
        when(model.call(any(Prompt.class))).thenAnswer(script);
        var firstSubscribed = new java.util.concurrent.CountDownLatch(1);
        var initialResponse = reactor.core.publisher.Sinks.<ChatResponse>one();
        var firstStream = new java.util.concurrent.atomic.AtomicBoolean(true);
        var firstInvocation = new java.util.concurrent.atomic.AtomicReference<org.mockito.invocation.InvocationOnMock>();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            if ((queued || lateTerminal) && firstStream.compareAndSet(true, false)) {
                firstInvocation.set(invocation);
                return initialResponse.asMono().flux().doOnSubscribe(subscription -> firstSubscribed.countDown());
            }
            return Flux.just(script.answer(invocation));
        });

        when(model.getDefaultOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().model("json-http-fixture").build());
        when(modelFactory.buildFor(any(), any())).thenReturn(model);
        String message = "Produce, publish, check and complete the managed JSON report.";
        if (approval) {
            var rule = new vip.mate.tool.guard.model.ToolGuardRuleEntity();
            rule.setId(IdWorker.getId()); rule.setRuleId("json-http-approval-" + goal.getId());
            rule.setName("Offline managed JSON approval fixture"); rule.setDescription("Exercise the real approval replay path");
            rule.setToolName("getManagedGoalJsonSlots"); rule.setParamName("args");
            rule.setCategory("RESOURCE_ABUSE"); rule.setSeverity("MEDIUM"); rule.setDecision("NEEDS_APPROVAL");
            rule.setPattern("getManagedGoalJsonSlots"); rule.setBuiltin(false); rule.setEnabled(true); rule.setPriority(1000); rule.setDeleted(0);
            guardRules.insert(rule);
            vip.mate.tool.guard.model.ToolGuardRuleEntity publishRule = null;
            if (doubleApproval) {
                publishRule = new vip.mate.tool.guard.model.ToolGuardRuleEntity();
                org.springframework.beans.BeanUtils.copyProperties(rule, publishRule);
                publishRule.setId(IdWorker.getId()); publishRule.setRuleId(rule.getRuleId() + "-publish");
                publishRule.setToolName("publishManagedGoalJson"); publishRule.setPattern("publishManagedGoalJson");
                guardRules.insert(publishRule);
            }
            guardRegistry.reload();
            var guard = guardConfig.getConfig(); guard.setEnabled(true); guardConfig.updateConfig(guard);
            try {
                String waiting;
                if (scheduled) {
                    SegmentOutcome outcome = runner.run(run, message, false);
                    assertInstanceOf(SegmentOutcome.AwaitApproval.class, outcome);
                    assertTrue(coordinator.settle(run, outcome, java.time.LocalDateTime.now()));
                    assertEquals("waiting_approval", continuations.get(goal.getId()).state());
                    waiting = outcome.toString();
                } else if (queuedPreflightRejected) {
                    String queuedToken = token;
                    var initialTurn = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return requestBody("POST", "/api/v1/chat/stream", queuedToken,
                                    Map.of("agentId", String.valueOf(agentId), "conversationId", conversation,
                                            "message", "Wait for a queued approval fixture."));
                        } catch (Exception failure) {
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                    });
                    try {
                        assertTrue(firstSubscribed.await(10, java.util.concurrent.TimeUnit.SECONDS));
                        JsonNode enqueue = request("POST", "/api/v1/chat/" + conversation + "/interrupt", token,
                                Map.of("agentId", String.valueOf(agentId), "message", message));
                        assertTrue(enqueue.path("data").path("queued").asBoolean(), enqueue.toString());
                        long queueId = Long.parseLong(enqueue.path("data").path("queueItemId").asText());
                        assertEquals(userId, jdbc.queryForObject("SELECT requester_user_id FROM mate_conversation_input_queue WHERE id=?", Long.class, queueId));
                        assertEquals(goal.getId(), jdbc.queryForObject(
                                "SELECT selected_goal_id FROM mate_conversation_input_queue WHERE id=?", Long.class, queueId));
                        if (queuedTerminal) {
                            goals.abandon(goal.getId(), username);
                            assertEquals(GoalStatus.ABANDONED, goals.getById(goal.getId()).getStatus());
                        } else {
                            jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE id=?", userId);
                        }
                        String initialAnswer = plan
                                ? "{\"needs_planning\":false,\"direct_answer\":\"Initial fixture turn finished.\"}"
                                : "Initial fixture turn finished.";
                        assertEquals(reactor.core.publisher.Sinks.EmitResult.OK, initialResponse.tryEmitValue(
                                new ChatResponse(List.of(new Generation(new AssistantMessage(initialAnswer))))));
                        waiting = initialTurn.get(45, java.util.concurrent.TimeUnit.SECONDS);
                        assertEquals("consumed", jdbc.queryForObject("SELECT state FROM mate_conversation_input_queue WHERE id=?", String.class, queueId));
                    } finally {
                        initialResponse.tryEmitEmpty();
                        initialTurn.cancel(true);
                    }
                } else if (lateTerminal) {
                    String inFlightToken = token;
                    var inFlight = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return requestBody("POST", "/api/v1/chat/stream", inFlightToken,
                                    Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", message));
                        } catch (Exception failure) {
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                    });
                    assertTrue(firstSubscribed.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    goals.abandon(goal.getId(), username);
                    assertEquals(GoalStatus.ABANDONED, goals.getById(goal.getId()).getStatus());
                    assertEquals(reactor.core.publisher.Sinks.EmitResult.OK,
                            initialResponse.tryEmitValue(model.call((Prompt) firstInvocation.get().getArgument(0))));
                    waiting = inFlight.get(45, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    waiting = requestBody("POST", "/api/v1/chat/stream", token,
                            Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", message));
                }
                if (queuedPreflightRejected) {
                    assertTrue(waiting.contains("queued_input_skipped"), waiting);
                    assertEquals(0, calls.get(), "A stale queued Goal must not invoke the model");
                    assertEquals(queuedTerminal ? GoalStatus.ABANDONED : GoalStatus.ACTIVE,
                            goals.getById(goal.getId()).getStatus());
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_tool_approval WHERE conversation_id=?", Integer.class, conversation));
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
                    return;
                }
                JsonNode pending = request("GET", "/api/v1/chat/" + conversation + "/pending-approvals", token, null).path("data");
                assertEquals(1, pending.size(), waiting);
                String pendingId = pending.get(0).path("pendingId").asText();
                assertEquals("getManagedGoalJsonSlots", pending.get(0).path("toolName").asText());
                assertEquals(lateTerminal || queuedTerminal ? GoalStatus.ABANDONED : GoalStatus.ACTIVE,
                        goals.getById(goal.getId()).getStatus());
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
                String persistedOrigin = jdbc.queryForObject("SELECT chat_origin FROM mate_tool_approval WHERE pending_id=?", String.class, pendingId);
                assertEquals(conversation, approvals.restoreChatOrigin(persistedOrigin).conversationId());
                assertEquals(agentId, approvals.restoreChatOrigin(persistedOrigin).agentId());
                assertEquals(1L, approvals.restoreChatOrigin(persistedOrigin).workspaceId());
                if (scheduled) {
                    assertNotNull(approvals.restoreChatOrigin(persistedOrigin).executionAttribution(), persistedOrigin);
                    assertEquals(run.attempt().id(), approvals.restoreChatOrigin(persistedOrigin).executionAttribution().goalAttemptId());
                }
                else {
                    assertEquals(userId, approvals.restoreChatOrigin(persistedOrigin).requesterUserId());
                    assertEquals(goal.getId(), approvals.restoreChatOrigin(persistedOrigin).selectedGoalId());
                    if (!lateTerminal && !queuedTerminal) {
                        var approvalReplayOrigin = approvals.restoreChatOrigin(persistedOrigin)
                                .withSelectedGoalId(null).withApprovalId(pendingId);
                        assertEquals(goal.getId(), approvalRuns.captureSelectedGoal(approvalReplayOrigin).selectedGoalId(),
                                "A replayed interactive approval can create another selected approval");
                    }
                }
                Long approvedPlan = plan ? jdbc.queryForObject("SELECT id FROM mate_plan WHERE conversation_id=?", Long.class, conversation) : null;
                if (terminal) {
                    if (entry.startsWith("legacy-")) {
                        var oldOrigin = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(persistedOrigin);
                        oldOrigin.remove("selectedGoalId");
                        String oldSnapshot = json.writeValueAsString(oldOrigin);
                        approvals.getPending(pendingId).orElseThrow().setChatOrigin(oldSnapshot);
                        jdbc.update("UPDATE mate_tool_approval SET chat_origin=? WHERE pending_id=?", oldSnapshot, pendingId);
                    } else if (entry.startsWith("originless-")) {
                        approvals.getPending(pendingId).orElseThrow().setChatOrigin(null);
                        jdbc.update("UPDATE mate_tool_approval SET chat_origin=NULL WHERE pending_id=?", pendingId);
                    }
                    if (!lateTerminal && !queuedTerminal) goals.abandon(goal.getId(), username);
                    assertEquals(GoalStatus.ABANDONED, goals.getById(goal.getId()).getStatus());
                    if (entry.startsWith("legacy-")) {
                        var oldApprovalOrigin = approvals.restoreChatOrigin(
                                approvals.getPending(pendingId).orElseThrow().getChatOrigin()).withApprovalId(pendingId);
                        assertNull(oldApprovalOrigin.selectedGoalId());
                        assertTrue(approvalRuns.requiresCurrentApprover(oldApprovalOrigin),
                                "An old approval pending before Goal termination must remain managed");
                    }
                    var laterUnselected = approvalRuns.captureSelectedGoal(
                            ChatOrigin.web(conversation, username, 1L, null, null, userId).withAgent(agentId));
                    assertEquals(0L, laterUnselected.selectedGoalId());
                    assertFalse(approvalRuns.requiresCurrentApprover(laterUnselected.withApprovalId(pendingId)),
                            "A newly unselected approval must retain the legacy route");
                    String rejected = requestBody("POST", "/api/v1/chat/stream", token,
                            Map.of("agentId", String.valueOf(agentId), "conversationId", conversation,
                                    "message", "/approve", "pendingApprovalId", pendingId));
                    assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM mate_tool_approval WHERE pending_id=?", String.class, pendingId), rejected);
                    assertEquals(GoalStatus.ABANDONED, goals.getById(goal.getId()).getStatus());
                    requestBody("POST", "/api/v1/chat/stream", token,
                            Map.of("agentId", String.valueOf(agentId), "conversationId", conversation,
                                    "message", "/deny", "pendingApprovalId", pendingId));
                    assertEquals("DENIED", jdbc.queryForObject("SELECT status FROM mate_tool_approval WHERE pending_id=?", String.class, pendingId));
                    return;
                }
                if (reassigned) {
                    var replaceOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
                    doAnswer(invocation -> {
                        if (replaceOnce.compareAndSet(true, false)) {
                            long replacementId = IdWorker.getId();
                            jdbc.update("UPDATE mate_user SET username=?,deleted=1,enabled=FALSE WHERE id=?", "retired-" + userId, userId);
                            jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", replacementId, username,
                                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password));
                            jdbc.update("INSERT INTO mate_workspace_member(id,workspace_id,user_id,role,create_time,update_time,deleted) VALUES (?,1,?,'member',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId(), replacementId);
                        }
                        return invocation.callRealMethod();
                    }).when(conversationService).isConversationOwner(conversation, username);
                    planApprovalReplay.set(plan);
                    var oldRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/chat/stream"))
                            .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json")
                            .header("X-Workspace-Id", "1").header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("agentId", String.valueOf(agentId),
                                    "conversationId", conversation, "message", "/approve", "pendingApprovalId", pendingId)))).build();
                    var rejected = HttpClient.newHttpClient().send(oldRequest, HttpResponse.BodyHandlers.ofString());
                    assertFalse(replaceOnce.get(), "Replacement must happen after JWT authentication");
                    assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus(), rejected.body());
                    assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM mate_tool_approval WHERE pending_id=?", String.class, pendingId));
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
                    token = request("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", password)).path("data").path("token").asText();
                    assertFalse(token.isBlank());
                    if (!scheduled) {
                        var newAccountReplay = requestBody("POST", "/api/v1/chat/stream", token,
                                Map.of("agentId", String.valueOf(agentId), "conversationId", conversation,
                                        "message", "/approve", "pendingApprovalId", pendingId));
                        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM mate_tool_approval WHERE pending_id=?", String.class, pendingId), newAccountReplay);
                        assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
                        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
                        return;
                    }
                }
                planApprovalReplay.set(plan);
                String replay = requestBody("POST", "/api/v1/chat/stream", token,
                        Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", "/approve", "pendingApprovalId", pendingId));
                String expectedParent = scheduled ? run.attempt().id() : null;
                if (doubleApproval) {
                    String firstReplayAttempt = jdbc.queryForObject("SELECT attempt_id FROM mate_goal_attempt WHERE approval_pending_id=?", String.class, pendingId);
                    assertEquals(expectedParent, attempts.get(firstReplayAttempt).parentAttemptId());
                    assertEquals("succeeded", attempts.get(firstReplayAttempt).state());
                    assertEquals("waiting_approval", continuations.get(goal.getId()).state(), replay);
                    assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
                    JsonNode next = request("GET", "/api/v1/chat/" + conversation + "/pending-approvals", token, null).path("data");
                    assertEquals(1, next.size(), replay);
                    assertEquals("publishManagedGoalJson", next.get(0).path("toolName").asText());
                    pendingId = next.get(0).path("pendingId").asText();
                    approvedToolName.set("publishManagedGoalJson"); planApprovalReplay.set(plan);
                    expectedParent = firstReplayAttempt;
                    replay = requestBody("POST", "/api/v1/chat/stream", token,
                            Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", "/approve", "pendingApprovalId", pendingId));
                }
                assertTrue(replay.contains("Managed JSON fixture completed."), replay);
                assertEquals("CONSUMED", jdbc.queryForObject("SELECT status FROM mate_tool_approval WHERE pending_id=?", String.class, pendingId));
                if (scheduled) {
                    String freshAttempt = jdbc.queryForObject("SELECT attempt_id FROM mate_goal_attempt WHERE approval_pending_id=?", String.class, pendingId);
                    var fresh = attempts.get(freshAttempt);
                    assertEquals(expectedParent, fresh.parentAttemptId());
                    assertNotEquals(run.attempt().leaseToken(), fresh.leaseToken());
                    assertEquals("succeeded", fresh.state());
                    assertEquals("completed", continuations.get(goal.getId()).state());
                    assertFalse(coordinator.renew(run, java.time.LocalDateTime.now()));
                    assertEquals(freshAttempt, jdbc.queryForObject("SELECT producer_id FROM mate_goal_json_artifact WHERE goal_id=?", String.class, goal.getId()));
                    assertEquals(doubleApproval ? 3 : 2, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_attempt WHERE goal_id=?", Integer.class, goal.getId()));
                }
                if (plan) {
                    assertEquals(approvedPlan, jdbc.queryForObject("SELECT id FROM mate_plan WHERE conversation_id=?", Long.class, conversation),
                            "Approval replay must finish the original plan without creating a replacement");
                    assertEquals("completed", jdbc.queryForObject("SELECT status FROM mate_plan WHERE id=?", String.class, approvedPlan));
                }
            } finally {
                jdbc.update("DELETE FROM mate_tool_guard_rule WHERE id=?", rule.getId());
                if (publishRule != null) jdbc.update("DELETE FROM mate_tool_guard_rule WHERE id=?", publishRule.getId());
                guardRegistry.reload();
            }
        } else if (queued) {
            String queuedToken = token;
            var response = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return requestBody("POST", "/api/v1/chat/stream", queuedToken,
                        Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", "Wait for a follow-up fixture."));
                } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            });
            try {
                assertTrue(firstSubscribed.await(10, java.util.concurrent.TimeUnit.SECONDS), "Initial HTTP turn must reach the actual model boundary");
                if (queuedReplacement) goals.abandon(goal.getId(), username);
                JsonNode enqueue = request("POST", "/api/v1/chat/" + conversation + "/interrupt", token,
                    Map.of("agentId", String.valueOf(agentId), "message", message));
                assertTrue(enqueue.path("data").path("queued").asBoolean(), enqueue.toString());
                long queueId = Long.parseLong(enqueue.path("data").path("queueItemId").asText());
                assertEquals(userId, jdbc.queryForObject("SELECT requester_user_id FROM mate_conversation_input_queue WHERE id=?", Long.class, queueId));
                GoalEntity replacement = null;
                if (queuedReplacement) {
                    assertEquals(0L, jdbc.queryForObject(
                            "SELECT selected_goal_id FROM mate_conversation_input_queue WHERE id=?", Long.class, queueId));
                    var replacementCreate = new GoalCreateRequest(); replacementCreate.setConversationId(conversation);
                    replacementCreate.setAgentId(agentId); replacementCreate.setWorkspaceId(1L);
                    replacementCreate.setTitle("Managed Goal created while input waits");
                    replacementCreate.setDescription("Do not attach the explicit zero queue snapshot");
                    replacement = goals.create(replacementCreate, username);
                    JsonNode replacementConfigured = request("PUT", "/api/v1/goals/" + replacement.getId()
                                    + "/json-acceptance/requirements/r", token,
                            Map.of("expectedRevision", "0", "artifactSlot", "report", "requiredFields", List.of("summary")));
                    assertEquals(200, replacementConfigured.path("code").asInt(), replacementConfigured.toString());
                }
                String initialAnswer = plan
                        ? "{\"needs_planning\":false,\"direct_answer\":\"Initial fixture turn finished.\"}"
                        : "Initial fixture turn finished.";
                assertEquals(reactor.core.publisher.Sinks.EmitResult.OK, initialResponse.tryEmitValue(
                    new ChatResponse(List.of(new Generation(new AssistantMessage(initialAnswer))))));
                String events = response.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("consumed", jdbc.queryForObject("SELECT state FROM mate_conversation_input_queue WHERE id=?", String.class, queueId));
                if (queuedReplacement) {
                    assertTrue(events.contains("queued_input_skipped"), events);
                    assertEquals(0, calls.get(), "The explicit zero snapshot must not start a second model turn");
                    assertEquals(GoalStatus.ACTIVE, goals.getById(replacement.getId()).getStatus());
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?",
                            Integer.class, replacement.getId()));
                    return;
                } else {
                    assertTrue(events.contains("Managed JSON fixture completed."), events);
                }
            } finally {
                initialResponse.tryEmitEmpty();
                response.cancel(true);
            }
        } else if (supervised) {
            GoalAttempt finished = null;
            var active = (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(supervisor, "active");
            assertNotNull(active);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            try {
                while (System.nanoTime() < deadline) {
                    supervisor.tick();
                    finished = attempts.listRecent(goal.getId(), 2).stream()
                            .filter(attempt -> attempt.assistantMessageId() != null
                                    && (accepted ? "succeeded" : "retryable").equals(attempt.state()))
                            .findFirst().orElse(null);
                    var projection = continuations.get(goal.getId());
                    if (finished != null && active.isEmpty() && projection != null
                            && (accepted ? "completed" : "retry").equals(projection.state())) break;
                    Thread.sleep(25);
                }
                assertNotNull(finished, "Actual supervisor must dispatch and settle a persisted segment");
                assertTrue(active.isEmpty(), "Supervisor must release the completed worker");
                assertEquals(accepted ? "completed" : "retry", continuations.get(goal.getId()).state());
                assertEquals("message_saved", finished.checkpointType());
                assertTrue(jdbc.queryForObject("SELECT content FROM mate_message WHERE id=?", String.class,
                        finished.assistantMessageId()).contains(accepted ? "Managed JSON fixture completed." : "PASS from offline fixture."));
                if (recovered) {
                    assertEquals(run.attempt().id(), finished.parentAttemptId());
                    assertNotEquals(run.attempt().leaseToken(), finished.leaseToken());
                }
            } finally {
                jdbc.update("UPDATE mate_agent_goal SET auto_followup_enabled=FALSE WHERE id=?", goal.getId());
                runner.cancel(goal.getId());
            }
        } else if (scheduled) {
            if (entry.equals("scheduled-queued-terminal-unselected")) {
                var queuedInput = new vip.mate.channel.web.ConversationInputQueueStore(jdbc, json).enqueue(
                        conversation, agentId, username, "Unselected input after Goal ended", List.of(),
                        userId, 0L, java.time.LocalDateTime.now());
                goals.abandon(goal.getId(), username);

                SegmentOutcome outcome = runner.run(run, message, false);

                assertInstanceOf(SegmentOutcome.Continue.class, outcome);
                assertEquals(0, calls.get(), "Terminal Goal must reject queued input before model execution");
                assertEquals("consumed", jdbc.queryForObject(
                        "SELECT state FROM mate_conversation_input_queue WHERE id=?", String.class, queuedInput.id()));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_message WHERE conversation_id=? AND role='user' AND content=?",
                        Integer.class, conversation, "Unselected input after Goal ended"));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_message WHERE conversation_id=? AND role='assistant' AND content LIKE ?",
                        Integer.class, conversation, "%was not run because its selected Goal%"));
                assertTrue(coordinator.settle(run, outcome, java.time.LocalDateTime.now()));
                return;
            }
            if (entry.equals("scheduled-queued-legacy-new-goal")) {
                goals.abandon(goal.getId(), username);
                assertTrue(coordinator.settle(run, new SegmentOutcome.Cancelled("replaced"),
                        java.time.LocalDateTime.now()));
                var replacement = new GoalCreateRequest(); replacement.setConversationId(conversation);
                replacement.setAgentId(agentId); replacement.setWorkspaceId(1L);
                replacement.setTitle("Replacement unselected Goal"); replacement.setDescription("Legacy queue isolation");
                replacement.setPersistentExecution(true); replacement.setAutoFollowupEnabled(true);
                GoalEntity newGoal = goals.create(replacement, username);
                assertFalse(newGoal.isJsonAcceptanceRequired());
                var queuedInput = new vip.mate.channel.web.ConversationInputQueueStore(jdbc, json).enqueue(
                        conversation, agentId, username, "Old unknown selected input", List.of(),
                        null, null, java.time.LocalDateTime.now());
                continuations.discover(java.time.LocalDateTime.now());
                var replacementRun = claim(newGoal);

                SegmentOutcome outcome = runner.run(replacementRun, message, false);

                assertInstanceOf(SegmentOutcome.Continue.class, outcome);
                assertEquals(0, calls.get(), "Legacy unknown input must not run under the replacement Goal");
                assertEquals(GoalStatus.ACTIVE, goals.getById(newGoal.getId()).getStatus());
                assertEquals("consumed", jdbc.queryForObject(
                        "SELECT state FROM mate_conversation_input_queue WHERE id=?", String.class, queuedInput.id()));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_message WHERE conversation_id=? AND role='user' AND content=?",
                        Integer.class, conversation, "Old unknown selected input"));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_message WHERE conversation_id=? AND role='assistant' AND content LIKE ?",
                        Integer.class, conversation, "%was not run because its selected Goal%"));
                assertTrue(coordinator.settle(replacementRun, outcome, java.time.LocalDateTime.now()));
                return;
            }
            if (entry.equals("scheduled-queued-paused")) {
                var queuedInput = new vip.mate.channel.web.ConversationInputQueueStore(jdbc, json).enqueue(
                        conversation, agentId, username, message, List.of(), userId, goal.getId(),
                        java.time.LocalDateTime.now());
                goals.pause(goal.getId(), username);
                SegmentOutcome pausedOutcome = runner.run(run, message, false);
                assertInstanceOf(SegmentOutcome.Cancelled.class, pausedOutcome);
                assertEquals(0, calls.get(), "Paused Goal must not start a model call");
                assertEquals("queued", jdbc.queryForObject(
                        "SELECT state FROM mate_conversation_input_queue WHERE id=?", String.class, queuedInput.id()));
                assertTrue(coordinator.settle(run, pausedOutcome, java.time.LocalDateTime.now()));
                goals.resume(goal.getId(), username);
                var resumedRun = claim(goal);
                SegmentOutcome resumedOutcome = runner.run(resumedRun, message, false);
                assertEquals(GoalStatus.COMPLETED, goals.getById(goal.getId()).getStatus(), resumedOutcome.toString());
                assertEquals("consumed", jdbc.queryForObject(
                        "SELECT state FROM mate_conversation_input_queue WHERE id=?", String.class, queuedInput.id()));
                assertTrue(coordinator.settle(resumedRun, resumedOutcome, java.time.LocalDateTime.now()));
                assertEquals("completed", continuations.get(goal.getId()).state());
                return;
            }
            if (entry.equals("scheduled-queued-foreign") || entry.equals("scheduled-queued-legacy")
                    || entry.equals("scheduled-queued-unselected")) {
                Long selectedGoalId = null;
                if (entry.equals("scheduled-queued-foreign")) {
                    String foreignConversation = UUID.randomUUID().toString();
                    jdbc.update("INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,model_provider,model_name,create_time,update_time,deleted) VALUES (?,?,?,1,?,'dashscope','json-http-fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                            IdWorker.getId(), foreignConversation, username, agentId);
                    var foreignCreate = new GoalCreateRequest(); foreignCreate.setConversationId(foreignConversation);
                    foreignCreate.setAgentId(agentId); foreignCreate.setWorkspaceId(1L);
                    foreignCreate.setTitle("Other selected Goal"); foreignCreate.setDescription("Separate conversation");
                    selectedGoalId = goals.create(foreignCreate, username).getId();
                } else if (entry.equals("scheduled-queued-unselected")) {
                    selectedGoalId = 0L;
                }
                Long queuedUserId = entry.equals("scheduled-queued-legacy") ? null : userId;
                var queuedInput = new vip.mate.channel.web.ConversationInputQueueStore(jdbc, json).enqueue(
                        conversation, agentId, username, "Do not run this queued input", List.of(), queuedUserId,
                        selectedGoalId, java.time.LocalDateTime.now());
                assertEquals(selectedGoalId, queuedInput.selectedGoalId());
                assertEquals(queuedUserId, queuedInput.requesterUserId());

                SegmentOutcome outcome = runner.run(run, message, false);

                assertInstanceOf(SegmentOutcome.Continue.class, outcome);
                assertEquals(0, calls.get(), "The unavailable queued selection must not start a model call");
                assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
                assertEquals("consumed", jdbc.queryForObject(
                        "SELECT state FROM mate_conversation_input_queue WHERE id=?", String.class, queuedInput.id()));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_message WHERE conversation_id=? AND role='user' AND content=?",
                        Integer.class, conversation, "Do not run this queued input"));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_message WHERE conversation_id=? AND role='assistant' AND content LIKE ?",
                        Integer.class, conversation, "%was not run because its selected Goal%"));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?",
                        Integer.class, goal.getId()));
                assertTrue(coordinator.settle(run, outcome, java.time.LocalDateTime.now()));
                return;
            }
            Long queuedInputId = null;
            if (entry.equals("scheduled-queued")) {
                var queuedInput = new vip.mate.channel.web.ConversationInputQueueStore(jdbc, json).enqueue(
                        conversation, agentId, username, message, List.of(), userId, goal.getId(),
                        java.time.LocalDateTime.now());
                queuedInputId = queuedInput.id();
                assertEquals(goal.getId(), queuedInput.selectedGoalId());
            }
            SegmentOutcome outcome = runner.run(run, message, recovered);
            if (queuedInputId != null) assertEquals("consumed", jdbc.queryForObject(
                    "SELECT state FROM mate_conversation_input_queue WHERE id=?", String.class, queuedInputId));
            assertEquals(accepted ? GoalStatus.COMPLETED : GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus(), outcome.toString());
            if (!accepted) assertInstanceOf(SegmentOutcome.Retry.class, outcome, "Runner must consume the actual rejected-completion event");
            var savedAttempt = attempts.get(run.attempt().id());
            assertEquals("message_saved", savedAttempt.checkpointType());
            assertNotNull(savedAttempt.assistantMessageId());
            assertTrue(jdbc.queryForObject("SELECT content FROM mate_message WHERE id=?", String.class,
                savedAttempt.assistantMessageId()).contains(accepted ? "Managed JSON fixture completed." : "PASS from offline fixture."));
            assertTrue(coordinator.settle(run, outcome, java.time.LocalDateTime.now()));
            assertEquals(accepted ? "succeeded" : "retryable", attempts.get(run.attempt().id()).state());
            assertEquals(accepted ? "completed" : "retry", continuations.get(goal.getId()).state());
        } else if (entry.equals("stream")) {
            String events = requestBody("POST", "/api/v1/chat/stream", token,
                Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", message));
            assertTrue(events.contains("data:"), events);
            assertTrue(events.contains("Managed JSON fixture completed."), events);
        } else {
            JsonNode result = request("POST", "/api/v1/chat?agentId=" + agentId, token,
                Map.of("conversationId", conversation, "message", message));
            assertEquals(200, result.path("code").asInt(), result.toString());
            assertTrue(result.path("data").asText().contains("Managed JSON fixture completed."), result.toString());
        }
        assertEquals(accepted ? GoalStatus.COMPLETED : GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
        assertEquals(accepted, bindings.state(goal.getId(), username).getFirst().acceptanceEligible());
        assertTrue(calls.get() >= (accepted ? 6 : 2) && calls.get() <= (recheck ? 12 : accepted ? 10 : 4), "Bounded offline model calls: " + calls.get());
        if (!accepted) assertEquals(recovered ? 1 : 0,
            jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
        if (recheck) assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()),
            "A changed goal definition requires a fresh binding, not another publication of unchanged bytes");
        if (reuse) assertEquals(32, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()),
            "Checking and completing a current version must not consume another publication");
        JsonNode currentRequirements = request("GET", "/api/v1/goals/" + goal.getId() + "/json-acceptance", token, null);
        assertEquals(accepted ? "completed" : "active", currentRequirements.path("data").path("status").asText());
        verify(modelFactory, atLeastOnce()).buildFor(any(), any());
    }

    @org.junit.jupiter.api.Test
    void oldJwtCannotConfigureManagedRequirementsAfterUsernameIsReassigned() throws Exception {
        String username = "reassigned-json-" + UUID.randomUUID();
        String conversation = UUID.randomUUID().toString();
        String password = "OfflineFixtureOnly-20260914";
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password);
        long oldId = IdWorker.getId(), newId = IdWorker.getId();
        jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", oldId, username, hash);
        jdbc.update("INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,create_time,update_time,deleted) VALUES (?,?,?,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId(), conversation, username);
        var create = new GoalCreateRequest(); create.setConversationId(conversation); create.setAgentId(1L); create.setWorkspaceId(1L);
        create.setTitle("Reassigned account JSON fixture"); create.setDescription("Produce JSON"); create.setPersistentExecution(false); create.setAutoFollowupEnabled(false);
        GoalEntity goal = goals.create(create, username);
        String token = request("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", password)).path("data").path("token").asText();
        assertFalse(token.isBlank());
        String path = "/api/v1/goals/" + goal.getId() + "/json-acceptance/requirements/r";
        assertEquals(200, request("PUT", path, token, Map.of("expectedRevision", "0", "artifactSlot", "report", "requiredFields", List.of("summary"))).path("code").asInt());
        // Simulate account retirement and a new account receiving the same username.
        jdbc.update("UPDATE mate_user SET username=?,deleted=1,enabled=FALSE WHERE id=?", "retired-" + oldId, oldId);
        jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", newId, username, hash);
        var stale = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("expectedRevision", "1", "artifactSlot", "report", "requiredFields", List.of("changed"))))).build();
        var rejected = HttpClient.newHttpClient().send(stale, HttpResponse.BodyHandlers.ofString());
        assertTrue(rejected.statusCode() == 401 || rejected.statusCode() == 403, rejected.statusCode() + ": " + rejected.body());
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM mate_goal_json_requirement WHERE goal_id=? AND criterion_key='r'", Long.class, goal.getId()));
        String fresh = request("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", password)).path("data").path("token").asText();
        assertFalse(fresh.isBlank());
        assertEquals(200, request("GET", "/api/v1/goals/" + goal.getId() + "/json-acceptance", fresh, null).path("code").asInt());
    }

    private GoalRunCoordinator.ClaimedRun claim(GoalEntity goal) {
        var run = coordinator.claim(continuations.get(goal.getId()), goals.getById(goal.getId()), java.time.LocalDateTime.now());
        assertNotNull(run);
        assertTrue(coordinator.markRunning(run, java.time.LocalDateTime.now()));
        return run;
    }

    private vip.mate.agent.context.ChatOrigin attemptOrigin(GoalEntity goal, GoalRunCoordinator.ClaimedRun run) {
        return vip.mate.agent.context.ChatOrigin.web(goal.getConversationId(), goal.getCreatedBy(), goal.getWorkspaceId(), null)
            .withAgent(goal.getAgentId()).withExecutionAttribution(new vip.mate.agent.context.ExecutionAttribution(
                goal.getId(), run.attempt().id(), null, null, run.attempt().leaseToken()));
    }

    private JsonNode request(String method, String path, String token, Object body) throws Exception {
        return json.readTree(requestBody(method, path, token, body));
    }

    private String requestBody(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json").header("X-Workspace-Id", "1");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var response = HttpClient.newHttpClient().send(builder.method(method,
            (body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}
