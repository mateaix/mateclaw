package vip.mate.memory.scheduler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.StructuredMemoryConsolidationService;
import vip.mate.memory.service.StructuredMemoryConsolidationService.ConsolidationStats;

import java.time.LocalDateTime;

/**
 * Scheduled maintenance for always-on structured memory.
 * <p>
 * Runs the consolidation pass that merges duplicate / stale user & feedback
 * entries so the always-on block shrinks at the storage level. Kept separate from
 * {@link DreamingScheduler} so it has its own cron and enable flag — disabling
 * nightly dreaming must not silently disable structured consolidation, and vice
 * versa.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructuredMemoryMaintenanceScheduler {

    private final AgentService agentService;
    private final StructuredMemoryConsolidationService consolidationService;
    private final MemoryProperties properties;

    /** Last run time, for the status API. */
    @Getter
    private volatile LocalDateTime lastRunTime;

    @Scheduled(cron = "${mate.memory.structured-consolidation-cron:0 30 3 * * ?}")
    public void runConsolidation() {
        if (!properties.isStructuredConsolidationEnabled()) {
            log.debug("[StructuredConsolidation] Disabled, skipping");
            return;
        }

        log.info("[StructuredConsolidation] Starting maintenance cycle");
        ConsolidationStats total = new ConsolidationStats();
        int agents = 0;

        for (AgentEntity agent : agentService.listAgents()) {
            if (!Boolean.TRUE.equals(agent.getEnabled())) {
                continue;
            }
            agents++;
            try {
                ConsolidationStats agentStats = consolidationService.consolidateAgent(agent.getId());
                total.add(agentStats);
            } catch (Exception e) {
                log.warn("[StructuredConsolidation] Failed for agent={} ({}): {}",
                        agent.getId(), agent.getName(), e.getMessage());
            }
        }

        lastRunTime = LocalDateTime.now();
        log.info("[StructuredConsolidation] Cycle done: agents={}, buckets={}, updated={}, "
                        + "skipped(small)={}, skipped(cap)={}, failed={}, entries {}->{}",
                agents, total.ownersConsolidated, total.updated,
                total.skippedSmall, total.skippedOverCap, total.failed,
                total.entriesBefore, total.entriesAfter);
    }
}
