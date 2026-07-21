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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BundledSkillSyncer} ensuring bundled skill scripts are synced to DB and workspace disk.
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

        when(skillService.getByName("pptx")).thenReturn(pptxSkill);

        List<String> synced = syncer.sync();

        assertTrue(synced.contains("pptx"));
        verify(skillFileService, atLeastOnce()).applyBundleFiles(eq(100L), anyMap(), eq(false));
    }

    @Test
    @DisplayName("Sync forces copy to disk when scripts directory is missing despite unchanged version")
    void syncForcesDiskCopyWhenScriptsDirMissing() throws IOException {
        Path pptxDir = tmp.resolve("pptx");
        Files.createDirectories(pptxDir);
        // Create SKILL.md with current version but omit scripts directory
        Files.writeString(pptxDir.resolve("SKILL.md"), "---\nname: pptx\nversion: 1.0.0\n---\n");

        SkillEntity pptxSkill = new SkillEntity();
        pptxSkill.setId(100L);
        pptxSkill.setName("pptx");
        when(skillService.getByName("pptx")).thenReturn(pptxSkill);

        List<String> synced = syncer.sync();

        assertTrue(synced.contains("pptx"), "Should force re-sync when scripts directory is missing");
        assertTrue(Files.exists(pptxDir.resolve("scripts")), "scripts directory should now be copied to disk");
    }
}
