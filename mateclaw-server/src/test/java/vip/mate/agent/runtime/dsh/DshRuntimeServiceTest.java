package vip.mate.agent.runtime.dsh;

import java.nio.file.Files;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.config.ConversationWindowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.Disposable;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfigService;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfiguration;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class DshRuntimeServiceTest {

    @Test
    void sessionWorkingDirectoryWinsOverGlobalRuntimeDirectory() {
        RuntimeSession session = new RuntimeSession(
                "session-1", "conversation-1", 1L, 2L, "model",
                Path.of("/workspace/agent"), Map.of());
        DshRuntimeConfiguration configuration = new DshRuntimeConfiguration(
                "/bin/dsh", "", "/workspace/global", "", "", "");

        assertEquals(Path.of("/workspace/agent"),
                DshRuntimeService.resolveWorkingDirectory(session, configuration));
    }

    @Test
    void usageChunkBecomesContextUsageEvent() throws Exception {
        DshRuntimeService service = service();
        RuntimeEvent event = service.mapEvent("session-1", 7,
                new ObjectMapper().readTree("""
                        {
                          "type":"assistant/chunk",
                          "data":{"chunk":{"type":"usage","usage":{"inputTokens":123,"outputTokens":45}}}
                        }
                        """));

        assertEquals(RuntimeEventType.CONTEXT_USAGE, event.type());
        assertEquals(123L, ((Number) event.data().get("promptTokens")).longValue());
        assertEquals(45L, ((Number) event.data().get("completionTokens")).longValue());
        assertEquals(123L, ((Number) event.data().get("inputTokens")).longValue());
        assertEquals(45L, ((Number) event.data().get("outputTokens")).longValue());
    }

    @Test
    void cancelProcessDestroysLiveProcess() {
        Process process = Mockito.mock(Process.class);
        Mockito.when(process.isAlive()).thenReturn(true);

        DshRuntimeService.cancelProcess(process);

        Mockito.verify(process).destroy();
        Mockito.verify(process).destroyForcibly();
    }

    @Test
    void cancelProcessStopsChildHoldingParentPipes() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "sleep 30").start();
        try {
            DshRuntimeService.cancelProcess(process);
            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "parent process should stop promptly");
            assertTrue(process.exitValue() != 0 || !process.isAlive());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void streamSubscriptionReturnsBeforeSynchronousDshReadLoopFinishes(@TempDir Path temp) throws Exception {
        Path script = Files.writeString(temp.resolve("synthetic-sdk"), "#!/bin/sh\nsleep 5\n");
        assertTrue(script.toFile().setExecutable(true));
        DshRuntimeService service = service(script.toString());
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setWorkspaceId(2L);
        agent.setModelName("model");

        Disposable subscription = assertTimeout(Duration.ofSeconds(2), () ->
                service.stream(agent, "hello", "conversation", "model").subscribe(delta -> {}, error -> {}));
        assertFalse(subscription.isDisposed());
        subscription.dispose();
    }

    @Test
    void oldCommandStringRequiresMigration() {
        DshRuntimeConfiguration configuration = new DshRuntimeConfiguration("/bin/sh --stdio", "", "/tmp", "", "", "");
        assertTrue(configuration.migrationRequired());
    }

    @Test
    void childEnvironmentKeepsOnlyRuntimeVariablesAndExplicitCredentials() {
        RuntimeSession session = session(Path.of("/workspace/project"));
        DshRuntimeConfiguration configuration = new DshRuntimeConfiguration(
                "/bin/dsh", "/opt/dsh/cordis.yml", "/workspace/global",
                "https://configured.example/v1", "model", "configured-key");
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setApiKey("provider-key");
        provider.setBaseUrl("https://provider.example/v1");
        Map<String, String> inherited = new HashMap<>();
        inherited.put("PATH", "/usr/bin");
		inherited.put("HOME", "/Users/tester");
        inherited.put("AWS_SECRET_ACCESS_KEY", "must-not-leak");
        inherited.put("DEEPSEEK_API_KEY", "inherited-key");
        inherited.put("DSH_CORDIS_CONFIG", "/stale/cordis.yml");

        Map<String, String> environment = DshRuntimeService.childEnvironment(
                inherited, session, configuration, provider);

        assertEquals("/usr/bin", environment.get("PATH"));
		assertEquals("/Users/tester", environment.get("HOME"));
        assertEquals("/workspace/project", environment.get("DSH_CWD"));
        assertFalse(environment.containsKey("DSH_CORDIS_CONFIG"));
        assertTrue(environment.containsKey("DSH_HOME"));
        assertEquals("configured-key", environment.get("DEEPSEEK_API_KEY"));
        assertEquals("https://configured.example/v1", environment.get("DEEPSEEK_BASE_URL"));
        assertFalse(environment.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(environment.containsValue("inherited-key"));
        assertFalse(environment.containsValue("/stale/cordis.yml"));
    }

    @Test
    void committedMessageUsesContentNotStream() throws Exception {
        RuntimeEvent event = service().mapEvent("root", 1, new ObjectMapper().readTree("""
                {"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"answer"}]},
                "stream":[{"type":"text-delta","text":"duplicate"}]}}
                """));
        Assertions.assertNotNull(event);
        assertEquals("answer", event.text());
    }

    @Test void ptcCallerCancellationIsDistinctFromToolFailure() throws Exception {
        var json = new ObjectMapper();
        for (String code : List.of("ABORTED", "CODE_RUN_FAILED")) {
            RuntimeEvent event = service().mapEvent("root", 1, json.readTree("""
                    {"type":"tool/result","data":{"message":{"toolCallId":"c1","isError":true,"content":[]},
                    "error":{"name":"ToolError","code":"%s","reason":"private runtime detail"}}}
                    """.formatted(code)));
            assertEquals(code.equals("ABORTED"), event.data().get("cancelled"));
            assertEquals(code, event.data().get("errorCode"));
            assertEquals(true, event.data().get("isError"));
            assertEquals(false, event.data().get("success"));
            assertEquals("c1", event.data().get("callId"));
            assertFalse(event.data().toString().contains("private runtime detail"));
        }
    }

    @Test
    void incompleteTurnIsFailure() throws Exception {
        RuntimeEvent event = service().mapEvent("root", 1, new ObjectMapper().readTree("""
                {"type":"turn/end","data":{"reason":{"kind":"max-tokens"}}}
                """));
        Assertions.assertNotNull(event);
        assertEquals(RuntimeEventType.FAILED, event.type());
    }

    @Test
    void toolUsesSdkNameAndCallId() throws Exception {
        RuntimeEvent event = service().mapEvent("root", 1, new ObjectMapper().readTree("""
                {"type":"tool/start","data":{"name":"read_file","callId":"call-1"}}
                """));
        assertEquals("read_file", event.data().get("toolName"));
        assertEquals("call-1", event.data().get("callId"));
    }

    @Test
    void toolResultPairsWithNestedMessageCallId() throws Exception {
        RuntimeEvent event = service().mapEvent("root", 1, new ObjectMapper().readTree("""
                {"type":"tool/result","data":{"message":{"role":"tool","toolCallId":"call-1","content":[]}}}
                """));
        assertEquals("call-1", event.data().get("callId"));
    }

    private static DshRuntimeService service() {
        return service("/bin/dsh");
    }

    private static DshRuntimeService service(String executable) {
        DshRuntimeConfigService config = Mockito.mock(DshRuntimeConfigService.class);
        Mockito.when(config.resolve()).thenReturn(new DshRuntimeConfiguration(
                executable, "", "/tmp", "", "model", "", "sdk", List.of(), Path.of(executable).getParent().resolve("homes").toString()));
        return new DshRuntimeService(new ObjectMapper(),
                Mockito.mock(ModelConfigService.class),
                Mockito.mock(ModelProviderService.class), config, new ConversationWindowProperties());
    }

    private static RuntimeSession session(Path workingDirectory) {
        return new RuntimeSession("session-1", "conversation-1", 1L, 2L,
                "model", workingDirectory, Map.of());
    }
}
