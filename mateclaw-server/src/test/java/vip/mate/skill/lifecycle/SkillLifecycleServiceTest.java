package vip.mate.skill.lifecycle;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.skill.workspace.SkillWorkspaceProperties;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the lifecycle state machine ({@code planTransition}) and the
 * archive atomicity / compensation path.
 */
@ExtendWith(MockitoExtension.class)
class SkillLifecycleServiceTest {

    @Mock
    private SkillMapper skillMapper;
    @Mock
    private SkillWorkspaceManager workspaceManager;
    @Mock
    private SkillRuntimeService runtimeService;
    @Mock
    private AuditEventService auditEventService;

    private SkillLifecycleService service;

    private final LocalDateTime now = LocalDateTime.now();

    @BeforeAll
    static void initTableInfo() {
        // Lambda wrappers resolve column names from MyBatis-Plus's static
        // TableInfo cache; in a Spring context this happens during mapper
        // scan, in a plain MockitoExtension test we trigger it manually.
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                SkillEntity.class);
    }

    @BeforeEach
    void setUp() {
        SkillWorkspaceProperties workspaceProperties = new SkillWorkspaceProperties();
        SkillLifecycleProperties properties = new SkillLifecycleProperties();
        service = new SkillLifecycleService(skillMapper, workspaceManager, workspaceProperties,
                runtimeService, auditEventService, new ObjectMapper(), properties);
    }

    private SkillEntity skill(String type, String state, LocalDateTime lastActivity) {
        SkillEntity s = new SkillEntity();
        s.setId(1L);
        s.setName("demo-skill");
        s.setSkillType(type);
        s.setBuiltin(false);
        s.setPinned(false);
        s.setLifecycleState(state);
        s.setLastActivityAt(lastActivity);
        s.setCreateTime(lastActivity);
        return s;
    }

    // ==================== planTransition ====================

    @Test
    void activeIdlePastStaleThresholdBecomesStale() {
        SkillEntity s = skill("dynamic", "active", now.minusDays(31));
        assertEquals(LifecycleTransition.TO_STALE, service.planTransition(s, now));
    }

    @Test
    void staleIdlePastArchiveThresholdBecomesArchived() {
        SkillEntity s = skill("custom", "stale", now.minusDays(91));
        assertEquals(LifecycleTransition.TO_ARCHIVED, service.planTransition(s, now));
    }

    @Test
    void staleSkillWithRecentActivityReactivates() {
        SkillEntity s = skill("dynamic", "stale", now.minusDays(5));
        assertEquals(LifecycleTransition.REACTIVATE, service.planTransition(s, now));
    }

    @Test
    void pinnedSkillIsNeverTouched() {
        SkillEntity s = skill("dynamic", "active", now.minusDays(120));
        s.setPinned(true);
        assertEquals(LifecycleTransition.NONE, service.planTransition(s, now));
    }

    @Test
    void builtinSkillIsNeverTouched() {
        SkillEntity s = skill("builtin", "active", now.minusDays(120));
        s.setBuiltin(true);
        assertEquals(LifecycleTransition.NONE, service.planTransition(s, now));
    }

    @Test
    void protectedPrefixSkillIsNeverTouched() {
        SkillEntity s = skill("dynamic", "active", now.minusDays(120));
        s.setName("sys-health-probe");
        assertEquals(LifecycleTransition.NONE, service.planTransition(s, now));
    }

    @Test
    void freshSkillStaysActive() {
        SkillEntity s = skill("dynamic", "active", now.minusDays(3));
        assertEquals(LifecycleTransition.NONE, service.planTransition(s, now));
    }

    @Test
    void alreadyStaleSkillWithinArchiveWindowStaysPut() {
        // A skill already 'stale' and idle 50d (>= stale, < archive) yields
        // NONE — a second sweep makes no further change (idempotency).
        SkillEntity s = skill("dynamic", "stale", now.minusDays(50));
        assertEquals(LifecycleTransition.NONE, service.planTransition(s, now));
    }

    // ==================== bumpActivity / setPinned ====================

    @Test
    void bumpActivityWritesTheActivityAnchor() {
        service.bumpActivity(7L);
        verify(skillMapper).update(any(), any());
    }

    @Test
    void bumpActivityWithNullIdIsANoOp() {
        service.bumpActivity(null);
        verify(skillMapper, never()).update(any(), any());
    }

    @Test
    void setPinnedUpdatesTheRow() {
        SkillEntity s = skill("dynamic", "active", now.minusDays(1));
        when(skillMapper.selectById(1L)).thenReturn(s);
        service.setPinned(1L, true);
        verify(skillMapper).update(any(), any());
    }

    @Test
    void setPinnedThrowsWhenSkillMissing() {
        when(skillMapper.selectById(99L)).thenReturn(null);
        assertThrows(MateClawException.class, () -> service.setPinned(99L, true));
    }

    // ==================== restore ====================

    private SkillEntity archivedSkill() {
        SkillEntity s = skill("dynamic", "archived", now.minusDays(100));
        s.setSkillContent("---\nname: demo-skill\n---\n# body");
        return s;
    }

    @Test
    void restoreMovesWorkspaceBackAndFlipsTheRow() {
        when(skillMapper.selectById(1L)).thenReturn(archivedSkill());
        when(workspaceManager.restoreWorkspace("demo-skill"))
                .thenReturn(SkillWorkspaceManager.RestoreResult.MOVED);

        service.restore(1L);

        verify(skillMapper).update(any(), any());
        verify(runtimeService).refreshActiveSkills();
    }

    @Test
    void restoreDbOnlySkillFlipsRowWithoutWorkspace() {
        when(skillMapper.selectById(1L)).thenReturn(archivedSkill());
        when(workspaceManager.restoreWorkspace("demo-skill"))
                .thenReturn(SkillWorkspaceManager.RestoreResult.MISSING);

        service.restore(1L);

        verify(skillMapper).update(any(), any());
    }

    @Test
    void restoreRejectsUnrecoverableSkill() {
        SkillEntity s = archivedSkill();
        s.setSkillContent("   ");
        when(skillMapper.selectById(1L)).thenReturn(s);
        when(workspaceManager.restoreWorkspace("demo-skill"))
                .thenReturn(SkillWorkspaceManager.RestoreResult.MISSING);

        assertThrows(MateClawException.class, () -> service.restore(1L));
        verify(skillMapper, never()).update(any(), any());
    }

    @Test
    void restoreRejectsWhenWorkspaceMoveBackFails() {
        when(skillMapper.selectById(1L)).thenReturn(archivedSkill());
        when(workspaceManager.restoreWorkspace("demo-skill"))
                .thenReturn(SkillWorkspaceManager.RestoreResult.FAILED);

        assertThrows(MateClawException.class, () -> service.restore(1L));
        verify(skillMapper, never()).update(any(), any());
    }

    @Test
    void restoreRejectsSkillThatIsNotArchived() {
        when(skillMapper.selectById(1L)).thenReturn(skill("dynamic", "active", now));
        assertThrows(MateClawException.class, () -> service.restore(1L));
    }

    @Test
    void restoreThrowsWhenSkillMissing() {
        when(skillMapper.selectById(1L)).thenReturn(null);
        assertThrows(MateClawException.class, () -> service.restore(1L));
    }

    // ==================== archive atomicity ====================

    @Test
    void archiveDefersWhenWorkspaceMoveFails() {
        SkillEntity s = skill("dynamic", "stale", now.minusDays(100));
        when(workspaceManager.archiveWorkspace(anyString()))
                .thenReturn(SkillWorkspaceManager.ArchiveResult.FAILED);

        boolean applied = service.apply(s, LifecycleTransition.TO_ARCHIVED, now);

        assertFalse(applied);
        verify(skillMapper, never()).update(any(), any());
    }

    @Test
    void archiveCommitsForDbOnlySkillWithNoWorkspace() {
        SkillEntity s = skill("dynamic", "stale", now.minusDays(100));
        when(workspaceManager.archiveWorkspace(anyString()))
                .thenReturn(SkillWorkspaceManager.ArchiveResult.MISSING);
        when(skillMapper.update(any(), any())).thenReturn(1);

        boolean applied = service.apply(s, LifecycleTransition.TO_ARCHIVED, now);

        assertTrue(applied);
        verify(runtimeService).deregisterSkillWrappers(1L);
        verify(runtimeService).refreshActiveSkills();
    }

    @Test
    void archiveCompensatesWorkspaceWhenDbWriteTouchesNoRows() {
        SkillEntity s = skill("dynamic", "stale", now.minusDays(100));
        when(workspaceManager.archiveWorkspace(anyString()))
                .thenReturn(SkillWorkspaceManager.ArchiveResult.MOVED);
        when(skillMapper.update(any(), any())).thenReturn(0);

        boolean applied = service.apply(s, LifecycleTransition.TO_ARCHIVED, now);

        assertFalse(applied);
        verify(workspaceManager).restoreWorkspace("demo-skill");
    }
}
