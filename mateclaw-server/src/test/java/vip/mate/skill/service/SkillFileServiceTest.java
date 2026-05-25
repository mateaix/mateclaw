package vip.mate.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.skill.model.SkillFileEntity;
import vip.mate.skill.repository.SkillFileMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the empty-bundle guard on the canonical-store side.
 * <p>
 * Mirrors the FS-side guard in {@code SkillWorkspaceManagerApplyBundleTest}:
 * if the new bundle has zero entries for a bucket, existing rows for that
 * bucket are preserved unless {@code force=true}. Issue #104 hit this on
 * the FS path; the DB path now has the same protection so the canonical
 * store cannot be silently wiped either.
 */
class SkillFileServiceTest {

    private SkillFileMapper mapper;
    private SkillFileService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SkillFileMapper.class);
        service = new SkillFileService(mapper);
    }

    @Test
    @DisplayName("empty bundle preserves existing scripts rows")
    void emptyBundlePreservesScripts() {
        SkillFileEntity row = newRow(1L, "scripts/run.py", "important");
        when(mapper.selectList(any())).thenReturn(List.of(row));

        var result = service.applyBundleFiles(42L, Map.of(), false);

        assertTrue(result.scriptsPreservedDueToEmptyBundle());
        assertEquals(0, result.rowsWritten());
        assertEquals(0, result.rowsPruned());
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("force=true removes even preserved rows")
    void forceFlagPrunesScripts() {
        SkillFileEntity row = newRow(1L, "scripts/run.py", "doomed");
        when(mapper.selectList(any())).thenReturn(List.of(row));

        var result = service.applyBundleFiles(42L, Map.of(), true);

        assertFalse(result.scriptsPreservedDueToEmptyBundle());
        assertEquals(1, result.rowsPruned());
        verify(mapper).deleteById(1L);
    }

    @Test
    @DisplayName("write-then-prune updates changed rows, drops removed ones, inserts new")
    void mixedApply() {
        SkillFileEntity keep = newRow(1L, "scripts/keep.py", "v1");
        SkillFileEntity removed = newRow(2L, "scripts/old.py", "obsolete");
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(List.of(keep, removed)));

        var result = service.applyBundleFiles(42L, Map.of(
                "scripts/keep.py", "v2",       // changed → update
                "scripts/new.py", "fresh"      // new → insert
        ), false);

        assertEquals(2, result.rowsWritten(), "1 updated + 1 inserted");
        assertEquals(1, result.rowsPruned(), "old.py removed");
        verify(mapper, times(1)).insert(any(SkillFileEntity.class));
        ArgumentCaptor<SkillFileEntity> updateCaptor = ArgumentCaptor.forClass(SkillFileEntity.class);
        verify(mapper, times(1)).updateById((SkillFileEntity) updateCaptor.capture());
        assertEquals("v2", updateCaptor.getValue().getContent());
        verify(mapper).deleteById(2L);
    }

    @Test
    @DisplayName("unchanged rows skip the update (sha256 idempotency)")
    void unchangedRowSkipped() {
        String content = "stable";
        SkillFileEntity row = newRow(7L, "scripts/run.py", content);

        when(mapper.selectList(any())).thenReturn(List.of(row));

        var result = service.applyBundleFiles(42L, Map.of("scripts/run.py", content), false);

        assertEquals(0, result.rowsWritten());
        assertEquals(0, result.rowsPruned());
        verify(mapper, never()).updateById(any(SkillFileEntity.class));
        verify(mapper, never()).insert(any(SkillFileEntity.class));
        verify(mapper, never()).deleteById(anyLong());
    }

    private static final AtomicLong IDS = new AtomicLong(1);

    private static SkillFileEntity newRow(Long id, String path, String content) {
        SkillFileEntity e = new SkillFileEntity();
        e.setId(id == null ? IDS.incrementAndGet() : id);
        e.setSkillId(42L);
        e.setFilePath(path);
        e.setContent(content);
        e.setContentSize(content.length());
        e.setSha256(SkillFileService.sha256Hex(content));
        return e;
    }
}
