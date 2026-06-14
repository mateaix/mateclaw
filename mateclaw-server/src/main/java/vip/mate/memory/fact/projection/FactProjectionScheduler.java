package vip.mate.memory.fact.projection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.memory.MemoryProperties;

import java.util.List;

/**
 * Scheduled full rebuild of the fact projection for all active agents.
 * Cron expression configured via mate.memory.fact.projection-rebuild-cron.
 * Only runs when projection-enabled=true.
 * <p>
 * {@code @Async} keeps the scheduler-thread pool free — the actual DB
 * work runs on the virtual-thread async executor.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactProjectionScheduler {

    private final AgentService agentService;
    private final FactProjectionBuilder projectionBuilder;
    private final MemoryProperties properties;

    @Async
    @Scheduled(cron = "${mate.memory.fact.projection-rebuild-cron:0 */30 * * * ?}")
    public void rebuildAll() {
        if (!properties.getFact().isProjectionEnabled()) {
            return;
        }

        log.info("[FactProjection] Starting scheduled full rebuild");
        List<AgentEntity> agents = agentService.listAgents();
        int total = 0;
        int failed = 0;

        for (AgentEntity agent : agents) {
            if (!Boolean.TRUE.equals(agent.getEnabled())) continue;
            try {
                int count = projectionBuilder.rebuildAll(agent.getId());
                total += count;
            } catch (Exception e) {
                failed++;
                log.warn("[FactProjection] Rebuild failed for agent={}: {}", agent.getId(), e.getMessage());
            }
        }

        log.info("[FactProjection] Scheduled rebuild completed: {} facts across all agents, {} failures", total, failed);
    }
}
