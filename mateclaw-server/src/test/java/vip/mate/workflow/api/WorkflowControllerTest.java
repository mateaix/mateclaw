package vip.mate.workflow.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.common.result.R;
import vip.mate.workflow.compiler.WorkflowAclPort;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.repository.WorkflowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct call-through tests for {@link WorkflowController}. Skipping MockMvc
 * because the full security stack would otherwise need to boot for every
 * test; the controller is intentionally thin so the round-trip semantics
 * (HTTP status + payload shape) are easy to assert against the returned
 * {@link ResponseEntity}.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_ctrl_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import(WorkflowControllerTest.PermissiveAclConfig.class)
class WorkflowControllerTest {

    @Autowired private WorkflowController controller;
    @Autowired private WorkflowMapper workflowMapper;

    @Test
    @DisplayName("publish() returns HTTP 200 wrapping a PublishOutcome for a clean draft.")
    void publishCleanDraftReturns200() {
        Long id = createWorkflow("ctrl-clean");
        controller.saveDraft(id, new WorkflowDraftRequest(
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"x\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"hi\"}]}"), 1L, 99L);

        ResponseEntity<?> response = controller.publish(id, new WorkflowPublishRequest("first"), 1L, 99L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Object body = response.getBody();
        assertInstanceOf(R.class, body);
        @SuppressWarnings("unchecked")
        R<Object> r = (R<Object>) body;
        assertEquals(200, r.getCode());
        assertInstanceOf(vip.mate.workflow.service.WorkflowService.PublishOutcome.class, r.getData());
    }

    @Test
    @DisplayName("publish() returns HTTP 422 with structured errors for a broken draft.")
    void publishBrokenDraftReturns422() {
        Long id = createWorkflow("ctrl-broken");
        // collect-without-fan_out → schema validator emits step.collect.no_preceding_fan_out.
        controller.saveDraft(id, new WorkflowDraftRequest(
                "{\"steps\":[{\"name\":\"j\",\"mode\":{\"type\":\"collect\"}}]}"), 1L, 99L);

        ResponseEntity<?> response = controller.publish(id, null, null, 99L);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());

        @SuppressWarnings("unchecked")
        R<CompileErrorResponse> body = (R<CompileErrorResponse>) response.getBody();
        assertInstanceOf(CompileErrorResponse.class, body.getData());
        CompileErrorResponse data = body.getData();
        assertTrue(data.errorCount() >= 1);
        assertTrue(data.errors().stream().anyMatch(e ->
                "step.collect.no_preceding_fan_out".equals(e.code())));
    }

    @Test
    @DisplayName("compileDraft() surfaces errors without persisting a revision.")
    void compileEndpointReturns422OnErrors() {
        Long id = createWorkflow("ctrl-compile");
        controller.saveDraft(id, new WorkflowDraftRequest(
                "{\"steps\":[{\"name\":\"j\",\"mode\":{\"type\":\"collect\"}}]}"), 1L, 99L);

        ResponseEntity<?> response = controller.compileDraft(id, 99L);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());

        @SuppressWarnings("unchecked")
        R<CompileErrorResponse> body = (R<CompileErrorResponse>) response.getBody();
        assertInstanceOf(CompileErrorResponse.class, body.getData());
    }

    private Long createWorkflow(String name) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setWorkspaceId(99L);
        wf.setName(name);
        wf.setEnabled(true);
        workflowMapper.insert(wf);
        return wf.getId();
    }

    @TestConfiguration
    static class PermissiveAclConfig {
        @Bean
        @Primary
        WorkflowAclPort permissiveAclPort() {
            return new WorkflowAclPort() {
                @Override public boolean agentExists(long ws, String n) { return true; }
                @Override public boolean agentIdExists(long ws, long id) { return true; }
                @Override public boolean channelAllowed(long ws, String n) { return true; }
                @Override public boolean employeeInWorkspace(long ws, String e) { return true; }
            };
        }
    }
}
