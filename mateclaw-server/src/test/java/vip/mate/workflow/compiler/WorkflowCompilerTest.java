package vip.mate.workflow.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowCompilerTest {

    private WorkflowCompiler compiler;

    @BeforeEach
    void setUp() {
        WorkflowParser parser = new WorkflowParser(new ObjectMapper());
        compiler = new WorkflowCompiler(
                parser,
                new WorkflowSchemaValidator(),
                new OutputContentTypeChecker(),
                new WorkflowAclValidator(),
                new PebbleSubsetEvaluator()
        );
    }

    @Test
    void allPhasesPassOnRealisticOnboardingFlow() {
        String json = """
                {
                  "schemaVersion": "1.0",
                  "inputs": [{"name":"customerData","type":"json"}],
                  "steps": [
                    {"name":"enrich","agentName":"data-analyst","promptTemplate":"Enrich strict JSON: {{ inputs.customerData }}","mode":{"type":"sequential"},"outputVar":"enriched","outputContentType":"json"},
                    {"name":"vip-route","agentName":"sales","promptTemplate":"VIP onboarding: {{ outputs.enriched }}","mode":{"type":"conditional","expression":"outputs.enriched.tier == 'enterprise'"}},
                    {"name":"notify-feishu","agentName":"messenger","mode":{"type":"fan_out"},"promptTemplate":"feishu: {{ outputs.enriched }}"},
                    {"name":"notify-email","agentName":"messenger","mode":{"type":"fan_out"},"promptTemplate":"email: {{ outputs.enriched }}"},
                    {"name":"wait-acks","mode":{"type":"collect"}},
                    {"name":"record","mode":{"type":"write_memory","employeeId":"42","file":"MEMORY.md","mergeStrategy":"append","content":"onboarded"}}
                  ]
                }
                """;
        WorkflowAclPort port = new WorkflowAclPort() {
            @Override public boolean agentExists(long ws, String name) { return Set.of("data-analyst","sales","messenger").contains(name); }
            @Override public boolean agentIdExists(long ws, long id) { return false; }
            @Override public boolean channelAllowed(long ws, String name) { return true; }
            @Override public boolean employeeInWorkspace(long ws, String e) { return "42".equals(e); }
        };
        // PublishContext is (workspaceId, publisherId): ws=99, publisher=1.
        WorkflowCompiler.Result result = compiler.compile(json, new PublishContext(99L, 1L), port);
        assertTrue(result.ok(),
                "expected clean compile but got: " + result.errors());
    }

    @Test
    void allPhasesAccumulateErrorsInOneCompile() {
        // Multiple kinds of errors at once: bad mergeStrategy + collect-without-fan_out + unknown agent.
        String json = """
                {"steps":[
                  {"name":"bad","agentName":"missing","mode":{"type":"sequential"}},
                  {"name":"join","mode":{"type":"collect"}},
                  {"name":"w","mode":{"type":"write_memory","employeeId":"42","file":"M.md","mergeStrategy":"weird","content":"x"}}
                ]}
                """;
        WorkflowAclPort port = new WorkflowAclPort() {
            @Override public boolean agentExists(long ws, String name) { return false; }
            @Override public boolean agentIdExists(long ws, long id) { return false; }
            @Override public boolean channelAllowed(long ws, String name) { return false; }
            @Override public boolean employeeInWorkspace(long ws, String e) { return true; }
        };
        // PublishContext is (workspaceId, publisherId): ws=99, publisher=1.
        WorkflowCompiler.Result result = compiler.compile(json, new PublishContext(99L, 1L), port);
        assertTrue(result.errors().stream().anyMatch(e -> "step.collect.no_preceding_fan_out".equals(e.code())));
        assertTrue(result.errors().stream().anyMatch(e -> "step.write_memory.merge_unknown".equals(e.code())));
        assertTrue(result.errors().stream().anyMatch(e -> "acl.agent_not_resolvable".equals(e.code())));
    }

    @Test
    void requireOkThrowsWithAllErrors() {
        String json = """
                {"steps":[{"name":"a","mode":{"type":"sequential"}}]}
                """;
        WorkflowAclPort permissive = new WorkflowAclPort() {
            @Override public boolean agentExists(long ws, String name) { return true; }
            @Override public boolean agentIdExists(long ws, long id) { return true; }
            @Override public boolean channelAllowed(long ws, String name) { return true; }
            @Override public boolean employeeInWorkspace(long ws, String e) { return true; }
        };
        WorkflowCompiler.Result result = compiler.compile(json, new PublishContext(99L, 1L), permissive);
        WorkflowCompileFailedException e = assertThrows(WorkflowCompileFailedException.class, result::requireOk);
        assertEquals(result.errors().size(), e.errors().size());
    }
}
