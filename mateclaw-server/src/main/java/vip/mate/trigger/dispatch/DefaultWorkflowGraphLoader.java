package vip.mate.trigger.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.model.WorkflowRevisionEntity;
import vip.mate.workflow.repository.WorkflowMapper;
import vip.mate.workflow.repository.WorkflowRevisionMapper;

/**
 * Production binding for {@link WorkflowGraphLoader}. Looks up
 * {@code mate_workflow.latest_revision_id} and parses the corresponding
 * {@code mate_workflow_revision.graph_json}. Returns
 * {@link Loaded#missing()} when either lookup fails or the workflow is
 * disabled — triggers should not fire workflows that the user already
 * paused or removed.
 */
@Slf4j
@Component
public class DefaultWorkflowGraphLoader implements WorkflowGraphLoader {

    private final WorkflowMapper workflowMapper;
    private final WorkflowRevisionMapper revisionMapper;
    private final WorkflowParser parser;

    public DefaultWorkflowGraphLoader(WorkflowMapper workflowMapper,
                                      WorkflowRevisionMapper revisionMapper,
                                      WorkflowParser parser) {
        this.workflowMapper = workflowMapper;
        this.revisionMapper = revisionMapper;
        this.parser = parser;
    }

    @Override
    public Loaded load(long workflowId) {
        WorkflowEntity workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || Boolean.FALSE.equals(workflow.getEnabled())
                || workflow.getLatestRevisionId() == null) {
            return Loaded.missing();
        }
        WorkflowRevisionEntity revision = revisionMapper.selectById(workflow.getLatestRevisionId());
        if (revision == null) return Loaded.missing();
        try {
            return new Loaded(parser.parse(revision.getGraphJson()), revision.getId());
        } catch (Exception e) {
            log.warn("Trigger graph load: revision {} failed to parse: {}",
                    revision.getId(), e.getMessage());
            return Loaded.missing();
        }
    }
}
