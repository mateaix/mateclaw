package vip.mate.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Server-issued execution linkage. This record is never a tool argument or an HTTP request body. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionAttribution(Long goalId, String goalAttemptId, Long cronRunId,
                                   String approvalId, String ownerFence) {
    public ExecutionAttribution withApproval(String pendingId) {
        return new ExecutionAttribution(goalId, goalAttemptId, cronRunId, pendingId, ownerFence);
    }
}
