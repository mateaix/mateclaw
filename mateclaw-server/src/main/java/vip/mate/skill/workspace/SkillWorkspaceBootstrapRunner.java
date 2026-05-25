package vip.mate.skill.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Skill workspace bootstrap.
 * <ol>
 *   <li>Ensure the workspace root exists.</li>
 *   <li>Sync classpath-bundled skills into the workspace
 *       (first install creates them; later starts upgrade only when the
 *       bundled SKILL.md frontmatter version is strictly newer).</li>
 *   <li>Materialize {@code mate_skill_file} rows down to each node's
 *       local cache so multi-instance deployments share the same
 *       scripts/references regardless of which node accepted the upload.
 *       Also backfills any pre-V112 on-disk-only skill files into the
 *       canonical store.</li>
 * </ol>
 *
 * <p>Order(210) — runs after {@code DatabaseBootstrapRunner}(200) so the
 * skill rows the syncer needs to read are already loaded.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(210)
@RequiredArgsConstructor
public class SkillWorkspaceBootstrapRunner implements ApplicationRunner {

    private final SkillWorkspaceManager workspaceManager;
    private final BundledSkillSyncer bundledSkillSyncer;
    private final SkillFileSyncer skillFileSyncer;

    @Override
    public void run(ApplicationArguments args) {
        var root = workspaceManager.getWorkspaceRoot();
        log.info("Skill workspace root ready: {}", root);

        List<String> synced = bundledSkillSyncer.sync();
        if (!synced.isEmpty()) {
            log.info("Synced {} bundled skill(s) to workspace: {}", synced.size(), synced);
        }

        // Pull canonical bundle files from DB → local cache (and one-time
        // backfill of pre-V112 disk-only skills back into the DB).
        var report = skillFileSyncer.syncAll();
        log.info("Skill file sync: skills={}, materialized={}, current={}, " +
                        "diskBackfilled(skills={}, files={})",
                report.skillsConsidered(), report.filesMaterialized(),
                report.filesAlreadyCurrent(),
                report.skillsBackfilled(), report.filesBackfilledFromDisk());
    }
}
