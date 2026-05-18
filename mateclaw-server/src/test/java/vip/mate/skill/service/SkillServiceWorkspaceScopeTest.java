package vip.mate.skill.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.skill.model.SkillEntity;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #135 — verifies that skill read paths are scoped to one workspace:
 * builtin skills are global (visible everywhere), every other skill is only
 * visible inside its owning workspace. Without this, a workspace-B user saw
 * workspace-A's skills in the marketplace but hit a 403 when binding them.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:skill_ws_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class SkillServiceWorkspaceScopeTest {

    private static final AtomicLong SKILL_ID_SEQ = new AtomicLong(8_135_000L);

    @Autowired
    private SkillService skillService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Insert a skill row directly so {@code createSkill}'s workspace/FS side effects stay out of scope. */
    private long seedSkill(String name, long workspaceId, boolean builtin) {
        long id = SKILL_ID_SEQ.getAndIncrement();
        jdbcTemplate.update(
                "MERGE INTO mate_skill (id, name, skill_type, version, enabled, builtin, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, ?, '1.0.0', TRUE, ?, ?, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id, name, builtin ? "builtin" : "dynamic", builtin, workspaceId);
        return id;
    }

    @Test
    @DisplayName("listSkills(workspaceId) returns builtin + own-workspace skills, hides other workspaces")
    void listSkillsIsWorkspaceScoped() {
        String ws1Name = "ws1-skill-" + SKILL_ID_SEQ.get();
        String ws2Name = "ws2-skill-" + SKILL_ID_SEQ.get();
        String builtinName = "builtin-skill-" + SKILL_ID_SEQ.get();
        seedSkill(ws1Name, 1L, false);
        seedSkill(ws2Name, 2L, false);
        seedSkill(builtinName, 1L, true);

        Set<String> ws2Names = skillService.listSkills(2L).stream()
                .map(SkillEntity::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(ws2Names.contains(ws2Name), "workspace 2 must see its own skill");
        assertTrue(ws2Names.contains(builtinName), "workspace 2 must see the global builtin skill");
        assertFalse(ws2Names.contains(ws1Name), "workspace 2 must not see workspace 1's skill");
    }

    @Test
    @DisplayName("pageSkills(workspaceId) excludes other workspaces' skills from the marketplace listing")
    void pageSkillsIsWorkspaceScoped() {
        String ws1Name = "page-ws1-" + SKILL_ID_SEQ.get();
        String ws2Name = "page-ws2-" + SKILL_ID_SEQ.get();
        seedSkill(ws1Name, 1L, false);
        seedSkill(ws2Name, 2L, false);

        IPage<SkillEntity> ws2Page = skillService.pageSkills(
                1, 200, null, null, null, null, null, null, null, Set.of(), 2L);
        Set<String> names = ws2Page.getRecords().stream()
                .map(SkillEntity::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(names.contains(ws2Name), "marketplace page for workspace 2 must list its own skill");
        assertFalse(names.contains(ws1Name), "marketplace page for workspace 2 must not list workspace 1's skill");
    }

    @Test
    @DisplayName("countByType(workspaceId) counts builtin globally but other skills per workspace")
    void countByTypeIsWorkspaceScoped() {
        long before = skillService.countByType(2L).getOrDefault("dynamic", 0L);
        seedSkill("count-ws1-" + SKILL_ID_SEQ.get(), 1L, false);
        seedSkill("count-ws2-" + SKILL_ID_SEQ.get(), 2L, false);

        long after = skillService.countByType(2L).getOrDefault("dynamic", 0L);
        assertTrue(after == before + 1,
                "workspace 2's dynamic count should rise by exactly one (its own skill), not two");
    }
}
