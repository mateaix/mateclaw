package vip.mate.skill.lifecycle;
import vip.mate.skill.model.SkillOrigin;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.eq;
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
        s.setWorkspaceId(1L);
        s.setName("demo-skill");
        s.setSkillType(type);
        s.setBuiltin(false);
        s.setPinned(false);
        s.setLifecycleState(state);
        s.setLastActivityAt(lastActivity);
        s.setCreateTime(lastActivity);
        return s;
    }

    // ==================== adopt / release ====================

    @Test
    @DisplayName("adopting anchors to creation time — it does not buy a fresh window")
    void adoptDoesNotResetTheIdleClock() {
        // The operator hands over a skill knowing it is idle; granting it a new
        // 90-day lease would defeat the reason they handed it over.
        SkillEntity s = unobserved(now.minusDays(400));
        s.setOrigin(null);
        when(skillMapper.selectById(1L)).thenReturn(s);

        service.setAdopted(1L, true);

        ArgumentCaptor<LambdaUpdateWrapper<SkillEntity>> cap = updateCaptor();
        verify(skillMapper).update(eq(null), cap.capture());
        String sql = cap.getValue().getSqlSet();
        assertTrue(sql.contains("origin"), sql);
        assertTrue(sql.contains("curator_seen_at"), sql);

        // With the anchor at creation time the skill ages immediately.
        s.setCuratorSeenAt(s.getCreateTime());
        assertEquals(LifecycleTransition.TO_ARCHIVED, service.planTransition(s, now));
    }

    @Test
    @DisplayName("lifecycle audit records the owning workspace explicitly")
    void auditIsWorkspaceScoped() {
        SkillEntity s = skill("dynamic", "active", now);
        when(skillMapper.selectById(1L)).thenReturn(s);

        service.setPinned(1L, true);

        verify(auditEventService).record(eq("PIN"), eq("SKILL"), eq("1"),
                eq("demo-skill"), anyString(), eq(1L));
    }

    @Test
    @DisplayName("releasing hands ownership back and leaves the clock alone")
    void releaseRestoresUserOwnership() {
        SkillEntity s = skill("dynamic", "active", now.minusDays(10));
        s.setOrigin(SkillOrigin.AGENT.code());
        when(skillMapper.selectById(1L)).thenReturn(s);

        service.setAdopted(1L, false);

        ArgumentCaptor<LambdaUpdateWrapper<SkillEntity>> cap = updateCaptor();
        verify(skillMapper).update(eq(null), cap.capture());
        String sql = cap.getValue().getSqlSet();
        assertTrue(sql.contains("origin"), sql);
        assertFalse(sql.contains("curator_seen_at"), "release must not touch the clock: " + sql);
    }

    @Test
    @DisplayName("an exempt skill cannot be adopted")
    void exemptSkillIsNotAdoptable() {
        SkillEntity builtin = skill("builtin", "active", now.minusDays(10));
        builtin.setBuiltin(true);
        when(skillMapper.selectById(1L)).thenReturn(builtin);

        assertThrows(MateClawException.class, () -> service.setAdopted(1L, true));
        verify(skillMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("adopting a missing skill is a 404, not a silent no-op")
    void adoptMissingSkillThrows() {
        when(skillMapper.selectById(404L)).thenReturn(null);
        assertThrows(MateClawException.class, () -> service.setAdopted(404L, true));
    }

    @Test
    @DisplayName("a workspace cannot adopt another workspace's skill by id")
    void adoptRejectsForeignWorkspaceSkill() {
        SkillEntity foreign = skill("dynamic", "active", now.minusDays(10));
        foreign.setWorkspaceId(2L);
        when(skillMapper.selectById(1L)).thenReturn(foreign);

        assertThrows(MateClawException.class, () -> service.setAdopted(1L, true, 7L));

        verify(skillMapper, never()).update(any(), any());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<SkillEntity>> updateCaptor() {
        return ArgumentCaptor.forClass((Class<LambdaUpdateWrapper<SkillEntity>>) (Class<?>) LambdaUpdateWrapper.class);
    }

    // ==================== observation anchor ====================

    /** A skill curation has never seen: no activity, no observation stamp. */
    private SkillEntity unobserved(LocalDateTime createdAt) {
        SkillEntity s = skill("dynamic", "active", null);
        s.setCreateTime(createdAt);
        return s;
    }

    @Test
    @DisplayName("a never-observed skill is deferred however old it is")
    void unobservedSkillIsDeferred() {
        // Widening the curator scope pulls in skills created years ago. Judging
        // them on creation time would archive the whole batch on the first sweep.
        SkillEntity s = unobserved(now.minusDays(900));
        assertTrue(SkillLifecycleService.isUnobserved(s));
        assertEquals(LifecycleTransition.NONE, service.planTransition(s, now));
    }

    @Test
    @DisplayName("once observed, the idle clock runs from the observation, not creation")
    void observationAnchorReplacesCreationTime() {
        SkillEntity s = unobserved(now.minusDays(900));
        s.setCuratorSeenAt(now.minusDays(2));

        assertFalse(SkillLifecycleService.isUnobserved(s));
        assertEquals(now.minusDays(2), SkillLifecycleService.anchor(s));
        assertEquals(LifecycleTransition.NONE, service.planTransition(s, now));
    }

    @Test
    @DisplayName("an observed skill ages normally once the threshold passes")
    void observedSkillStillAges() {
        SkillEntity s = unobserved(now.minusDays(900));
        s.setCuratorSeenAt(now.minusDays(95));

        assertEquals(LifecycleTransition.TO_ARCHIVED, service.planTransition(s, now));
    }

    @Test
    @DisplayName("real activity outranks the observation stamp")
    void activityOutranksObservation() {
        SkillEntity s = skill("dynamic", "active", now.minusDays(1));
        s.setCuratorSeenAt(now.minusDays(400));

        assertEquals(now.minusDays(1), SkillLifecycleService.anchor(s));
        assertEquals(LifecycleTransition.NONE, service.planTransition(s, now));
    }

    @Test
    @DisplayName("creation time remains the fallback for rows predating the column")
    void creationTimeRemainsFallback() {
        SkillEntity s = skill("dynamic", "active", now.minusDays(95));
        assertEquals(now.minusDays(95), SkillLifecycleService.anchor(s));
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
        when(workspaceManager.restoreWorkspace("demo-skill", 1L))
                .thenReturn(SkillWorkspaceManager.RestoreResult.MOVED);

        service.restore(1L);

        verify(skillMapper).update(any(), any());
        verify(runtimeService).refreshActiveSkills();
    }

    @Test
    void restoreDbOnlySkillFlipsRowWithoutWorkspace() {
        when(skillMapper.selectById(1L)).thenReturn(archivedSkill());
        when(workspaceManager.restoreWorkspace("demo-skill", 1L))
                .thenReturn(SkillWorkspaceManager.RestoreResult.MISSING);

        service.restore(1L);

        verify(skillMapper).update(any(), any());
    }

    @Test
    void restoreRejectsUnrecoverableSkill() {
        SkillEntity s = archivedSkill();
        s.setSkillContent("   ");
        when(skillMapper.selectById(1L)).thenReturn(s);
        when(workspaceManager.restoreWorkspace("demo-skill", 1L))
                .thenReturn(SkillWorkspaceManager.RestoreResult.MISSING);

        assertThrows(MateClawException.class, () -> service.restore(1L));
        verify(skillMapper, never()).update(any(), any());
    }

    @Test
    void restoreRejectsWhenWorkspaceMoveBackFails() {
        when(skillMapper.selectById(1L)).thenReturn(archivedSkill());
        when(workspaceManager.restoreWorkspace("demo-skill", 1L))
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
        when(workspaceManager.archiveWorkspace(anyString(), any()))
                .thenReturn(SkillWorkspaceManager.ArchiveResult.FAILED);

        boolean applied = service.apply(s, LifecycleTransition.TO_ARCHIVED, now);

        assertFalse(applied);
        verify(skillMapper, never()).update(any(), any());
    }

    @Test
    void archiveCommitsForDbOnlySkillWithNoWorkspace() {
        SkillEntity s = skill("dynamic", "stale", now.minusDays(100));
        when(workspaceManager.archiveWorkspace(anyString(), any()))
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
        when(workspaceManager.archiveWorkspace(anyString(), any()))
                .thenReturn(SkillWorkspaceManager.ArchiveResult.MOVED);
        when(skillMapper.update(any(), any())).thenReturn(0);

        boolean applied = service.apply(s, LifecycleTransition.TO_ARCHIVED, now);

        assertFalse(applied);
        verify(workspaceManager).restoreWorkspace("demo-skill", 1L);
    }
}
