package vip.mate.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import vip.mate.workflow.compiler.WorkflowAclPort;
import vip.mate.workflow.compiler.WorkflowCompileFailedException;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.model.WorkflowRevisionEntity;
import vip.mate.workflow.repository.WorkflowMapper;
import vip.mate.workflow.repository.WorkflowRevisionMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the draft → publish lifecycle: a clean draft compiles into a v1
 * revision and {@code latest_revision_id} points at it; a draft with errors
 * throws {@link WorkflowCompileFailedException} and leaves no revision row;
 * republishing bumps the revision number.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_publish_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import(WorkflowServicePublishTest.PermissiveAclConfig.class)
class WorkflowServicePublishTest {

    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkflowRevisionMapper revisionMapper;

    @Test
    @DisplayName("Publishing a clean draft creates a v1 revision and points latest_revision_id at it.")
    void publishCleanDraftCreatesV1Revision() {
        WorkflowEntity wf = newWorkflow("clean");
        wf = workflowService.create(wf);

        String draft = """
                {"steps":[
                  {"name":"step-a","agentName":"a","mode":{"type":"sequential"},
                   "promptTemplate":"hi"}
                ]}
                """;
        workflowService.saveDraft(wf.getId(), wf.getWorkspaceId(), draft, 1L);

        WorkflowService.PublishOutcome outcome = workflowService.publish(wf.getId(), wf.getWorkspaceId(), 1L, "first publish");
        assertNotNull(outcome.revision().getId());
        assertEquals(1, outcome.revision().getRevision());

        WorkflowEntity reloaded = workflowMapper.selectById(wf.getId());
        assertEquals(outcome.revision().getId(), reloaded.getLatestRevisionId());
    }

    @Test
    @DisplayName("Publishing a broken draft throws and writes no revision row.")
    void publishBrokenDraftThrows() {
        WorkflowEntity wf = workflowService.create(newWorkflow("broken"));

        // collect step without preceding fan_out — schema validator catches this.
        String draft = """
                {"steps":[
                  {"name":"j","mode":{"type":"collect"}}
                ]}
                """;
        workflowService.saveDraft(wf.getId(), wf.getWorkspaceId(), draft, 1L);

        assertThrows(WorkflowCompileFailedException.class,
                () -> workflowService.publish(wf.getId(), wf.getWorkspaceId(), 1L, null));

        long revs = revisionMapper.selectCount(new LambdaQueryWrapper<WorkflowRevisionEntity>()
                .eq(WorkflowRevisionEntity::getWorkflowId, wf.getId()));
        assertEquals(0L, revs);

        // latest_revision_id stays null too.
        WorkflowEntity reloaded = workflowMapper.selectById(wf.getId());
        assertEquals(null, reloaded.getLatestRevisionId());
    }

    @Test
    @DisplayName("Re-publishing increments the revision number.")
    void republishIncrementsRevisionNumber() {
        WorkflowEntity wf = workflowService.create(newWorkflow("dual"));
        String draft = """
                {"steps":[{"name":"step-a","agentName":"a","mode":{"type":"sequential"},
                          "promptTemplate":"hi"}]}
                """;
        workflowService.saveDraft(wf.getId(), wf.getWorkspaceId(), draft, 1L);
        workflowService.publish(wf.getId(), wf.getWorkspaceId(), 1L, "v1");

        // Edit and publish again.
        String draft2 = """
                {"steps":[{"name":"step-b","agentName":"a","mode":{"type":"sequential"},
                          "promptTemplate":"hello"}]}
                """;
        workflowService.saveDraft(wf.getId(), wf.getWorkspaceId(), draft2, 1L);
        WorkflowService.PublishOutcome second = workflowService.publish(wf.getId(), wf.getWorkspaceId(), 1L, "v2");
        assertEquals(2, second.revision().getRevision());

        WorkflowEntity reloaded = workflowMapper.selectById(wf.getId());
        assertEquals(second.revision().getId(), reloaded.getLatestRevisionId());
    }

    private static WorkflowEntity newWorkflow(String name) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setWorkspaceId(99L);
        wf.setName(name);
        wf.setEnabled(true);
        return wf;
    }

    /**
     * The default ACL port queries mate_agent / mate_channel which would
     * make these tests depend on agent + channel rows. Override with a
     * permissive ACL so the test can exercise the publish/lifecycle in
     * isolation.
     */
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
