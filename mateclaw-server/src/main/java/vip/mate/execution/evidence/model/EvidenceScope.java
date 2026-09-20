package vip.mate.execution.evidence.model;

/** Persisted resource identity reserved for managed validation scopes. */
public record EvidenceScope(Long id, Long workspaceId, String resourceKey, String hostId,
        String rootId, long generation, int activeMutations, boolean tainted) { }
