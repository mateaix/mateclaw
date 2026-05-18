package vip.mate.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.memory.event.MemoryWriteEvent;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for MemoryHilService — a user edit must land in the file the user is
 * editing (MEMORY.md / PROFILE.md / SOUL.md), not unconditionally in MEMORY.md.
 */
@ExtendWith(MockitoExtension.class)
class MemoryHilServiceTest {

    @Mock private WorkspaceFileService workspaceFileService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private MemoryHilService service;

    @BeforeEach
    void setUp() {
        service = new MemoryHilService(workspaceFileService, eventPublisher);
    }

    private WorkspaceFileEntity file(String filename, String content) {
        WorkspaceFileEntity e = new WorkspaceFileEntity();
        e.setAgentId(1L);
        e.setFilename(filename);
        e.setContent(content);
        return e;
    }

    @Test
    @DisplayName("Editing a PROFILE.md section writes back to PROFILE.md, not MEMORY.md")
    void editProfile_writesProfile() {
        String profile = "## Identity\nold name\n\n## Goals\nlearn\n";
        when(workspaceFileService.getFile(1L, "PROFILE.md")).thenReturn(file("PROFILE.md", profile));

        service.editMemoryEntry(1L, "PROFILE.md", "Identity", "new name");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(workspaceFileService).saveFile(eq(1L), eq("PROFILE.md"), content.capture());
        verify(workspaceFileService, never()).saveFile(eq(1L), eq("MEMORY.md"), any());

        String saved = content.getValue();
        assertTrue(saved.contains("## Identity\nnew name"), "section body replaced");
        assertTrue(saved.contains("<!-- user-edited:"), "user-edited marker appended");
        assertTrue(saved.contains("## Goals\nlearn"), "other sections untouched");
    }

    @Test
    @DisplayName("Editing SOUL.md does not publish a MemoryWriteEvent (avoids SOUL self-overwrite)")
    void editSoul_noEvent() {
        when(workspaceFileService.getFile(1L, "SOUL.md"))
                .thenReturn(file("SOUL.md", "## Tone\ndry\n"));

        service.editMemoryEntry(1L, "SOUL.md", "Tone", "warm");

        verify(workspaceFileService).saveFile(eq(1L), eq("SOUL.md"), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Editing MEMORY.md publishes a MemoryWriteEvent targeting MEMORY.md")
    void editMemory_publishesEvent() {
        when(workspaceFileService.getFile(1L, "MEMORY.md"))
                .thenReturn(file("MEMORY.md", "## Facts\nold\n"));

        service.editMemoryEntry(1L, "MEMORY.md", "Facts", "fresh");

        ArgumentCaptor<MemoryWriteEvent> event = ArgumentCaptor.forClass(MemoryWriteEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals("MEMORY.md", event.getValue().target());
    }

    @Test
    @DisplayName("Repeated edits do not accumulate user-edited markers")
    void repeatedEdit_singleMarker() {
        // Section body already carries a marker from a previous edit
        String memory = "## Facts\nv1\n<!-- user-edited: 2026-05-01 -->\n";
        when(workspaceFileService.getFile(1L, "MEMORY.md")).thenReturn(file("MEMORY.md", memory));

        // Simulate an editor that echoes the old marker back inside the new body
        service.editMemoryEntry(1L, "MEMORY.md", "Facts", "v2\n<!-- user-edited: 2026-05-01 -->");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(workspaceFileService).saveFile(eq(1L), eq("MEMORY.md"), content.capture());

        String saved = content.getValue();
        int markers = saved.split("<!-- user-edited:", -1).length - 1;
        assertEquals(1, markers, "exactly one marker after a re-edit");
        assertTrue(saved.contains("v2"));
        assertFalse(saved.contains("v1"));
    }

    @Test
    @DisplayName("Editing a section absent from the file appends it as a new section")
    void editMissingSection_appends() {
        when(workspaceFileService.getFile(1L, "PROFILE.md"))
                .thenReturn(file("PROFILE.md", "## Identity\nMate\n"));

        service.editMemoryEntry(1L, "PROFILE.md", "Goals", "ship the fix");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(workspaceFileService).saveFile(eq(1L), eq("PROFILE.md"), content.capture());

        String saved = content.getValue();
        assertTrue(saved.contains("## Identity\nMate"), "existing section kept");
        assertTrue(saved.contains("## Goals\nship the fix"), "new section appended");
    }
}
