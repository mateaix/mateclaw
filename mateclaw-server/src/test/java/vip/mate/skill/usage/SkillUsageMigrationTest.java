package vip.mate.skill.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillUsageMigrationTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("skill usage table migration uses a version after existing V86 repair migration")
    void skillUsageMigrationUsesV87() {
        Path h2 = MIGRATIONS.resolve("h2/V87__skill_usage_stat.sql");
        Path mysql = MIGRATIONS.resolve("mysql/V87__skill_usage_stat.sql");

        assertTrue(Files.exists(h2), "H2 usage migration must be V87 so already-applied V86 databases run it");
        assertTrue(Files.exists(mysql), "MySQL usage migration must be V87 so already-applied V86 databases run it");
        assertFalse(Files.exists(MIGRATIONS.resolve("h2/V86__skill_usage_stat.sql")),
                "Do not reuse V86 for usage stats; some installations already applied a different V86");
        assertFalse(Files.exists(MIGRATIONS.resolve("mysql/V86__skill_usage_stat.sql")),
                "Do not reuse V86 for usage stats; some installations already applied a different V86");
    }

    @Test
    @DisplayName("skill usage migrations create the expected table")
    void skillUsageMigrationCreatesExpectedTable() throws Exception {
        String h2 = Files.readString(MIGRATIONS.resolve("h2/V87__skill_usage_stat.sql"));
        String mysql = Files.readString(MIGRATIONS.resolve("mysql/V87__skill_usage_stat.sql"));

        assertTrue(h2.contains("CREATE TABLE IF NOT EXISTS mate_skill_usage_stat"));
        assertTrue(mysql.contains("CREATE TABLE IF NOT EXISTS mate_skill_usage_stat"));
        assertTrue(h2.contains("uk_skill_usage_scope"));
        assertTrue(mysql.contains("uk_skill_usage_scope"));
    }
}
