package vip.mate.workflow.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.workflow.compiler.ir.WorkflowGraph;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowSchemaValidatorTest {

    private WorkflowParser parser;
    private WorkflowSchemaValidator validator;

    @BeforeEach
    void setUp() {
        parser = new WorkflowParser(new ObjectMapper());
        validator = new WorkflowSchemaValidator();
    }

    @Test
    void minimalSequentialPasses() {
        WorkflowGraph g = parser.parse("""
                {"steps":[{"name":"a","agentName":"x","mode":{"type":"sequential"}}]}
                """);
        assertTrue(validator.validate(g).isEmpty(), "minimal sequential should pass");
    }

    @Test
    void emptyStepsRejected() {
        WorkflowGraph g = parser.parse("""
                {"steps":[]}
                """);
        List<CompileError> errors = validator.validate(g);
        assertEquals(1, errors.size());
        assertEquals("workflow.no_steps", errors.get(0).code());
    }

    @Test
    void duplicateStepNamesRejected() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"dup","agentName":"x","mode":{"type":"sequential"}},
                  {"name":"dup","agentName":"y","mode":{"type":"sequential"}}
                ]}
                """);
        List<CompileError> errors = validator.validate(g);
        assertTrue(errors.stream().anyMatch(e -> "step.name_duplicate".equals(e.code())),
                "duplicate name must be flagged");
    }

    @Test
    void singletonFanOutRejected() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"x","mode":{"type":"fan_out"}},
                  {"name":"b","mode":{"type":"collect"}}
                ]}
                """);
        List<CompileError> errors = validator.validate(g);
        assertTrue(errors.stream().anyMatch(e -> "step.fan_out.singleton".equals(e.code())),
                "single fan_out step is not a valid group");
    }

    @Test
    void fanOutWithoutCollectRejected() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"x","mode":{"type":"fan_out"}},
                  {"name":"b","agentName":"y","mode":{"type":"fan_out"}},
                  {"name":"c","agentName":"z","mode":{"type":"sequential"}}
                ]}
                """);
        List<CompileError> errors = validator.validate(g);
        assertTrue(errors.stream().anyMatch(e -> "step.fan_out.no_terminating_collect".equals(e.code())));
    }

    @Test
    void collectWithoutFanOutRejected() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"x","mode":{"type":"sequential"}},
                  {"name":"b","mode":{"type":"collect"}}
                ]}
                """);
        List<CompileError> errors = validator.validate(g);
        assertTrue(errors.stream().anyMatch(e -> "step.collect.no_preceding_fan_out".equals(e.code())));
    }

    @Test
    void conditionalRequiresExpression() {
        WorkflowGraph g = parser.parse("""
                {"steps":[{"name":"a","agentName":"x","mode":{"type":"conditional"}}]}
                """);
        List<CompileError> errors = validator.validate(g);
        assertTrue(errors.stream().anyMatch(e -> "step.conditional_expression_required".equals(e.code())));
    }

    @Test
    void awaitApprovalRequiresKindAndChannels() {
        WorkflowGraph g = parser.parse("""
                {"steps":[{"name":"a","mode":{"type":"await_approval"}}]}
                """);
        List<CompileError> errors = validator.validate(g);
        assertTrue(errors.stream().anyMatch(e -> "step.await_approval.kind_required".equals(e.code())));
        assertTrue(errors.stream().anyMatch(e -> "step.await_approval.channels_required".equals(e.code())));
    }

    @Test
    void writeMemoryRejectsUnknownMergeStrategy() {
        WorkflowGraph g = parser.parse("""
                {"steps":[{"name":"a","mode":{"type":"write_memory","employeeId":"42","file":"M.md","mergeStrategy":"weird","content":"x"}}]}
                """);
        List<CompileError> errors = validator.validate(g);
        assertTrue(errors.stream().anyMatch(e -> "step.write_memory.merge_unknown".equals(e.code())));
    }

    @Test
    void agentRequiredForSequentialAndFanOut() {
        WorkflowGraph g = parser.parse("""
                {"steps":[{"name":"a","mode":{"type":"sequential"}}]}
                """);
        List<CompileError> errors = validator.validate(g);
        assertTrue(errors.stream().anyMatch(e -> "step.agent_required".equals(e.code())));
    }

    @Test
    void tooManyStepsRejected() {
        // Build > 200 sequential steps
        StringBuilder sb = new StringBuilder("{\"steps\":[");
        for (int i = 0; i < 250; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"s").append(i).append("\",\"agentName\":\"a\",\"mode\":{\"type\":\"sequential\"}}");
        }
        sb.append("]}");
        WorkflowGraph g = parser.parse(sb.toString());
        List<CompileError> errors = validator.validate(g);
        assertTrue(errors.stream().anyMatch(e -> "workflow.too_many_steps".equals(e.code())));
    }

    @Test
    void wellFormedFanOutCollectGroupPasses() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"prep","agentName":"a","mode":{"type":"sequential"}},
                  {"name":"f1","agentName":"a","mode":{"type":"fan_out"}},
                  {"name":"f2","agentName":"a","mode":{"type":"fan_out"}},
                  {"name":"f3","agentName":"a","mode":{"type":"fan_out"}},
                  {"name":"join","mode":{"type":"collect"}},
                  {"name":"finish","agentName":"a","mode":{"type":"sequential"}}
                ]}
                """);
        assertTrue(validator.validate(g).isEmpty(), "well-formed fan_out group should validate cleanly");
    }
}
