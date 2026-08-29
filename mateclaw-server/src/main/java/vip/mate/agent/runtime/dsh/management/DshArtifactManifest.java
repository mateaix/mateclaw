package vip.mate.agent.runtime.dsh.management;

import java.time.Instant;

public record DshArtifactManifest(
        String name,
        String version,
        String platform,
        String downloadUrl,
        String sha256,
        long size,
        Instant releasedAt) {
}
