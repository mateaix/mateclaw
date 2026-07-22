package vip.mate.skill.runtime;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Three-way SKILL.md reconciliation between the canonical DB column and
 * the convention-workspace file cache.
 */
class SkillContentReconcilerTest {

    @TempDir
    Path workspaceDir;

    private SkillMapper skillMapper;
    private SkillContentReconciler reconciler;

    @BeforeAll
    static void initTableInfo() {
        // Lambda wrappers resolve column names from MyBatis-Plus's static
        // TableInfo cache; in a Spring context this happens during mapper
        // scan, in a plain unit test we trigger it manually.
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                SkillEntity.class);
    }

    @BeforeEach
    void setUp() {
        skillMapper = mock(SkillMapper.class);
        reconciler = new SkillContentReconciler(skillMapper);
    }

    private SkillEntity entity(String dbContent) {
        SkillEntity e = new SkillEntity();
        e.setId(1L);
        e.setName("demo-skill");
        e.setSkillContent(dbContent);
        return e;
    }

    private void writeFile(String content) throws IOException {
        Files.writeString(workspaceDir.resolve("SKILL.md"), content);
    }

    private void writeMarker(String ofContent) throws IOException {
        Files.writeString(workspaceDir.resolve(SkillContentReconciler.SYNC_MARKER),
                SkillContentReconciler.sha256(ofContent));
    }

    private String fileContent() throws IOException {
        return Files.readString(workspaceDir.resolve("SKILL.md"));
    }

    private String markerContent() throws IOException {
        return Files.readString(workspaceDir.resolve(SkillContentReconciler.SYNC_MARKER)).strip();
    }

    // ==================== in sync ====================

    @Test
    void inSyncHealsMissingMarker() throws IOException {
        writeFile("same");
        var outcome = reconciler.reconcile(entity("same"), workspaceDir);

        assertThat(outcome.action()).isEqualTo(SkillContentReconciler.Action.IN_SYNC);
        assertThat(outcome.content()).isEqualTo("same");
        assertThat(markerContent()).isEqualTo(SkillContentReconciler.sha256("same"));
        verify(skillMapper, never()).update(isNull(), any(Wrapper.class));
    }

    // ==================== blank-side guards ====================

    @Test
    void missingFileMaterializesFromDb() throws IOException {
        var outcome = reconciler.reconcile(entity("db content"), workspaceDir);

        assertThat(outcome.action()).isEqualTo(SkillContentReconciler.Action.MATERIALIZED_TO_FS);
        assertThat(fileContent()).isEqualTo("db content");
        assertThat(markerContent()).isEqualTo(SkillContentReconciler.sha256("db content"));
        verify(skillMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void blankFileNeverIngestedOverDbContent() throws IOException {
        // A truncated/emptied file must not wipe the canonical content,
        // even when the marker says the DB side is unchanged.
        writeFile("");
        writeMarker("db content");

        var outcome = reconciler.reconcile(entity("db content"), workspaceDir);

        assertThat(outcome.action()).isEqualTo(SkillContentReconciler.Action.MATERIALIZED_TO_FS);
        assertThat(fileContent()).isEqualTo("db content");
        verify(skillMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void blankDbBackfillsFromFile() throws IOException {
        writeFile("file content");
        SkillEntity e = entity(null);

        var outcome = reconciler.reconcile(e, workspaceDir);

        assertThat(outcome.action()).isEqualTo(SkillContentReconciler.Action.BACKFILLED_TO_DB);
        assertThat(outcome.content()).isEqualTo("file content");
        assertThat(e.getSkillContent()).isEqualTo("file content");
        assertThat(markerContent()).isEqualTo(SkillContentReconciler.sha256("file content"));
        verify(skillMapper).update(isNull(), any(Wrapper.class));
    }

    // ==================== single-side changes ====================

    @Test
    void fileEditSinceLastSyncIsIngestedToDb() throws IOException {
        // Marker == DB hash → the DB did not move; the file edit wins.
        writeFile("edited via shell");
        writeMarker("db content");
        SkillEntity e = entity("db content");

        var outcome = reconciler.reconcile(e, workspaceDir);

        assertThat(outcome.action()).isEqualTo(SkillContentReconciler.Action.INGESTED_TO_DB);
        assertThat(outcome.content()).isEqualTo("edited via shell");
        assertThat(e.getSkillContent()).isEqualTo("edited via shell");
        assertThat(markerContent()).isEqualTo(SkillContentReconciler.sha256("edited via shell"));
        verify(skillMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void dbEditSinceLastSyncIsMaterializedToFile() throws IOException {
        // Marker == file hash → the file did not move; the DB edit wins.
        writeFile("old content");
        writeMarker("old content");

        var outcome = reconciler.reconcile(entity("new db content"), workspaceDir);

        assertThat(outcome.action()).isEqualTo(SkillContentReconciler.Action.MATERIALIZED_TO_FS);
        assertThat(fileContent()).isEqualTo("new db content");
        assertThat(markerContent()).isEqualTo(SkillContentReconciler.sha256("new db content"));
        verify(skillMapper, never()).update(isNull(), any(Wrapper.class));
    }

    // ==================== legacy + conflict ====================

    @Test
    void legacyDivergenceWithoutMarkerLetsFileWinOnce() throws IOException {
        // Pre-marker installs resolved runtime content from the directory,
        // so on first reconcile the file reflects what was in effect.
        writeFile("file version");
        SkillEntity e = entity("db version");

        var outcome = reconciler.reconcile(e, workspaceDir);

        assertThat(outcome.action()).isEqualTo(SkillContentReconciler.Action.INGESTED_TO_DB);
        assertThat(e.getSkillContent()).isEqualTo("file version");
    }

    @Test
    void twoSidedConflictDbWinsAndFileIsBackedUp() throws IOException {
        writeFile("file edit");
        writeMarker("common ancestor");

        var outcome = reconciler.reconcile(entity("db edit"), workspaceDir);

        assertThat(outcome.action()).isEqualTo(SkillContentReconciler.Action.CONFLICT_DB_WON);
        assertThat(fileContent()).isEqualTo("db edit");
        assertThat(Files.readString(workspaceDir.resolve(SkillContentReconciler.CONFLICT_BACKUP)))
                .isEqualTo("file edit");
        assertThat(markerContent()).isEqualTo(SkillContentReconciler.sha256("db edit"));
        verify(skillMapper, never()).update(isNull(), any(Wrapper.class));
    }

    // ==================== ingest write shape ====================

    @Test
    void ingestWritesViaColumnWhitelistedWrapper() throws IOException {
        writeFile("edited via shell");
        writeMarker("db content");

        reconciler.reconcile(entity("db content"), workspaceDir);

        // The write must go through an update wrapper (column whitelist),
        // not updateById — SkillEntity carries FieldStrategy.ALWAYS columns
        // that a partial updateById would null out.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<SkillEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(skillMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("skill_content");
    }

    // ==================== explicit-dir mirror ====================

    @Test
    void mirrorToDbWritesOnlyWhenDifferent() {
        SkillEntity e = entity("same");
        reconciler.mirrorToDb(e, "same");
        verify(skillMapper, never()).update(isNull(), any(Wrapper.class));

        reconciler.mirrorToDb(e, "changed");
        verify(skillMapper).update(isNull(), any(Wrapper.class));
        assertThat(e.getSkillContent()).isEqualTo("changed");
    }

    @Test
    void mirrorToDbIgnoresBlankFileContent() {
        SkillEntity e = entity("db content");
        reconciler.mirrorToDb(e, "");
        reconciler.mirrorToDb(e, null);
        verify(skillMapper, never()).update(isNull(), any(Wrapper.class));
        assertThat(e.getSkillContent()).isEqualTo("db content");
    }
}
