package vip.mate.memory.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.auth.service.AuthService;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.memory.repository.DreamReportMapper;
import vip.mate.memory.repository.MemoryRecallMapper;
import vip.mate.memory.service.MemoryHilService;
import vip.mate.memory.service.MorningCardService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for HiL edit API contract:
 * - Report-scoped edit: key must belong to that report's entry set, target is MEMORY.md
 * - Direct edit (reportId=0): key must be an existing section in the request's target file,
 *   which must be a whitelisted memory file (MEMORY.md / PROFILE.md / SOUL.md / structured/*.md)
 */
@ExtendWith(MockitoExtension.class)
class HilEditValidationTest {

    @Mock private DreamReportMapper dreamReportMapper;
    @Mock private MemoryRecallMapper recallMapper;
    @Mock private MorningCardService morningCardService;
    @Mock private MemoryHilService hilService;
    @Mock private DreamEventBroadcaster eventBroadcaster;
    @Mock private AuthService authService;

    private DreamController controller;

    @BeforeEach
    void setUp() {
        controller = new DreamController(dreamReportMapper, recallMapper,
                morningCardService, hilService, eventBroadcaster, authService);
    }

    @Test
    @DisplayName("Report-scoped edit: key not in report's candidates → rejected")
    void reportScopedEdit_keyNotInReport_rejected() {
        // Setup: report exists and belongs to agent
        DreamReportEntity report = new DreamReportEntity();
        report.setId(100L);
        report.setAgentId(1L);
        report.setStartedAt(LocalDateTime.of(2026, 4, 20, 3, 0));
        report.setFinishedAt(LocalDateTime.of(2026, 4, 20, 3, 5));
        report.setDeleted(0);
        lenient().when(dreamReportMapper.selectOne(any())).thenReturn(report);

        // No recall entries match the key "unrelated_section"
        MemoryRecallEntity candidate = new MemoryRecallEntity();
        candidate.setFilename("memory/2026-04-19.md#deployment_info");
        candidate.setLastRecalledAt(LocalDateTime.of(2026, 4, 20, 3, 2));
        candidate.setDeleted(0);
        lenient().when(recallMapper.selectList(any())).thenReturn(List.of(candidate));

        var result = controller.editEntry(1L, 100L, "unrelated_section",
                Map.of("content", "hacked content"));

        // Should fail — key doesn't belong to this report
        assertNotEquals(200, result.getCode());
        verify(hilService, never()).editMemoryEntry(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Report-scoped edit: key matches report candidate → allowed, writes MEMORY.md")
    void reportScopedEdit_keyInReport_allowed() {
        DreamReportEntity report = new DreamReportEntity();
        report.setId(100L);
        report.setAgentId(1L);
        report.setStartedAt(LocalDateTime.of(2026, 4, 20, 3, 0));
        report.setFinishedAt(LocalDateTime.of(2026, 4, 20, 3, 5));
        report.setDeleted(0);
        lenient().when(dreamReportMapper.selectOne(any())).thenReturn(report);

        // Recall entry filename contains the key
        MemoryRecallEntity candidate = new MemoryRecallEntity();
        candidate.setFilename("MEMORY.md#deployment_info");
        candidate.setLastRecalledAt(LocalDateTime.of(2026, 4, 20, 3, 2));
        candidate.setDeleted(0);
        lenient().when(recallMapper.selectList(any())).thenReturn(List.of(candidate));

        var result = controller.editEntry(1L, 100L, "deployment_info",
                Map.of("content", "updated content"));

        // Should succeed — report-scoped edits always target MEMORY.md
        assertEquals(200, result.getCode());
        verify(hilService).editMemoryEntry(eq(1L), eq("MEMORY.md"), eq("deployment_info"), eq("updated content"));
    }

    @Test
    @DisplayName("Report-scoped edit: substring of candidate key → rejected (exact match required)")
    void reportScopedEdit_substringKey_rejected() {
        DreamReportEntity report = new DreamReportEntity();
        report.setId(100L);
        report.setAgentId(1L);
        report.setStartedAt(LocalDateTime.of(2026, 4, 20, 3, 0));
        report.setFinishedAt(LocalDateTime.of(2026, 4, 20, 3, 5));
        report.setDeleted(0);
        lenient().when(dreamReportMapper.selectOne(any())).thenReturn(report);

        MemoryRecallEntity candidate = new MemoryRecallEntity();
        candidate.setFilename("MEMORY.md#deployment_info");
        candidate.setLastRecalledAt(LocalDateTime.of(2026, 4, 20, 3, 2));
        candidate.setDeleted(0);
        lenient().when(recallMapper.selectList(any())).thenReturn(List.of(candidate));

        // "deployment" is a substring of "deployment_info" — must be rejected
        var result = controller.editEntry(1L, 100L, "deployment",
                Map.of("content", "content"));

        assertNotEquals(200, result.getCode());
        verify(hilService, never()).editMemoryEntry(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Direct edit (reportId=0): existing section, no filename → defaults to MEMORY.md")
    void directEdit_existingSection_defaultsToMemoryMd() {
        when(hilService.sectionExists(1L, "MEMORY.md", "stable_facts")).thenReturn(true);

        var result = controller.editEntry(1L, 0L, "stable_facts",
                Map.of("content", "new content"));

        assertEquals(200, result.getCode());
        verify(hilService).editMemoryEntry(eq(1L), eq("MEMORY.md"), eq("stable_facts"), eq("new content"));
    }

    @Test
    @DisplayName("Direct edit (reportId=0): non-existing section → rejected")
    void directEdit_nonExistingSection_rejected() {
        when(hilService.sectionExists(1L, "MEMORY.md", "ghost_section")).thenReturn(false);

        var result = controller.editEntry(1L, 0L, "ghost_section",
                Map.of("content", "content"));

        assertNotEquals(200, result.getCode());
        verify(hilService, never()).editMemoryEntry(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Direct edit (reportId=0): PROFILE.md section → writes PROFILE.md, not MEMORY.md")
    void directEdit_profileFile_writesProfile() {
        when(hilService.sectionExists(1L, "PROFILE.md", "Identity")).thenReturn(true);

        var result = controller.editEntry(1L, 0L, "Identity",
                Map.of("content", "name: Mate", "filename", "PROFILE.md"));

        assertEquals(200, result.getCode());
        verify(hilService).editMemoryEntry(eq(1L), eq("PROFILE.md"), eq("Identity"), eq("name: Mate"));
    }

    @Test
    @DisplayName("Direct edit (reportId=0): SOUL.md section → writes SOUL.md")
    void directEdit_soulFile_writesSoul() {
        when(hilService.sectionExists(1L, "SOUL.md", "Tone")).thenReturn(true);

        var result = controller.editEntry(1L, 0L, "Tone",
                Map.of("content", "warm and direct", "filename", "SOUL.md"));

        assertEquals(200, result.getCode());
        verify(hilService).editMemoryEntry(eq(1L), eq("SOUL.md"), eq("Tone"), eq("warm and direct"));
    }

    @Test
    @DisplayName("Direct edit (reportId=0): structured/*.md section → allowed")
    void directEdit_structuredFile_allowed() {
        when(hilService.sectionExists(1L, "structured/user.md", "Preferences")).thenReturn(true);

        var result = controller.editEntry(1L, 0L, "Preferences",
                Map.of("content", "likes dark mode", "filename", "structured/user.md"));

        assertEquals(200, result.getCode());
        verify(hilService).editMemoryEntry(eq(1L), eq("structured/user.md"), eq("Preferences"),
                eq("likes dark mode"));
    }

    @Test
    @DisplayName("Direct edit (reportId=0): non-whitelisted filename → rejected")
    void directEdit_unsupportedFile_rejected() {
        var result = controller.editEntry(1L, 0L, "anything",
                Map.of("content", "content", "filename", "application.yml"));

        assertNotEquals(200, result.getCode());
        verify(hilService, never()).editMemoryEntry(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Direct edit (reportId=0): path-traversal filename → rejected")
    void directEdit_pathTraversal_rejected() {
        var result = controller.editEntry(1L, 0L, "anything",
                Map.of("content", "content", "filename", "../../etc/passwd"));

        assertNotEquals(200, result.getCode());
        verify(hilService, never()).editMemoryEntry(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Direct edit (reportId=0): blank content → rejected")
    void directEdit_blankContent_rejected() {
        var result = controller.editEntry(1L, 0L, "stable_facts",
                Map.of("content", "   "));

        assertNotEquals(200, result.getCode());
        verify(hilService, never()).editMemoryEntry(any(), any(), any(), any());
    }
}
