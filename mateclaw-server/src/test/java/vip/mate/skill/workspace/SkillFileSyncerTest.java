package vip.mate.skill.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillFileEntity;
import vip.mate.skill.repository.SkillFileMapper;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.service.SkillService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SkillFileSyncer} covering the multi-instance scenarios:
 *
 * <ul>
 *   <li>DB has rows, FS is missing them (new node receives shared DB) →
 *       files materialized to disk.</li>
 *   <li>FS already current with DB → nothing rewritten.</li>
 *   <li>DB empty, FS has files (pre-V112 install) → files backfilled into
 *       canonical store.</li>
 * </ul>
 */
class SkillFileSyncerTest {

    @TempDir
    Path tmp;

    private SkillService skillService;
    private SkillFileMapper mapper;
    private SkillFileService fileService;
    private SkillWorkspaceManager workspaceManager;
    private SkillFileSyncer syncer;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        mapper = mock(SkillFileMapper.class);
        fileService = new SkillFileService(mapper);
        SkillWorkspaceProperties props = new SkillWorkspaceProperties();
        props.setRoot(tmp.toString());
        workspaceManager = new SkillWorkspaceManager(props, mock(ApplicationEventPublisher.class));
        syncer = new SkillFileSyncer(skillService, fileService, workspaceManager);
    }

    @Test
    @DisplayName("DB rows materialize to a missing local cache")
    void materializesDbRowsOntoDisk() throws IOException {
        SkillEntity skill = newSkill(10L, "demo");
        when(skillService.listSkills()).thenReturn(List.of(skill));

        // DB has scripts/run.py and references/notes.md but local FS has neither.
        when(mapper.selectList(any())).thenReturn(List.of(
                newRow(1L, 10L, "scripts/run.py", "print('a')\n"),
                newRow(2L, 10L, "references/notes.md", "hello")
        ));

        var report = syncer.syncAll();

        Path workspace = tmp.resolve("demo");
        assertEquals("print('a')\n", Files.readString(workspace.resolve("scripts/run.py")));
        assertEquals("hello", Files.readString(workspace.resolve("references/notes.md")));
        assertEquals(2, report.filesMaterialized());
        assertEquals(0, report.filesAlreadyCurrent());
        assertEquals(0, report.filesBackfilledFromDisk());
    }

    @Test
    @DisplayName("FS already in sync with DB → no rewrites")
    void skipsAlreadyCurrentFiles() throws IOException {
        SkillEntity skill = newSkill(10L, "demo");
        when(skillService.listSkills()).thenReturn(List.of(skill));

        Path workspace = tmp.resolve("demo");
        Files.createDirectories(workspace.resolve("scripts"));
        Files.writeString(workspace.resolve("scripts/run.py"), "stable");

        when(mapper.selectList(any())).thenReturn(List.of(
                newRow(1L, 10L, "scripts/run.py", "stable")
        ));

        var report = syncer.syncAll();

        assertEquals(0, report.filesMaterialized());
        assertEquals(1, report.filesAlreadyCurrent());
    }

    @Test
    @DisplayName("FS has files, DB is empty (pre-V112): backfill into DB")
    void backfillsFromDiskWhenDbEmpty() throws IOException {
        SkillEntity skill = newSkill(10L, "demo");
        when(skillService.listSkills()).thenReturn(List.of(skill));

        Path workspace = tmp.resolve("demo");
        Files.createDirectories(workspace.resolve("scripts"));
        Files.createDirectories(workspace.resolve("references"));
        Files.writeString(workspace.resolve("scripts/run.py"), "legacy");
        Files.writeString(workspace.resolve("references/cfg.md"), "old-ref");

        // selectList call sequence inside syncOne with backfill:
        //   1. syncOne reads dbFiles → empty (triggers backfill)
        //   2. applyBundleFiles inside backfill reads existing rows → empty (none inserted yet)
        //   3. syncOne re-reads dbFiles after backfill → freshly inserted rows
        List<SkillFileEntity> after = new ArrayList<>(List.of(
                newRow(1L, 10L, "scripts/run.py", "legacy"),
                newRow(2L, 10L, "references/cfg.md", "old-ref")
        ));
        when(mapper.selectList(any())).thenReturn(List.of(), List.of(), after);

        var report = syncer.syncAll();

        assertEquals(2, report.filesBackfilledFromDisk(),
                "Both legacy files should be ingested into the canonical store");
        assertEquals(1, report.skillsBackfilled());
        // After backfill, the reread "current" rows match what's already on disk.
        assertEquals(2, report.filesAlreadyCurrent());
        verify(mapper, times(2)).insert(any(SkillFileEntity.class));
    }

    private static SkillEntity newSkill(Long id, String name) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName(name);
        return s;
    }

    private static SkillFileEntity newRow(Long id, Long skillId, String path, String content) {
        SkillFileEntity e = new SkillFileEntity();
        e.setId(id);
        e.setSkillId(skillId);
        e.setFilePath(path);
        e.setContent(content);
        e.setContentSize(content.getBytes(StandardCharsets.UTF_8).length);
        e.setSha256(SkillFileService.sha256Hex(content));
        return e;
    }
}
