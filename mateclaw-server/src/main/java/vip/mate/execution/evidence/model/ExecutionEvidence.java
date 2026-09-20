package vip.mate.execution.evidence.model;

public record ExecutionEvidence(Long id, Long workspaceId, Long attemptId, String conversationId, EvidenceObservation observation) { }
