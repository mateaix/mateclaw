package vip.mate.workflow.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowGraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowParserTest {

    private WorkflowParser parser;

    @BeforeEach
    void setUp() {
        parser = new WorkflowParser(new ObjectMapper());
    }

    @Test
    void parsesAllSevenV0Modes() {
        String json = """
                {
                  "schemaVersion": "1.0",
                  "inputs": [{"name":"customer","type":"json"}],
                  "steps": [
                    {"name":"a","agentName":"x","mode":{"type":"sequential"},"outputVar":"out_a","outputContentType":"text"},
                    {"name":"b","agentName":"y","mode":{"type":"fan_out"}},
                    {"name":"c","agentName":"z","mode":{"type":"fan_out"}},
                    {"name":"d","mode":{"type":"collect"}},
                    {"name":"e","agentName":"q","mode":{"type":"conditional","expression":"true"}},
                    {"name":"f","mode":{"type":"await_approval","approvalKind":"manual","approverChannels":["feishu"],"approvalMessage":"check"}},
                    {"name":"g","mode":{"type":"dispatch_channel","channels":["email"],"content":"hi"}},
                    {"name":"h","mode":{"type":"write_memory","employeeId":"42","file":"MEMORY.md","mergeStrategy":"append","content":"x"}}
                  ]
                }
                """;
        WorkflowGraph graph = parser.parse(json);
        assertEquals("1.0", graph.schemaVersion());
        assertEquals(1, graph.inputs().size());
        assertEquals(8, graph.steps().size());
        assertInstanceOf(StepMode.Sequential.class, graph.steps().get(0).mode());
        assertInstanceOf(StepMode.FanOut.class, graph.steps().get(1).mode());
        assertInstanceOf(StepMode.FanOut.class, graph.steps().get(2).mode());
        assertInstanceOf(StepMode.Collect.class, graph.steps().get(3).mode());
        assertInstanceOf(StepMode.Conditional.class, graph.steps().get(4).mode());
        assertInstanceOf(StepMode.AwaitApproval.class, graph.steps().get(5).mode());
        assertInstanceOf(StepMode.DispatchChannel.class, graph.steps().get(6).mode());
        assertInstanceOf(StepMode.WriteMemory.class, graph.steps().get(7).mode());
    }

    @Test
    void rejectsLoopAndInvokeSkill() {
        String json = """
                {"steps":[{"name":"l","mode":{"type":"loop"}}]}
                """;
        WorkflowParseException e = assertThrows(WorkflowParseException.class, () -> parser.parse(json));
        assertTrue(e.getMessage().contains("not supported"),
                "loop mode should be rejected with a 'not supported' message");
    }

    @Test
    void rejectsMissingMode() {
        String json = """
                {"steps":[{"name":"a","agentName":"x"}]}
                """;
        assertThrows(WorkflowParseException.class, () -> parser.parse(json));
    }

    @Test
    void emptyStringRaises() {
        assertThrows(WorkflowParseException.class, () -> parser.parse(""));
    }

    @Test
    void agentIdAcceptsNumberOrString() {
        String numericForm = """
                {"steps":[{"name":"a","agentId":123,"mode":{"type":"sequential"}}]}
                """;
        WorkflowGraph g1 = parser.parse(numericForm);
        assertEquals(Long.valueOf(123L), g1.steps().get(0).agentId());

        String stringForm = """
                {"steps":[{"name":"a","agentId":"456","mode":{"type":"sequential"}}]}
                """;
        WorkflowGraph g2 = parser.parse(stringForm);
        assertEquals(Long.valueOf(456L), g2.steps().get(0).agentId());
    }

    @Test
    void retryErrorModeWithExplicitMaxRetries() {
        String json = """
                {"steps":[{"name":"a","agentName":"x","mode":{"type":"sequential"},
                          "errorMode":{"type":"retry","maxRetries":5}}]}
                """;
        WorkflowGraph g = parser.parse(json);
        var em = g.steps().get(0).errorMode();
        assertNotNull(em);
        assertEquals(5, ((vip.mate.workflow.compiler.ir.ErrorMode.Retry) em).maxRetries());
    }

    @Test
    void absentInputsAndErrorModeAreNullSafe() {
        String json = """
                {"steps":[{"name":"a","agentName":"x","mode":{"type":"sequential"}}]}
                """;
        WorkflowGraph g = parser.parse(json);
        assertEquals(0, g.inputs().size());
        assertNull(g.steps().get(0).errorMode());
    }
}
