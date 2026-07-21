package vip.mate.skill.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.bundle.SkillBundleMaterializer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BundledSkillSyncer} covering:
 *
 * <ul>
 *   <li>First install mirrors bundle scripts/references into the DB.</li>
 *   <li>Missing {@code scripts/} on disk triggers a self-heal copy even
 *       when the SKILL.md version is unchanged, archiving the old
 *       workspace first.</li>
 *   <li>A current workspace with all buckets present is a strict no-op —
 *       no disk copy, no DB writes.</li>
 * </ul>
 */
class BundledSkillSyncerTest {

    @TempDir
    Path tmp;

    private SkillWorkspaceProperties properties;
    private SkillWorkspaceManager workspaceManager;
    private SkillBundleMaterializer bundleMaterializer;
    private SkillService skillService;
    private SkillFileService skillFileService;
    private BundledSkillSyncer syncer;

    @BeforeEach
    void setUp() {
        properties = new SkillWorkspaceProperties();
        properties.setRoot(tmp.toString());
        properties.setBundledSkillsPath("skills");

        workspaceManager = new SkillWorkspaceManager(properties, mock(ApplicationEventPublisher.class));
        bundleMaterializer = new SkillBundleMaterializer();
        skillService = mock(SkillService.class);
        skillFileService = mock(SkillFileService.class);

        syncer = new BundledSkillSyncer(
                properties,
                workspaceManager,
                bundleMaterializer,
                mock(ApplicationEventPublisher.class),
                skillService,
                skillFileService
        );
    }

    @Test
    @DisplayName("Sync applies classpath bundle files to DB for existing skill entities")
    void syncAppliesBundleFilesToDb() {
        SkillEntity pptxSkill = new SkillEntity();
        pptxSkill.setId(100L);
        pptxSkill.setName("pptx");

        when(skillService.findByName("pptx")).thenReturn(pptxSkill);

        List<String> synced = syncer.sync();

        assertTrue(synced.contains("pptx"));
        verify(skillFileService, atLeastOnce()).applyBundleFiles(eq(100L), anyMap(), eq(false));
    }

    @Test
    @DisplayName("Sync self-heals a workspace missing scripts/ despite unchanged version")
    void syncForcesDiskCopyWhenScriptsDirMissing() throws IOException {
        Path pptxDir = tmp.resolve("pptx");
        Files.createDirectories(pptxDir);
        // Same SKILL.md as the bundle (identical version) but no scripts/
        // directory — the state left behind by a build that shipped without
        // bundle scripts.
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("skills/pptx/SKILL.md")) {
            assertNotNull(is, "bundled pptx SKILL.md must exist on the test classpath");
            Files.write(pptxDir.resolve("SKILL.md"), is.readAllBytes());
        }

        SkillEntity pptxSkill = new SkillEntity();
        pptxSkill.setId(100L);
        pptxSkill.setName("pptx");
        when(skillService.findByName("pptx")).thenReturn(pptxSkill);

        List<String> synced = syncer.sync();

        assertTrue(synced.contains("pptx"), "Should re-sync when scripts directory is missing");
        assertTrue(Files.isDirectory(pptxDir.resolve("scripts")), "scripts directory should now be on disk");
        verify(skillFileService, atLeastOnce()).applyBundleFiles(eq(100L), anyMap(), eq(false));
        try (var archived = Files.list(tmp.resolve(".archived"))) {
            assertTrue(archived.anyMatch(p -> p.getFileName().toString().startsWith("pptx-")),
                    "old workspace should be archived, not overwritten in place");
        }
    }

    @Test
    @DisplayName("Sync is a strict no-op when the workspace is current with all buckets present")
    void syncSkipsDbWhenWorkspaceCurrent() {
        // First pass installs every bundled skill into the empty root.
        syncer.sync();
        clearInvocations(skillFileService);

        SkillEntity pptxSkill = new SkillEntity();
        pptxSkill.setId(100L);
        pptxSkill.setName("pptx");
        when(skillService.findByName("pptx")).thenReturn(pptxSkill);

        List<String> second = syncer.sync();

        assertFalse(second.contains("pptx"), "Unchanged workspace should not re-sync");
        verify(skillFileService, never()).applyBundleFiles(anyLong(), anyMap(), anyBoolean());
    }
}
