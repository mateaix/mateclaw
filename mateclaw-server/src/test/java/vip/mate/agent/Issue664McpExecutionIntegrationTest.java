package vip.mate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.repository.*;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.context.PrefixBudgetPlan;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.model.*;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.builtin.ProgressiveToolBridgeTool;
import vip.mate.tool.disclosure.*;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.guard.service.ToolGuardConfigService;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.runtime.*;
import vip.mate.tool.mcp.service.McpServerService;
import vip.mate.tool.service.ToolService;

import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP MCP transport and production build filtering; model decisions are scripted tool calls. */
class Issue664McpExecutionIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String A = McpToolNameResolver.prefixedName(66401L, "search");
    static final String B = McpToolNameResolver.prefixedName(66402L, "search");

    @Test void selectedServerExecutesThroughBridgeWithoutRecoveringUncheckedTools() throws Exception {
        try (var a = new FakeMcp(); var b = new FakeMcp()) {
            var manager = manager();
            try {
                manager.connect(a.entity(66401L)); manager.connect(b.entity(66402L));
                var callbacks = manager.getAllToolCallbacks();
                assertEquals(Set.of(A, B), names(callbacks));
                var binding = bindings();
                var bridge = new ProgressiveToolBridgeTool(mock(ToolRegistry.class), binding,
                        mock(ToolGuardConfigService.class));
                var all = new ArrayList<>(callbacks);
                all.addAll(List.of(ToolCallbacks.from(bridge)));
                all.add(callback("web_search")); all.add(callback("browser_use"));
                var scoped = AgentToolSet.fromCallbacks(List.of(), all)
                        .withAllowedToolsOnly(binding.getEffectiveToolNames(664L));
                assertTrue(names(scoped.callbacks()).contains(A));
                assertFalse(names(scoped.callbacks()).contains(B));
                var context = new ToolContext(Map.of(ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY,
                        scoped.callbackByName()));
                assertTrue(bridge.search("search", 20, context).contains(A));
                for (String excluded : List.of(B, "web_search", "browser_use", "search")) {
                    assertFalse(bridge.search("", 20, context).contains("\"name\":\"" + excluded + "\""));
                    assertTrue(bridge.describe(excluded, context).contains("not available"));
                    assertTrue(call(scoped, excluded).contains("not available"));
                }
                assertTrue(bridge.describe(A, context).contains("query"));
                assertEquals("selected MCP result", call(scoped, A));
                assertEquals(1, a.calls.size()); assertTrue(b.calls.isEmpty());
                assertEquals("search", a.calls.getFirst().get("name"));
                assertEquals(Map.of("query", "issue664"), a.calls.getFirst().get("arguments"));

                // Hard schema budgeting hides a selected core schema, while execution retains its scoped callback.
                var ts = mock(ToolService.class); when(ts.listTools()).thenReturn(List.of());
                var ms = mock(McpServerService.class);
                var server = a.entity(66401L); server.setDisclosureTier("core");
                when(ms.listAll()).thenReturn(List.of(server)); when(ms.listEnabled()).thenReturn(List.of(server));
                var registry = mock(ToolRegistry.class); when(registry.enabledToolBeanFunctionNameIndex()).thenReturn(Map.of());
                var disclosure = new DefaultToolDisclosureService(ts, ms, registry, new ToolUsageRecencyTracker());
                var demoted = disclosure.computeAutoDemotions(scoped, 1);
                assertTrue(demoted.contains(A));
                assertFalse(names(disclosure.split(scoped, Set.of(), demoted).activeCallbacks()).contains(A));
                assertTrue(disclosure.renderExtensionCatalog(scoped, 8192, demoted).contains(A));
                assertEquals("selected MCP result", call(scoped, A));

                var denied = scoped.withDeniedToolsFiltered(Set.of(A)).withAllowedToolsOnly(binding.getEffectiveToolNames(664L));
                assertTrue(call(denied, A).contains("not available"));
                assertEquals(2, a.calls.size());
                // Oversized remote text is preserved on disk, then retrieved by the actual read_file callback.
                a.resultText = "fixture line\n".repeat(1000) + "TAIL_EVIDENCE_664\n";
                var dir = java.nio.file.Files.createTempDirectory("issue664-spill-");
                try {
                    var props = new vip.mate.agent.graph.executor.ToolResultProperties();
                    props.setStorageBaseDir(dir.toString());
                    var storage = new vip.mate.agent.graph.executor.ToolResultStorage(props);
                    var reader = new vip.mate.tool.builtin.ReadFileTool(mock(vip.mate.i18n.I18nService.class));
                    var withReader = new ArrayList<>(scoped.callbacks());
                    withReader.addAll(List.of(ToolCallbacks.from(reader)));
                    var readScope = AgentToolSet.fromCallbacks(List.of(), withReader)
                            .withAllowedToolsOnly(binding.getEffectiveToolNames(664L));
                    assertTrue(names(readScope.callbacks()).contains("read_file"));
                    var executor = new ToolExecutionExecutor(readScope, (n,args) -> ToolGuardResult.allow(), null, null);
                    ReflectionTestUtils.setField(executor, "resultStorage", storage);
                    var spilled = executor.execute(List.of(new AssistantMessage.ToolCall("large", "function", "tool_call",
                            "{\"toolName\":\"" + A + "\",\"arguments\":{\"query\":\"large\"}}")),
                            "issue664-large", "664", false, "user", null).responses().getFirst().responseData();
                    assertTrue(spilled.startsWith(vip.mate.agent.graph.executor.ToolResultStorage.SPILL_MARKER_PREFIX));
                    java.nio.file.Path file;
                    try (var files = java.nio.file.Files.walk(dir)) { file = files.filter(java.nio.file.Files::isRegularFile).findFirst().orElseThrow(); }
                    assertEquals(a.resultText, java.nio.file.Files.readString(file));
                    var readArgs = JSON.writeValueAsString(Map.of("filePath", file.toString(), "startLine", 1001));
                    var retrieved = executor.execute(List.of(new AssistantMessage.ToolCall("read", "function", "read_file", readArgs)),
                            "issue664-large", "664", false, "user", null).responses().getFirst().responseData();
                    assertTrue(retrieved.contains("TAIL_EVIDENCE_664"), retrieved);
                } finally {
                    try (var files = java.nio.file.Files.walk(dir)) {
                        for (var file : files.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(file);
                    }
                }
                manager.remove(66401L);
                var offline = AgentToolSet.fromCallbacks(List.of(), manager.getAllToolCallbacks())
                        .withAllowedToolsOnly(binding.getEffectiveToolNames(664L));
                assertTrue(offline.isEmpty());
                assertTrue(call(offline, A).contains("not available"));
                assertTrue(b.calls.isEmpty());
            } finally { manager.closeAll(); }
        }
    }

    @Test void scriptedModelRequestRunsSelectedMcpThroughProductionGraph() throws Exception {
        var tracker = new vip.mate.channel.web.ChatStreamTracker(JSON);
        try (var server = new FakeMcp()) {
            var manager = manager();
            try {
                manager.connect(server.entity(66401L));
                var bridge = new ProgressiveToolBridgeTool(mock(ToolRegistry.class), bindings(), mock(ToolGuardConfigService.class));
                var callbacks = new ArrayList<>(manager.getAllToolCallbacks());
                callbacks.addAll(List.of(ToolCallbacks.from(bridge)));
                var scoped = AgentToolSet.fromCallbacks(List.of(), callbacks).withAllowedToolsOnly(bindings().getEffectiveToolNames(664L));
                var builder = mock(AgentGraphBuilder.class, CALLS_REAL_METHODS);
                for (var field : AgentGraphBuilder.class.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive())
                        ReflectionTestUtils.setField(builder, field.getName(), mock(field.getType()));
                }
                ReflectionTestUtils.setField(builder, "streamTracker", tracker);
                ReflectionTestUtils.setField(builder, "providerHealthTracker",
                        new ProviderHealthTracker(new ProviderHealthProperties()));
                ReflectionTestUtils.setField(builder, "graphObservationProperties", new vip.mate.config.GraphObservationProperties());
                ReflectionTestUtils.setField(builder, "toolTimeoutProperties", new vip.mate.config.ToolTimeoutProperties());
                ReflectionTestUtils.setField(builder, "conversationWindowManager", null);
                ReflectionTestUtils.setField(builder, "progressLedgerService", null);
                ReflectionTestUtils.setField(builder, "executionCheckpoint", null);
                ReflectionTestUtils.setField(builder, "executionEvidenceRecorder", null);
                ReflectionTestUtils.setField(builder, "toolResultStorage", null);
                ReflectionTestUtils.setField(builder, "generatedFileCache", null);
                var guard = (vip.mate.tool.guard.service.ToolGuardService) ReflectionTestUtils.getField(builder, "toolGuardService");
                when(guard.evaluate(any(), eq(true))).thenReturn(vip.mate.tool.guard.model.GuardEvaluation.allow(A));
                var disclosure = disclosure(server.entity(66401L));
                ReflectionTestUtils.setField(builder, "toolDisclosureService", disclosure);
                var advertised = disclosure.split(scoped, Set.of(), Set.of()).activeCallbacks();
                var prompts = new CopyOnWriteArrayList<org.springframework.ai.chat.prompt.Prompt>();
                var model = mock(org.springframework.ai.chat.model.ChatModel.class);
                var emitted = new AssistantMessage.ToolCall("remote", "function", "tool_call",
                        "{\"toolName\":\"" + A + "\",\"arguments\":{\"query\":\"model-query\"}}");
                when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
                    prompts.add(inv.getArgument(0));
                    AssistantMessage answer = prompts.size() == 1
                            ? AssistantMessage.builder().content("").toolCalls(List.of(emitted)).build()
                            : new AssistantMessage(prompts.size() == 2 ? "selected MCP result" :
                                    "{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"Selected MCP output matches answer\"}");
                    return reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(
                            List.of(new org.springframework.ai.chat.model.Generation(answer))));
                });
                var graph = builder.buildReActGraph(scoped, model, 5, null);
                var states = graph.stream(Map.of(vip.mate.agent.graph.state.MateClawStateKeys.CONVERSATION_ID, "issue664-model",
                        vip.mate.agent.graph.state.MateClawStateKeys.SYSTEM_PROMPT, "Use the selected catalog tool " + A,
                        vip.mate.agent.graph.state.MateClawStateKeys.USER_MESSAGE, "Search fixture",
                        vip.mate.agent.graph.state.MateClawStateKeys.MESSAGES, List.of(new org.springframework.ai.chat.messages.UserMessage("Search fixture"))))
                        .collectList().block(java.time.Duration.ofSeconds(15));
                assertNotNull(states); assertFalse(states.isEmpty());
                assertEquals("selected MCP result", states.getLast().state().value(vip.mate.agent.graph.state.MateClawStateKeys.FINAL_ANSWER, ""));
                assertFalse(prompts.isEmpty());
                var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompts.getFirst().getOptions();
                assertEquals(names(advertised), names(options.getToolCallbacks()));
                assertFalse(names(options.getToolCallbacks()).contains(A));
                assertEquals(1, server.calls.size());
                assertEquals(Map.of("query", "model-query"), server.calls.getFirst().get("arguments"));
                assertTrue(prompts.stream().skip(1).anyMatch(prompt -> prompt.getContents().contains("selected MCP result")));
            } finally { manager.closeAll(); }
        } finally { tracker.onShutdown(); }
    }

    @Test void bothProductionBuildPathsReceiveFilteredToolsAndPrompt() throws Exception {
        for (String type : List.of("react", "plan_execute")) {
          for (boolean denySelected : List.of(false, true)) {
            var builder = mock(AgentGraphBuilder.class, CALLS_REAL_METHODS);
            for (var field : AgentGraphBuilder.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive())
                    ReflectionTestUtils.setField(builder, field.getName(), mock(field.getType()));
            }
            var registry = (ToolRegistry) ReflectionTestUtils.getField(builder, "toolRegistry");
            var enabled = AgentToolSet.fromCallbacks(List.of(),
                    List.of(callback(A), callback(B), callback("web_search"), callback("browser_use")));
            when(registry.getEnabledToolSet()).thenReturn(enabled);
            var guardConfig = (ToolGuardConfigService) ReflectionTestUtils.getField(builder, "toolGuardConfigService");
            when(guardConfig.getDeniedTools()).thenReturn(denySelected ? Set.of(A) : Set.of());
            ReflectionTestUtils.setField(builder, "agentBindingService", bindings());
            var provider = new ModelProviderEntity(); provider.setProviderId("fixture"); provider.setChatModel("openai-compatible");
            var model = new ModelConfigEntity(); model.setProvider("fixture"); model.setModelName("fixture");
            var models = (vip.mate.llm.service.ModelConfigService) ReflectionTestUtils.getField(builder, "modelConfigService");
            when(models.resolveModel(any())).thenReturn(model);
            var providers = (vip.mate.llm.service.ModelProviderService) ReflectionTestUtils.getField(builder, "modelProviderService");
            when(providers.getProviderConfig("fixture")).thenReturn(provider);
            when(providers.isProviderConfigured("fixture")).thenReturn(true);
            var planner = (vip.mate.agent.context.PrefixBudgetPlanner) ReflectionTestUtils.getField(builder, "prefixBudgetPlanner");
            when(planner.plan(any(), anyInt(), anyInt())).thenReturn(PrefixBudgetPlan.unlimited(16000));
            var captured = new java.util.concurrent.atomic.AtomicReference<AgentToolSet>();
            if (type.equals("react")) doAnswer(inv -> { captured.set(inv.getArgument(0)); return mock(vip.mate.agent.graph.StateGraphReActAgent.class); })
                    .when(builder).buildReActAgent(any(), any(), anyInt(), any(), any(), any(), anySet());
            else doAnswer(inv -> { captured.set(inv.getArgument(0)); return mock(vip.mate.agent.graph.plan.StateGraphPlanExecuteAgent.class); })
                    .when(builder).buildPlanExecuteAgent(any(), any(), anyInt(), any(), any(), anySet());
            var entity = new AgentEntity(); entity.setId(664L); entity.setName("fixture"); entity.setAgentType(type);
            var agent = builder.build(entity);
            assertEquals(denySelected ? Set.of() : Set.of(A), names(captured.get().callbacks()));
            String prompt = (String) ReflectionTestUtils.getField(agent, "systemPrompt");
            assertFalse(prompt.contains("web_search")); assertFalse(prompt.contains("browser_use"));
            assertFalse(prompt.contains("调用 search"));
          }
        }
    }

    static Set<String> names(List<ToolCallback> callbacks) {
        return callbacks.stream().map(cb -> cb.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet());
    }
    static ToolCallback callback(String name) {
        var cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name(name).description(name).inputSchema("{\"type\":\"object\"}").build());
        return cb;
    }
    static String call(AgentToolSet set, String target) {
        var executor = new ToolExecutionExecutor(set, (n,a) -> ToolGuardResult.allow(), null, null);
        return executor.execute(List.of(new AssistantMessage.ToolCall("call", "function", "tool_call",
                "{\"toolName\":\"" + target + "\",\"arguments\":{\"query\":\"issue664\"}}")),
                "issue664", "664", false, "user", null).responses().getFirst().responseData();
    }
    static AgentBindingService bindings() {
        var agents = mock(AgentMapper.class); var agent = new AgentEntity(); agent.setId(664L); agent.setSkillsDisabled(true);
        when(agents.selectById(664L)).thenReturn(agent);
        var tools = mock(AgentToolBindingMapper.class); var row = new AgentToolBinding(); row.setToolName(A); row.setEnabled(true);
        when(tools.selectList(any())).thenReturn(List.of(row));
        return new AgentBindingService(mock(AgentSkillBindingMapper.class), tools,
                mock(AgentProviderPreferenceMapper.class), mock(AgentWikiKbBindingMapper.class),
                mock(vip.mate.wiki.repository.WikiKnowledgeBaseMapper.class), mock(vip.mate.skill.runtime.SkillRuntimeService.class),
                mock(vip.mate.tool.service.AvailableToolService.class), agents,
                mock(vip.mate.skill.repository.SkillMapper.class), mock(vip.mate.skill.acp.AcpSkillBridge.class));
    }
    static DefaultToolDisclosureService disclosure(McpServerEntity server) {
        var tools = mock(ToolService.class);
        when(tools.listTools()).thenReturn(List.of());
        var servers = mock(McpServerService.class);
        when(servers.listAll()).thenReturn(List.of(server));
        when(servers.listEnabled()).thenReturn(List.of(server));
        var registry = mock(ToolRegistry.class);
        when(registry.enabledToolBeanFunctionNameIndex()).thenReturn(Map.of());
        return new DefaultToolDisclosureService(tools, servers, registry, new ToolUsageRecencyTracker());
    }
    static McpClientManager manager() {
        return new McpClientManager(mock(ApplicationEventPublisher.class),
                new McpIdentityForwardService(new McpIdentityForwardProperties()), null, JSON);
    }
    static class FakeMcp implements AutoCloseable {
        volatile String resultText = "selected MCP result";
        final HttpServer http; final List<Map<String,Object>> calls = new CopyOnWriteArrayList<>();
        FakeMcp() throws Exception {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/mcp", exchange -> {
                if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405,-1); exchange.close(); return; }
                var request = JSON.readTree(exchange.getRequestBody());
                if (!request.has("id")) { exchange.sendResponseHeaders(202,-1); exchange.close(); return; }
                Object result = switch (request.path("method").asText()) {
                    case "initialize" -> Map.of("protocolVersion", request.path("params").path("protocolVersion").asText(),
                            "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "fixture", "version", "1"));
                    case "tools/list" -> Map.of("tools", List.of(Map.of("name", "search", "description", "Search fixture",
                            "inputSchema", Map.of("type", "object", "required", List.of("query"), "properties", Map.of("query", Map.of("type", "string"))))));
                    case "tools/call" -> { calls.add(JSON.convertValue(request.path("params"), Map.class));
                        yield Map.of("content", List.of(Map.of("type", "text", "text", resultText)), "isError", false); }
                    default -> Map.of();
                };
                byte[] body = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            }); http.start();
        }
        McpServerEntity entity(long id) {
            var s = new McpServerEntity(); s.setId(id); s.setName("fixture-" + id); s.setTransport("streamable_http");
            s.setToolsCacheJson("[{\"name\":\"search\",\"description\":\"Search fixture\"}]");
            s.setUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/mcp"); s.setConnectTimeoutSeconds(3); s.setReadTimeoutSeconds(3); return s;
        }
        public void close() { http.stop(0); }
    }
}
