package vip.mate.skill.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillCuratorReportStoreWorkspaceTest {

    @TempDir
    Path root;

    @Test
    void reportsAreWrittenAndReadOnlyInsideTheirWorkspace() {
        SkillWorkspaceManager workspaceManager = mock(SkillWorkspaceManager.class);
        when(workspaceManager.getWorkspaceRoot()).thenReturn(root);
        SkillCuratorReportStore store = new SkillCuratorReportStore(
                workspaceManager, new ObjectMapper().findAndRegisterModules());
        SkillCuratorReport report = SkillCuratorReport.builder()
                .runAt(LocalDateTime.of(2026, 8, 9, 12, 0))
                .dryRun(true)
                .config(30, 90, "AGENT_CREATED")
                .build();

        store.write(report, 7L);

        assertTrue(Files.isRegularFile(root.resolve("7/.curator")
                .resolve(report.getRunId()).resolve("run.json")));
        assertNotNull(store.readRun(7L, report.getRunId()));
        assertNull(store.readRun(8L, report.getRunId()));
    }
}
