package vip.mate.workspace.artifact.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.MateClawApplication;
import vip.mate.workspace.artifact.model.WorkspaceArtifactEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-backed persistence guard for the issue #514 artifact catalog.
 *
 * <p>Catches the class of regression that round-1 introduced: the
 * {@code download_url} column was declared NOT NULL with no default, but the
 * read-time-URL design leaves it null at insert. Under MyBatis-Plus's default
 * NOT_NULL insert strategy, the null field is omitted from the INSERT column
 * list → the DB rejects it → {@code register()} swallows the exception → the
 * feature silently does nothing. This test boots a real H2 + Flyway so the
 * column nullability (V171) and the insert path are exercised truthfully.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ws_artifact_persist_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "mateclaw.jwt.secret=ws-artifact-it-secret-0123456789"
})
@Transactional
class WorkspaceArtifactPersistenceTest {

    @Autowired
    private WorkspaceArtifactService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("an artifact with a null downloadUrl persists (regression guard for NOT NULL column)")
    void nullDownloadUrlPersists() {
        WorkspaceArtifactEntity entity = new WorkspaceArtifactEntity();
        entity.setWorkspaceId(1L);
        entity.setAgentId(1L);
        entity.setSource(WorkspaceArtifactService.SOURCE_AGENT);
        entity.setArtifactType("document");
        entity.setName("test-report.docx");
        entity.setMime("application/pdf");
        entity.setSizeBytes(1024L);
        entity.setStorageKind(WorkspaceArtifactService.STORAGE_GENERATED_CACHE);
        entity.setStorageRef("uuid-test-1");
        entity.setDownloadUrl(null); // the regression trigger

        Long id = service.register(entity);

        assertThat(id).as("register() must return a non-null id — a null means the insert " +
                "was silently swallowed (check the download_url column is nullable)").isNotNull();

        // Verify the row is actually in the DB (not just accepted in memory).
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_workspace_artifact WHERE id = ?",
                Integer.class, id);
        assertThat(count).isEqualTo(1);
    }
}
