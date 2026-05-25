package vip.mate.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.archive.MemoryArchiveService;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B.14 — Dream flag guard tests: verifies flag on/off behavior for
 * focused-enabled and archive-enabled flags.
 */
@ExtendWith(MockitoExtension.class)
class DreamFlagGuardTest {

    @Mock private WorkspaceFileService workspaceFileService;
    @Mock private MemoryArchiveService archiveService;

    private MemoryProperties props;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
    }

    @Test
    @DisplayName("archive-enabled=false: archiveService.archiveOldDreams never called")
    void archiveOff_noArchive() {
        props.getDream().setArchiveEnabled(false);
        MemoryArchiveService service = new MemoryArchiveService(workspaceFileService, props);
        service.archiveOldDreams(1L);
        verify(workspaceFileService, never()).saveFile(any(), any(), any());
    }

    @Test
    @DisplayName("archive-enabled=true: archiveService.archiveOldDreams runs")
    void archiveOn_runs() {
        props.getDream().setArchiveEnabled(true);
        props.getDream().setArchiveKeepDays(30);
        MemoryArchiveService service = new MemoryArchiveService(workspaceFileService, props);

        // Set up old content
        String content = "# Dreaming\n\n## 2020-01-01 03:00 Dreaming\n\nOld\n";
        WorkspaceFileEntity file = new WorkspaceFileEntity();
        file.setContent(content);
        when(workspaceFileService.getFile(1L, "DREAMS.md")).thenReturn(file);
        lenient().when(workspaceFileService.getFile(eq(1L), argThat(s -> s != null && s.startsWith("memory/dreams/")))).thenReturn(null);

        service.archiveOldDreams(1L);

        // Archive file should be written
        verify(workspaceFileService, atLeastOnce()).saveFile(eq(1L), argThat(s -> s != null && s.contains("memory/dreams/")), any());
    }

    @Test
    @DisplayName("focused-enabled flag is correctly read from DreamProperties")
    void focusedEnabledFlag() {
        props.getDream().setFocusedEnabled(false);
        assertFalse(props.getDream().isFocusedEnabled());

        props.getDream().setFocusedEnabled(true);
        assertTrue(props.getDream().isFocusedEnabled());
    }

    @Test
    @DisplayName("archive-enabled flag is correctly read from DreamProperties")
    void archiveEnabledFlag() {
        props.getDream().setArchiveEnabled(false);
        assertFalse(props.getDream().isArchiveEnabled());

        props.getDream().setArchiveEnabled(true);
        assertTrue(props.getDream().isArchiveEnabled());
    }

    @Test
    @DisplayName("DreamReport SKIPPED when emergence is disabled")
    void emergenceDisabled_skipped() {
        props.setEmergenceEnabled(false);
        // Create a minimal service to test skipped report
        MemoryEmergenceService service = new MemoryEmergenceService(
                workspaceFileService, null, null, props, null, null, null, archiveService, null, null);

        DreamReport report = service.consolidate(1L, DreamMode.NIGHTLY, null);
        assertEquals(DreamStatus.SKIPPED, report.status());
        assertEquals("emergence disabled", report.llmReason());
    }

    @Test
    @DisplayName("DreamReport SKIPPED when no daily notes found")
    void noDailyNotes_skipped() {
        props.setEmergenceEnabled(true);
        when(workspaceFileService.listFiles(1L)).thenReturn(List.of());

        MemoryEmergenceService service = new MemoryEmergenceService(
                workspaceFileService, null, null, props, null, null, null, archiveService, null, null);

        DreamReport report = service.consolidate(1L, DreamMode.FOCUSED, "test");
        assertEquals(DreamStatus.SKIPPED, report.status());
    }
}
