package vip.mate.llm.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.failover.AvailableProviderPool.RemovalReason;
import vip.mate.llm.failover.ProbeResult;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.failover.ProviderHealthTracker.ProviderHealthSnapshot;
import vip.mate.llm.failover.ProviderInitProbe;
import vip.mate.llm.model.ProviderInfoDTO;
import vip.mate.llm.service.ModelProviderService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * RFC-009 Phase 4 — read-only diagnostic endpoint for the provider pool.
 *
 * <p>Surfaces the union of three pieces of state:</p>
 * <ul>
 *   <li>{@link AvailableProviderPool} membership + last removal reason
 *       (HARD failures, init probe verdicts, manual eviction).</li>
 *   <li>{@link ProviderHealthTracker} cooldown status (SOFT failures).</li>
 *   <li>{@link ModelProviderService} configuration metadata so the UI can
 *       skip rendering rows for providers the user has never set up.</li>
 * </ul>
 *
 * <p>Manual reprobe + auto-reprobe-on-config-change land in PR-1e.</p>
 */
@Tag(name = "Provider 可用池")
@RestController
@RequestMapping("/api/v1/llm/provider-pool")
@RequiredArgsConstructor
public class ProviderPoolController {

    private final AvailableProviderPool providerPool;
    private final ProviderHealthTracker healthTracker;
    private final ModelProviderService providerService;
    private final ProviderInitProbe initProbe;

    @Operation(summary = "查询所有 provider 的池状态 + 冷却信息")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<ProviderPoolEntryDTO>> snapshot() {
        Map<String, RemovalReason> poolView = providerPool.snapshot();
        Map<String, ProviderHealthSnapshot> healthView = healthTracker.snapshot();
        List<ProviderInfoDTO> providers = providerService.listProviders();

        List<ProviderPoolEntryDTO> rows = new ArrayList<>(providers.size());
        for (ProviderInfoDTO p : providers) {
            String id = p.getId();
            // Pool membership: snapshot() returns id->null for in-pool, id->reason for removed.
            // An id missing from the snapshot has never been touched (treat as not-in-pool until first probe).
            boolean tracked = poolView.containsKey(id);
            RemovalReason reason = poolView.get(id);
            boolean inPool = tracked && reason == null;
            ProviderHealthSnapshot health = healthView.get(id);

            rows.add(new ProviderPoolEntryDTO(
                    id,
                    p.getName(),
                    inPool,
                    reason == null ? null : reason.source().name(),
                    reason == null ? null : reason.message(),
                    reason == null ? null : reason.removedAtMs(),
                    health != null && health.cooldownRemainingMs() > 0,
                    health == null ? 0L : health.cooldownRemainingMs(),
                    health == null ? 0L : health.consecutiveFailures()
            ));
        }
        return R.ok(rows);
    }

    @Operation(summary = "手动重新探测某个 provider，立即更新池状态")
    @PostMapping("/{providerId}/reprobe")
    @RequireWorkspaceRole("admin")
    public R<ReprobeResultDTO> reprobe(@PathVariable String providerId) {
        ProbeResult result = initProbe.probeOne(providerId);
        return R.ok(new ReprobeResultDTO(
                providerId,
                result.success(),
                result.latencyMs(),
                result.errorMessage(),
                providerPool.contains(providerId)
        ));
    }

    /** Result of a manual reprobe. {@code inPool} reflects pool state after the probe ran. */
    public record ReprobeResultDTO(
            String providerId,
            boolean success,
            long latencyMs,
            String errorMessage,
            boolean inPool
    ) {}

    /**
     * Per-row payload for the {@code /provider-pool} response. Flat record
     * because the UI renders each provider as one card and we want a stable
     * shape that's trivial to bind in TypeScript.
     */
    public record ProviderPoolEntryDTO(
            String providerId,
            String providerName,
            boolean inPool,
            String removalSource,
            String removalMessage,
            Long removedAtMs,
            boolean inCooldown,
            long cooldownRemainingMs,
            long consecutiveFailures
    ) {}
}
