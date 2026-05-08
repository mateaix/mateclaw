package vip.mate.trigger.dispatch;

import vip.mate.workflow.compiler.ir.WorkflowGraph;

/**
 * SPI for "given a workflow id, load the published WorkflowGraph the trigger
 * should fire". Production binding reads {@code mate_workflow.latest_revision_id}
 * and parses {@code mate_workflow_revision.graph_json}; tests stub this so a
 * fire path can be exercised without standing up the publish pipeline.
 */
public interface WorkflowGraphLoader {

    /**
     * Result of a graph load. {@code graph == null} indicates the workflow
     * has no published revision yet (or was deleted) and the fire should
     * be skipped instead of erroring.
     */
    record Loaded(WorkflowGraph graph, Long revisionId) {
        public static Loaded missing() { return new Loaded(null, null); }
    }

    Loaded load(long workflowId);
}
