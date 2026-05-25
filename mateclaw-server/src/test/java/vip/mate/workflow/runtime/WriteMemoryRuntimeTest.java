package vip.mate.workflow.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.compiler.ir.WorkflowGraph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime-level test for write_memory: stubs the memory writer and verifies
 * (a) the rendered content reaches the writer with the right merge strategy,
 * (b) failures bubble up as a failed step / run, and
 * (c) a template-form employeeId resolves at runtime against the run context.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_writemem_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
@Import({StubAgentInvokerConfig.class,
        WriteMemoryRuntimeTest.StubMemoryWriterConfig.class})
class WriteMemoryRuntimeTest {

    @Autowired private WorkflowRunner runner;
    @Autowired private WorkflowParser parser;
    @Autowired private StubMemoryWriter stubWriter;
    @Autowired private StubAgentInvoker stubInvoker;

    @Test
    @DisplayName("Renders content + applies the configured merge strategy via the writer.")
    void rendersAndDelegatesToWriter() {
        stubInvoker.reset();
        stubInvoker.respond("collect", "{\"role\":\"owner\"}");
        stubWriter.reset();

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"collect","agentName":"collect","mode":{"type":"sequential"},
                     "promptTemplate":"x","outputVar":"d","outputContentType":"json"},
                    {"name":"persist",
                     "mode":{"type":"write_memory","employeeId":"42","file":"MEMORY.md",
                             "mergeStrategy":"upsert_kv",
                             "content":"role: {{ outputs.d.role }}"}}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(70L, 1L, 99L, "manual", Map.of()));
        assertEquals("succeeded", result.state());

        StubMemoryWriter.Call call = stubWriter.lastCall();
        assertEquals("42", call.employeeId);
        assertEquals("MEMORY.md", call.file);
        assertEquals("upsert_kv", call.mergeStrategy);
        assertEquals("role: owner", call.content);
        assertEquals(99L, call.workspaceId);
    }

    @Test
    @DisplayName("A failing writer fails the step and the run.")
    void failingWriterFailsRun() {
        stubInvoker.reset();
        stubWriter.reset();
        stubWriter.failNext("disk full");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"persist",
                     "mode":{"type":"write_memory","employeeId":"42","file":"M.md",
                             "mergeStrategy":"append","content":"hello"}}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(71L, 1L, 99L, "manual", Map.of()));
        assertEquals("failed", result.state());
        assertTrue(result.errorMessage().contains("disk full"),
                "underlying writer error should propagate: " + result.errorMessage());
    }

    @Test
    @DisplayName("Template-form employeeId is resolved against the run context at runtime.")
    void employeeIdTemplateResolves() {
        stubInvoker.reset();
        stubInvoker.respond("collect", "{\"assignee\":\"77\"}");
        stubWriter.reset();

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"collect","agentName":"collect","mode":{"type":"sequential"},
                     "promptTemplate":"x","outputVar":"d","outputContentType":"json"},
                    {"name":"persist",
                     "mode":{"type":"write_memory",
                             "employeeId":"{{ outputs.d.assignee }}",
                             "file":"NOTES.md","mergeStrategy":"append","content":"note"}}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(72L, 1L, 99L, "manual", Map.of()));
        assertEquals("succeeded", result.state());
        assertEquals("77", stubWriter.lastCall().employeeId);
    }

    @TestConfiguration
    static class StubMemoryWriterConfig {
        @Bean
        @Primary
        StubMemoryWriter stubMemoryWriter() { return new StubMemoryWriter(); }
    }

    static class StubMemoryWriter implements MemoryWriter {
        record Call(long workspaceId, String employeeId, String file,
                    String mergeStrategy, String content) {}

        private final Map<String, Call> calls = new ConcurrentHashMap<>();
        private volatile String forcedFailure;

        void reset() {
            calls.clear();
            forcedFailure = null;
        }

        void failNext(String message) { forcedFailure = message; }

        Call lastCall() {
            return calls.values().stream().reduce((a, b) -> b)
                    .orElseThrow(() -> new AssertionError("no writer calls observed"));
        }

        @Override
        public Result write(long workspaceId, String employeeId, String file,
                            String mergeStrategy, String content) {
            calls.put(workspaceId + ":" + employeeId + ":" + file,
                    new Call(workspaceId, employeeId, file, mergeStrategy, content));
            String fail = forcedFailure;
            if (fail != null) {
                forcedFailure = null;
                return Result.fail(fail);
            }
            return Result.ok(mergeStrategy + " ok");
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
