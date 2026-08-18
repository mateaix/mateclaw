package vip.mate.skill.routine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly sweep that mines recurring requests and promotes the qualified ones
 * into skills.
 *
 * <p>Scheduled an hour after the lifecycle curator so the two never contend
 * for the same skill rows: the curator ages skills out, this job writes new
 * ones, and interleaving them within one window would let a freshly promoted
 * routine meet the archival sweep before it has ever been used.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillRoutineJob {

    private final SkillRoutineMiner miner;
    private final SkillRoutinePromoter promoter;
    private final SkillRoutineProperties properties;

    @Scheduled(cron = "${mateclaw.skill.routine.cron:0 0 3 * * *}")
    @SchedulerLock(name = "skill-routine", lockAtMostFor = "PT20M", lockAtLeastFor = "PT30S")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int mined = miner.mineAll();
            int promoted = promoter.promoteQualified();
            if (mined > 0 || promoted > 0) {
                log.info("[SkillRoutine] Sweep complete — {} candidate(s) refreshed, {} promoted",
                        mined, promoted);
            }
        } catch (Exception e) {
            log.warn("[SkillRoutine] Sweep failed: {}", e.getMessage(), e);
        }
    }
}
