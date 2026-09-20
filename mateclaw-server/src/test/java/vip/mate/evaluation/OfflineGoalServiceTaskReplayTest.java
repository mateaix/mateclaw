package vip.mate.evaluation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import vip.mate.memory.spi.MemoryManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.goal.service.GoalService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MateClawApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:goal_replay_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key", "spring.main.web-application-type=none", "mateclaw.goal.enabled=false",
        "mateclaw.plugin.enabled=false", "mateclaw.skill.workspace.auto-init=false",
        "mateclaw.skill.workspace.root=${java.io.tmpdir}/mateclaw-goal-replay-skills-${random.uuid}"
})
class OfflineGoalServiceTaskReplayTest {
    @MockBean MemoryManager memory;
    @Autowired GoalService goals;
    @Autowired JdbcTemplate jdbc;
    private byte[] fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent-evaluation/goal-service-boundaries-v1.json")) {
            assertNotNull(stream); return stream.readAllBytes();
        }
    }
    @Test void executeServiceTasksAndWriteReport() throws Exception {
        String input = System.getProperty("goal.service.eval.suite");
        var report = OfflineGoalServiceTaskReplay.run(input == null ? fixture() : Files.readAllBytes(Path.of(input)),
                goals, jdbc, System.getProperty("goal.service.eval.revision", "unrecorded"));
        Path output = Path.of(System.getProperty("goal.service.eval.report", "target/agent-evaluation/goal-service-baseline.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        OfflineGoalServiceTaskReplay.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertEquals(0, report.mismatchedCases(), () -> "Goal service mismatches: " + output.toAbsolutePath());
        assertEquals("H2", report.databaseProduct());
        assertTrue(org.mockito.Mockito.mockingDetails(memory).isMock());
        assertNotNull(report.latestMigration());
        assertEquals(0, report.onlineModelCalls());
    }
    @Test void invalidLaterTaskIsRejectedBeforeDatabaseWrites() throws Exception {
        ObjectNode suite = (ObjectNode) OfflineGoalServiceTaskReplay.JSON.readTree(fixture());
        ((ObjectNode) suite.withArray("tasks").get(1).get("expected")).remove("completionCode");
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM mate_agent_goal", Long.class);
        assertThrows(IllegalArgumentException.class, () -> OfflineGoalServiceTaskReplay.run(
                suite.toString().getBytes(StandardCharsets.UTF_8), goals, jdbc, "test"));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM mate_agent_goal", Long.class));
    }
    @Test void wrongExpectationReportsMismatchAndRunsAllCases() throws Exception {
        ObjectNode suite = (ObjectNode) OfflineGoalServiceTaskReplay.JSON.readTree(fixture());
        ((ObjectNode) suite.withArray("tasks").get(1).get("expected")).put("completionCode", 200);
        var report = OfflineGoalServiceTaskReplay.run(suite.toString().getBytes(StandardCharsets.UTF_8), goals, jdbc, "test");
        assertEquals(1, report.mismatchedCases());
        assertEquals(suite.withArray("tasks").size(), report.cases().size());
    }
}
