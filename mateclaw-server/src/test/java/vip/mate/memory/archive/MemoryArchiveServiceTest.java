package vip.mate.memory.archive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.memory.MemoryProperties;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * B.13 — MemoryArchiveService tests.
 */
@ExtendWith(MockitoExtension.class)
class MemoryArchiveServiceTest {

    @Mock private WorkspaceFileService workspaceFileService;

    private MemoryProperties props;
    private MemoryArchiveService archiveService;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
        props.getDream().setArchiveEnabled(true);
        props.getDream().setArchiveKeepDays(30);
        archiveService = new MemoryArchiveService(workspaceFileService, props);
    }

    @Test
    @DisplayName("Flag off: archiveOldDreams is a no-op")
    void flagOff_noOp() {
        props.getDream().setArchiveEnabled(false);
        archiveService.archiveOldDreams(1L);
        verify(workspaceFileService, never()).saveFile(any(), any(), any());
    }

    @Test
    @DisplayName("Empty DREAMS.md: nothing to archive")
    void emptyDreams_noArchive() {
        when(workspaceFileService.getFile(1L, "DREAMS.md")).thenReturn(null);
        archiveService.archiveOldDreams(1L);
        verify(workspaceFileService, never()).saveFile(any(), any(), any());
    }

    @Test
    @DisplayName("All entries recent: nothing archived, DREAMS.md unchanged")
    void allRecent_noArchive() {
        String content = "# Dreaming 整合日记\n\n## 2099-01-01 03:00 Dreaming\n\nSome content\n";
        WorkspaceFileEntity file = new WorkspaceFileEntity();
        file.setContent(content);
        when(workspaceFileService.getFile(1L, "DREAMS.md")).thenReturn(file);

        archiveService.archiveOldDreams(1L);

        // Only the DREAMS.md save should NOT happen since nothing was archived
        verify(workspaceFileService, never()).saveFile(any(), any(), any());
    }

    @Test
    @DisplayName("Old entries moved to monthly archive file")
    void oldEntries_archived() {
        String content = "# Dreaming 整合日记\n\n"
                + "## 2020-01-15 03:00 Dreaming\n\nOld entry content\n\n"
                + "## 2099-12-01 03:00 Dreaming\n\nRecent entry\n";
        WorkspaceFileEntity file = new WorkspaceFileEntity();
        file.setContent(content);
        when(workspaceFileService.getFile(1L, "DREAMS.md")).thenReturn(file);
        when(workspaceFileService.getFile(1L, "memory/dreams/2020-01.md")).thenReturn(null);

        archiveService.archiveOldDreams(1L);

        // Should save the archive file
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(workspaceFileService).saveFile(eq(1L), eq("memory/dreams/2020-01.md"), contentCaptor.capture());
        assertTrue(contentCaptor.getValue().contains("Old entry content"));

        // Should save updated DREAMS.md (only recent entry)
        verify(workspaceFileService).saveFile(eq(1L), eq("DREAMS.md"), contentCaptor.capture());
        String updatedDreams = contentCaptor.getValue();
        assertTrue(updatedDreams.contains("Recent entry"));
        assertFalse(updatedDreams.contains("Old entry content"));
    }

    @Test
    @DisplayName("Idempotent: second archive call on same content does not duplicate")
    void idempotent_noDuplicate() {
        // After first archive, DREAMS.md only has recent entries
        String content = "# Dreaming 整合日记\n\n## 2099-12-01 03:00 Dreaming\n\nRecent\n";
        WorkspaceFileEntity file = new WorkspaceFileEntity();
        file.setContent(content);
        when(workspaceFileService.getFile(1L, "DREAMS.md")).thenReturn(file);

        archiveService.archiveOldDreams(1L);

        // Nothing old to archive
        verify(workspaceFileService, never()).saveFile(any(), any(), any());
    }
}
