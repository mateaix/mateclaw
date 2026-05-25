package vip.mate.workflow.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.workflow.compiler.ir.WorkflowGraph;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputContentTypeCheckerTest {

    private WorkflowParser parser;
    private OutputContentTypeChecker checker;

    @BeforeEach
    void setUp() {
        parser = new WorkflowParser(new ObjectMapper());
        checker = new OutputContentTypeChecker();
    }

    @Test
    void textOutputAccessOnFieldFails() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"x","mode":{"type":"sequential"},"outputVar":"summary","outputContentType":"text"},
                  {"name":"b","agentName":"y","mode":{"type":"conditional","expression":"outputs.summary.tier == 'pro'"}}
                ]}
                """);
        List<CompileError> errors = checker.check(g);
        assertEquals(1, errors.size());
        assertEquals("expression.field_on_text_output", errors.get(0).code());
        assertTrue(errors.get(0).path().endsWith("mode.expression"));
    }

    @Test
    void jsonOutputAccessOnFieldPasses() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"x","mode":{"type":"sequential"},"outputVar":"enriched","outputContentType":"json"},
                  {"name":"b","agentName":"y","mode":{"type":"conditional","expression":"outputs.enriched.tier == 'pro'"}}
                ]}
                """);
        assertEquals(0, checker.check(g).size());
    }

    @Test
    void unknownOutputVarFlagged() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"x","mode":{"type":"conditional","expression":"outputs.nope.tier == 'pro'"}}
                ]}
                """);
        List<CompileError> errors = checker.check(g);
        assertTrue(errors.stream().anyMatch(e -> "expression.unknown_output_var".equals(e.code())));
    }

    @Test
    void plainOutputAccessNeverFails() {
        // outputs.foo (no field) is allowed regardless of content type.
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"x","mode":{"type":"sequential"},"outputVar":"raw","outputContentType":"text"},
                  {"name":"b","agentName":"y","mode":{"type":"sequential"},"promptTemplate":"echo {{ outputs.raw }}"}
                ]}
                """);
        assertEquals(0, checker.check(g).size());
    }

    @Test
    void dispatchAndWriteMemoryContentScanned() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"x","mode":{"type":"sequential"},"outputVar":"sum","outputContentType":"text"},
                  {"name":"d","mode":{"type":"dispatch_channel","channels":["email"],"content":"hi {{ outputs.sum.headline }}"}},
                  {"name":"w","mode":{"type":"write_memory","employeeId":"42","file":"M.md","mergeStrategy":"append","content":"{{ outputs.sum.summary }}"}}
                ]}
                """);
        // Two errors: one in dispatch_channel content, one in write_memory content.
        List<CompileError> errors = checker.check(g);
        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(e -> "expression.field_on_text_output".equals(e.code())));
    }
}
