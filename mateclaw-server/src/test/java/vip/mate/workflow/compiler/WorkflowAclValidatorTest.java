package vip.mate.workflow.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.workflow.compiler.ir.WorkflowGraph;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowAclValidatorTest {

    private WorkflowParser parser;
    private WorkflowAclValidator validator;

    @BeforeEach
    void setUp() {
        parser = new WorkflowParser(new ObjectMapper());
        validator = new WorkflowAclValidator();
    }

    @Test
    void unknownAgentNameFlagged() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"missing","mode":{"type":"sequential"}}
                ]}
                """);
        WorkflowAclPort port = stub(Set.of(), Set.of(), Set.of(), Set.of());
        List<CompileError> errors = validator.validate(g, new PublishContext(99L, 1L), port);
        assertEquals(1, errors.size());
        assertEquals("acl.agent_not_resolvable", errors.get(0).code());
    }

    @Test
    void knownAgentPasses() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"good","mode":{"type":"sequential"}}
                ]}
                """);
        WorkflowAclPort port = stub(Set.of("good"), Set.of(), Set.of(), Set.of());
        assertTrue(validator.validate(g, new PublishContext(99L, 1L), port).isEmpty());
    }

    @Test
    void agentIdLookup() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentId":42,"mode":{"type":"sequential"}}
                ]}
                """);
        WorkflowAclPort port = stub(Set.of(), Set.of(42L), Set.of(), Set.of());
        assertTrue(validator.validate(g, new PublishContext(99L, 1L), port).isEmpty());
    }

    @Test
    void disallowedChannelFlagged() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","mode":{"type":"dispatch_channel","channels":["bad","email"],"content":"hi"}}
                ]}
                """);
        WorkflowAclPort port = stub(Set.of(), Set.of(), Set.of("email"), Set.of());
        List<CompileError> errors = validator.validate(g, new PublishContext(99L, 1L), port);
        assertEquals(1, errors.size());
        assertEquals("acl.channel_not_allowed", errors.get(0).code());
        assertTrue(errors.get(0).path().contains("mode.channels[0]"));
    }

    @Test
    void writeMemoryEmployeeOutsideWorkspaceFlagged() {
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","mode":{"type":"write_memory","employeeId":"alien","file":"M.md","mergeStrategy":"append","content":"x"}}
                ]}
                """);
        WorkflowAclPort port = stub(Set.of(), Set.of(), Set.of(), Set.of("inside"));
        List<CompileError> errors = validator.validate(g, new PublishContext(99L, 1L), port);
        assertEquals(1, errors.size());
        assertEquals("acl.employee_not_in_workspace", errors.get(0).code());
    }

    @Test
    void publishContextWorkspaceIdReachesAcl() {
        // Regression for the (workspaceId, publisherId) swap that used to
        // sit in WorkflowService.publish + WorkflowController.compileDraft
        // + WorkflowResumeController.resume. The stub below ONLY accepts
        // the agent when `ws == 99`; if the caller swaps the args, it
        // would call agentExists(publisherId=1, ...) and the agent would
        // appear missing.
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","agentName":"only-in-ws-99","mode":{"type":"sequential"}}
                ]}
                """);
        WorkflowAclPort wsScopedPort = new WorkflowAclPort() {
            @Override public boolean agentExists(long ws, String name) {
                return ws == 99L && "only-in-ws-99".equals(name);
            }
            @Override public boolean agentIdExists(long ws, long id) { return false; }
            @Override public boolean channelAllowed(long ws, String n) { return true; }
            @Override public boolean employeeInWorkspace(long ws, String e) { return true; }
        };
        // PublishContext(workspaceId=99, publisherId=1) — agent should resolve.
        assertTrue(validator.validate(g, new PublishContext(99L, 1L), wsScopedPort).isEmpty(),
                "ACL must receive workspaceId=99 from PublishContext.workspaceId()");
        // And the swapped order must NOT pass — proves the order is
        // load-bearing and a future regression would break this test.
        List<CompileError> swapped = validator.validate(g, new PublishContext(1L, 99L), wsScopedPort);
        assertEquals(1, swapped.size());
        assertEquals("acl.agent_not_resolvable", swapped.get(0).code());
    }

    @Test
    void writeMemoryEmployeeIdAsTemplateNotChecked() {
        // {{ outputs.x.assignee }} is resolved at runtime — publish-time
        // ACL skips template forms.
        WorkflowGraph g = parser.parse("""
                {"steps":[
                  {"name":"a","mode":{"type":"write_memory","employeeId":"{{ outputs.x.assignee }}","file":"M.md","mergeStrategy":"append","content":"x"}}
                ]}
                """);
        WorkflowAclPort port = stub(Set.of(), Set.of(), Set.of(), Set.of());
        assertTrue(validator.validate(g, new PublishContext(99L, 1L), port).isEmpty());
    }

    private static WorkflowAclPort stub(Set<String> agents, Set<Long> agentIds,
                                        Set<String> channels, Set<String> employees) {
        Set<String> a = new HashSet<>(agents);
        Set<Long> ai = new HashSet<>(agentIds);
        Set<String> ch = new HashSet<>(channels);
        Set<String> ee = new HashSet<>(employees);
        return new WorkflowAclPort() {
            @Override public boolean agentExists(long ws, String name) { return a.contains(name); }
            @Override public boolean agentIdExists(long ws, long id) { return ai.contains(id); }
            @Override public boolean channelAllowed(long ws, String name) { return ch.contains(name); }
            @Override public boolean employeeInWorkspace(long ws, String employeeId) { return ee.contains(employeeId); }
        };
    }
}
