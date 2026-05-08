package vip.mate.workflow.api;

/**
 * Request body for {@code PUT /api/v1/workflows/{id}/draft}. The wire format
 * matches {@code mate_workflow.draft_json} verbatim — the controller does
 * not reshape this before persisting, so the editor / API caller owns the
 * exact JSON the publish-time compiler will see.
 */
public record WorkflowDraftRequest(String draftJson) {
}
