package vip.mate.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code db/migration/postgresql} tree applies cleanly on a real
 * PostgreSQL server, and that the TEXT→JSONB upgrade produced actual {@code
 * jsonb} columns (not text) while the intentionally-excluded columns stayed
 * {@code text}.
 *
 * <p>This is the automated form of the manual verification done when the tree
 * was forked: it guards against a future migration introducing dialect-specific
 * SQL that only H2/MySQL/Kingbase accept.
 */
@DisplayName("PostgreSQL migration tree applies on a real server")
class PostgresMigrationSmokeTest extends PostgresE2EBaseTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("all migrations applied successfully (no failed history rows)")
    void allMigrationsApplyCleanly() {
        // Spring Boot already ran Flyway on context startup; assert the outcome.
        assertThat(flyway.info().applied().length)
                .as("applied migration count")
                .isGreaterThanOrEqualTo(150);

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mateclaw.flyway_schema_history WHERE success = FALSE",
                Integer.class);
        assertThat(failed).as("failed migrations").isZero();
    }

    @Test
    @DisplayName("upgraded JSON columns are physically JSONB; excluded ones stay TEXT")
    void jsonColumnsHaveExpectedPhysicalTypes() {
        // A representative slice of the whitelist across several tables.
        List<String[]> jsonbExpected = List.of(
                new String[]{"mate_model_provider", "generate_kwargs"},
                new String[]{"mate_channel", "config_json"},
                new String[]{"mate_skill", "config_json"},
                new String[]{"mate_mcp_server", "headers_json"},
                new String[]{"mate_cron_job", "delivery_config"},
                new String[]{"mate_workflow_revision", "graph_json"},
                new String[]{"mate_wiki_pipeline_definition", "steps_json"},
                new String[]{"mate_tool_guard_config", "guarded_tools_json"}
        );
        for (String[] tc : jsonbExpected) {
            assertThat(columnType(tc[0], tc[1]))
                    .as("%s.%s should be jsonb", tc[0], tc[1])
                    .isEqualTo("jsonb");
        }

        // C-list: deliberately kept TEXT (arbitrary JSON-schema text /
        // frequently-truncated half-structured blobs).
        assertThat(columnType("mate_tool", "params_schema")).isEqualTo("text");
        assertThat(columnType("mate_message", "metadata")).isEqualTo("text");
    }

    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'mateclaw' AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }
}
