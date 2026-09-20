package vip.mate.skill.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillFileEncodingMigrationTest {
    @Test
    void migrationPreservesLegacyTextAndStoresBinaryContent() throws Exception {
        for (String dialect : List.of("h2", "mysql", "kingbase")) {
            String migration = Files.readString(Path.of("src/main/resources/db/migration", dialect,
                    "V202__skill_file_encoding.sql"));
            // The added column uses portable SQL. Exercise it on populated tables,
            // including dialect modes; native MySQL/Kingbase still require deployment verification.
            String mode = dialect.equals("kingbase") ? "PostgreSQL" : "MySQL";
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:encoding_" + dialect + ";MODE=" + mode);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE mate_skill_file (id BIGINT PRIMARY KEY, content TEXT)");
                statement.execute("INSERT INTO mate_skill_file VALUES (1, '制度说明')");
                statement.execute(migration);
                try (var result = statement.executeQuery("SELECT content, content_encoding FROM mate_skill_file WHERE id=1")) {
                    assertTrue(result.next());
                    assertEquals("制度说明", result.getString(1));
                    assertEquals("utf8", result.getString(2));
                }
                byte[] bytes = {80, 75, 0, (byte) 255};
                try (var insert = connection.prepareStatement("INSERT INTO mate_skill_file VALUES (2, ?, 'base64')")) {
                    insert.setString(1, Base64.getEncoder().encodeToString(bytes));
                    insert.executeUpdate();
                }
                try (var result = statement.executeQuery("SELECT content FROM mate_skill_file WHERE id=2")) {
                    assertTrue(result.next());
                    assertArrayEquals(bytes, Base64.getDecoder().decode(result.getString(1)));
                }
            }
        }
    }
}
