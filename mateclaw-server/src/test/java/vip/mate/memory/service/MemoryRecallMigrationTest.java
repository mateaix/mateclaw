package vip.mate.memory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryRecallMigrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"mysql", "kingbase"})
    void dialectMigrationAppliesInCompatibleMode(String dialect) {
        JdbcDataSource database = new JdbcDataSource();
        String mode = "kingbase".equals(dialect) ? "PostgreSQL" : "MySQL";
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        createTable(jdbc);
        insertDuplicates(jdbc);

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/" + dialect + "/V192__memory_recall_unique_identity.sql")).execute(database);

        assertMerged(jdbc);
    }

    @Test
    void migrationMergesDuplicateCountersAndEnforcesOwnerAwareIdentity() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            createTable(jdbc);
            insertDuplicates(jdbc);

            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/migration/h2/V192__memory_recall_unique_identity.sql")).execute(database);

            assertMerged(jdbc);
        } finally {
            database.shutdown();
        }
    }

    private static void createTable(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE mate_memory_recall (
                  id BIGINT PRIMARY KEY,
                  agent_id BIGINT NOT NULL,
                  filename VARCHAR(256) NOT NULL,
                  recall_count INT,
                  daily_count INT,
                  last_recalled_at TIMESTAMP,
                  owner_key VARCHAR(128),
                  scope VARCHAR(16) NOT NULL,
                  deleted INT NOT NULL
                )
                """);
    }

    private static void insertDuplicates(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO mate_memory_recall VALUES (1,7,'MEMORY.md',2,1,TIMESTAMP '2026-01-01 00:00:00',NULL,'TEAM',0)");
        jdbc.update("INSERT INTO mate_memory_recall VALUES (2,7,'MEMORY.md',3,2,TIMESTAMP '2026-02-01 00:00:00','','TEAM',0)");
        jdbc.update("INSERT INTO mate_memory_recall VALUES (3,7,'old.md',9,9,NULL,'','TEAM',1)");
    }

    private static void assertMerged(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mate_memory_recall", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT recall_count FROM mate_memory_recall", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT daily_count FROM mate_memory_recall", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT owner_key FROM mate_memory_recall", String.class)).isEmpty();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO mate_memory_recall VALUES (4,7,'MEMORY.md',1,1,NULL,'','TEAM',0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
