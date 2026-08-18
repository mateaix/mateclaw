package vip.mate.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.repository.TeamRunMapper;
import vip.mate.team.service.TeamRunService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:team_run_migration_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class MigrationSmokeTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TeamRunMapper runMapper;

    @Autowired
    private TeamRunService runService;

    @Test
    @DisplayName("team run migration creates the run table with a BIGINT workspace")
    void teamRunTableExists() {
        assertEquals(1L, countTables("mate_team_run"));
        assertEquals("bigint", columnType("mate_team_run", "workspace_id").toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("team run migration adds the nullable task binding")
    void teamTaskRunBindingExists() {
        assertEquals(1L, countColumns("mate_team_task", "run_id"));
        assertEquals("YES", columnNullable("mate_team_task", "run_id"));
    }

    @Test
    @DisplayName("all database dialects contain the complete team run contract")
    void allDialectsContainVersion181() throws Exception {
        for (String dialect : List.of("h2", "mysql", "kingbase")) {
            Path migration = MIGRATIONS.resolve(dialect).resolve("V181__team_run_foundation.sql");
            assertTrue(Files.exists(migration), dialect + " migration must contain version 181");
            String sql = Files.readString(migration).toLowerCase(Locale.ROOT);
            assertTrue(sql.contains("mate_team_run"), dialect + " migration must create the run table");
            assertTrue(sql.matches("(?s).*workspace_id\\s+bigint.*"),
                    dialect + " migration must use BIGINT workspace ids");
            assertTrue(sql.matches("(?s).*run_id\\s+bigint\\s+null.*"),
                    dialect + " migration must add a nullable task run id");
            assertTrue(sql.matches("(?s).*unique\\s+(?:index(?:\\s+if\\s+not\\s+exists)?|key)"
                            + "\\s+uk_team_run_origin_message.*"),
                    dialect + " migration must enforce origin-message idempotency");
            assertTrue(sql.matches("(?s).*uk_team_run_origin_message.*?"
                            + "\\(workspace_id,\\s*lead_conversation_id,\\s*origin_message_id\\).*"),
                    dialect + " migration must scope origin-message idempotency by workspace");
            assertTrue(sql.contains("idx_team_task_run_number"),
                    dialect + " migration must index run task numbers");
            assertTrue(sql.contains("idx_team_task_run_status"),
                    dialect + " migration must index run task statuses");
        }
    }

    @Test
    @DisplayName("all database dialects index stable run history and backfill create time")
    void allDialectsContainStableHistoryIndexMigration() throws Exception {
        for (String dialect : List.of("h2", "mysql", "kingbase")) {
            Path migration = MIGRATIONS.resolve(dialect).resolve("V182__team_run_stable_history_indexes.sql");
            assertTrue(Files.exists(migration), dialect + " migration must contain version 182");
            String sql = Files.readString(migration).toLowerCase(Locale.ROOT);
            assertTrue(sql.contains("idx_team_run_team_history_stable"));
            assertTrue(sql.contains("team_id, create_time, id"));
            assertTrue(sql.contains("idx_team_run_conversation_history_stable"));
            assertTrue(sql.contains("lead_conversation_id, create_time, id"));
            assertTrue(sql.matches("(?s).*update\\s+mate_team_run\\s+set\\s+create_time.*"
                    + "where\\s+create_time\\s+is\\s+null.*"));
            assertTrue(sql.contains("not null"));
        }
        assertEquals("NO", columnNullable("mate_team_run", "create_time"));
    }

    @Test
    @DisplayName("all database dialects persist conversation kind with a primary default")
    void allDialectsContainConversationKindMigration() throws Exception {
        for (String dialect : List.of("h2", "mysql", "kingbase")) {
            Path migration = MIGRATIONS.resolve(dialect).resolve("V183__conversation_kind.sql");
            assertTrue(Files.exists(migration), dialect + " migration must contain version 183");
            String sql = Files.readString(migration).toLowerCase(Locale.ROOT);
            String normalizedSql = sql.replace("''", "'");
            assertTrue(sql.contains("conversation_kind"));
            assertTrue(normalizedSql.contains("default 'primary'"));
            assertTrue(sql.contains("not null"));
        }
        assertEquals("NO", columnNullable("mate_conversation", "conversation_kind"));
    }

    @Test
    @DisplayName("all database dialects index nullable team task conversation linkage")
    void allDialectsContainTeamTaskConversationIndex() throws Exception {
        for (String dialect : List.of("h2", "mysql", "kingbase")) {
            Path migration = MIGRATIONS.resolve(dialect).resolve("V184__team_task_conversation_index.sql");
            assertTrue(Files.exists(migration), dialect + " migration must contain version 184");
            String sql = Files.readString(migration).toLowerCase(Locale.ROOT);
            assertTrue(sql.contains("idx_team_task_conversation"));
            assertTrue(sql.matches("(?s).*idx_team_task_conversation.*conversation_id.*"));
        }
        assertEquals("YES", columnNullable("mate_team_task", "conversation_id"));
        assertEquals(1L, countIndexes("mate_team_task", "idx_team_task_conversation"));
    }

    @Test
    @DisplayName("H2 run history pages equal timestamps by id and ends without a cursor")
    void h2StableCursorPaginationAcrossEqualTimestamps() {
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 14, 12, 0);
        TeamRunEntity high = newRun(9_813_002L, "lead-page", null);
        high.setTeamId(321L);
        high.setCreateTime(sameTime);
        TeamRunEntity low = newRun(9_813_001L, "lead-page", null);
        low.setTeamId(321L);
        low.setCreateTime(sameTime);
        runMapper.insert(high);
        runMapper.insert(low);

        TeamRunService.RunPage first = runService.pageTeamRuns(321L, 41L, false, null, 1);
        TeamRunService.RunPage second = runService.pageTeamRuns(321L, 41L, false, first.nextCursor(), 1);

        assertEquals(high.getId(), first.items().getFirst().id());
        assertNotNull(first.nextCursor());
        assertEquals(low.getId(), second.items().getFirst().id());
        assertNull(second.nextCursor());
    }

    @Test
    @DisplayName("origin message identity is unique while manual runs allow null origins")
    void originMessageUniquenessAllowsManualRuns() {
        runMapper.insert(newRun(9_811_001L, "lead-unique", 7_001L));

        assertThrows(DuplicateKeyException.class,
                () -> runMapper.insert(newRun(9_811_002L, "lead-unique", 7_001L)));

        TeamRunEntity otherWorkspace = newRun(9_811_005L, "lead-unique", 7_001L);
        otherWorkspace.setWorkspaceId(42L);
        runMapper.insert(otherWorkspace);
        assertNotNull(runMapper.selectById(9_811_005L));

        runMapper.insert(newRun(9_811_003L, "lead-manual", null));
        runMapper.insert(newRun(9_811_004L, "lead-manual", null));
        assertNotNull(runMapper.selectById(9_811_003L));
        assertNotNull(runMapper.selectById(9_811_004L));
    }

    @Test
    @DisplayName("team run mapper round-trips fields and clears nullable final state")
    void teamRunMapperRoundTripAndClear() {
        TeamRunEntity run = newRun(9_812_001L, "lead-round-trip", 7_002L);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        run.setFinalSummary("delivered");
        run.setStopReason("finished");
        run.setMetadata("{\"outcome\":\"completed\"}");
        run.setStartedAt(now);
        run.setCompletedAt(now.plusMinutes(1));

        assertEquals(1, runMapper.insert(run));
        TeamRunEntity inserted = runMapper.selectById(run.getId());
        assertNotNull(inserted);
        assertEquals(41L, inserted.getWorkspaceId());
        assertEquals("delivered", inserted.getFinalSummary());
        assertNotNull(inserted.getCreateTime());

        inserted.setFinalSummary(null);
        inserted.setStopReason(null);
        inserted.setMetadata(null);
        inserted.setStartedAt(null);
        inserted.setCompletedAt(null);
        assertEquals(1, runMapper.updateById(inserted));

        TeamRunEntity cleared = runMapper.selectById(run.getId());
        assertNull(cleared.getFinalSummary());
        assertNull(cleared.getStopReason());
        assertNull(cleared.getMetadata());
        assertNull(cleared.getStartedAt());
        assertNull(cleared.getCompletedAt());
    }

    private TeamRunEntity newRun(long id, String leadConversationId, Long originMessageId) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(id);
        run.setTeamId(31L);
        run.setWorkspaceId(41L);
        run.setLeadAgentId(51L);
        run.setLeadConversationId(leadConversationId);
        run.setOriginMessageId(originMessageId);
        run.setTitle("Persistence contract");
        run.setObjective("Verify the team run persistence mapping");
        run.setStatus(TeamRunStatus.PLANNING);
        return run;
    }

    private Long countTables(String tableName) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Long.class,
                tableName);
    }

    private Long countColumns(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Long.class,
                tableName,
                columnName);
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private String columnNullable(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private Long countIndexes(String tableName, String indexName) {
        return jdbc.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.indexes "
                        + "WHERE table_name = ? AND index_name = ?",
                Long.class, tableName, indexName);
    }
}
