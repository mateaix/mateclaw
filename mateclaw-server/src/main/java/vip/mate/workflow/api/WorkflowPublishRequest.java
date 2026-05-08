package vip.mate.workflow.api;

/**
 * Request body for {@code POST /api/v1/workflows/{id}/publish}. {@code note}
 * is the human-friendly publish note recorded on the new revision row.
 */
public record WorkflowPublishRequest(String note) {
}
