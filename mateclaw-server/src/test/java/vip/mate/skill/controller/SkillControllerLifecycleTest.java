package vip.mate.skill.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.skill.lifecycle.ConfirmRequiredException;
import vip.mate.skill.lifecycle.LifecycleTransition;
import vip.mate.skill.lifecycle.SkillCuratorJob;
import vip.mate.skill.lifecycle.SkillCuratorReport;
import vip.mate.skill.lifecycle.SkillCuratorReportStore;
import vip.mate.skill.lifecycle.SkillLifecycleService;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the skill lifecycle / curator controller endpoints: pin, archive
 * (including the bound-skill 409 confirm handshake), restore, and the
 * curator control panel.
 */
@ExtendWith(MockitoExtension.class)
class SkillControllerLifecycleTest {

    private static final long SID = 100L;

    @Mock
    private SkillService skillService;
    @Mock
    private AgentBindingService agentBindingService;
    @Mock
    private SkillLifecycleService skillLifecycleService;
    @Mock
    private SkillCuratorJob skillCuratorJob;
    @Mock
    private SkillCuratorReportStore skillCuratorReportStore;

    private SkillController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillController(
                skillService, null, null, null, null, null, null, null, null, null,
                agentBindingService, null, null,
                skillLifecycleService, skillCuratorJob, skillCuratorReportStore);
    }

    private SkillEntity skill(String state, boolean builtin) {
        SkillEntity s = new SkillEntity();
        s.setId(SID);
        s.setName("demo-skill");
        s.setSkillType(builtin ? "builtin" : "dynamic");
        s.setBuiltin(builtin);
        s.setLifecycleState(state);
        s.setWorkspaceId(1L);
        return s;
    }

    // ==================== pin ====================

    @Test
    void pinDelegatesToLifecycleService() {
        SkillEntity s = skill("active", false);
        when(skillService.getSkill(SID)).thenReturn(s);
        when(skillLifecycleService.setPinned(SID, true)).thenReturn(s);

        R<SkillEntity> r = controller.pin(SID, new SkillController.PinRequest(true), 1L);

        assertEquals(200, r.getCode());
        verify(skillLifecycleService).setPinned(SID, true);
    }

    // ==================== archive ====================

    @Test
    void archiveUnboundSkillGoesStraightThrough() {
        when(skillService.getSkill(SID)).thenReturn(skill("active", false));
        when(agentBindingService.enabledAgentsBoundToSkill(SID)).thenReturn(List.of());

        controller.archive(SID, false, null, 1L);

        verify(skillLifecycleService).applyManual(any(), eq(LifecycleTransition.TO_ARCHIVED),
                any(), anyString());
    }

    @Test
    void archiveBoundSkillWithoutForceRequiresConfirm() {
        when(skillService.getSkill(SID)).thenReturn(skill("active", false));
        when(agentBindingService.enabledAgentsBoundToSkill(SID))
                .thenReturn(List.of(new ConfirmRequiredException.AgentRow(42L, "DataAnalyst")));

        ConfirmRequiredException ex = assertThrows(ConfirmRequiredException.class,
                () -> controller.archive(SID, false, null, 1L));
        assertEquals("BOUND_SKILL_CONFIRM_REQUIRED", ex.getCode());
        verify(skillLifecycleService, never()).applyManual(any(), any(), any(), anyString());
    }

    @Test
    void archiveBoundSkillWithForceSkipsTheConfirm() {
        when(skillService.getSkill(SID)).thenReturn(skill("active", false));

        controller.archive(SID, true, null, 1L);

        verify(agentBindingService, never()).enabledAgentsBoundToSkill(any());
        verify(skillLifecycleService).applyManual(any(), eq(LifecycleTransition.TO_ARCHIVED),
                any(), anyString());
    }

    @Test
    void archiveRejectsBuiltinSkill() {
        when(skillService.getSkill(SID)).thenReturn(skill("active", true));
        assertThrows(MateClawException.class, () -> controller.archive(SID, false, null, 1L));
    }

    @Test
    void archiveRejectsAlreadyArchivedSkill() {
        when(skillService.getSkill(SID)).thenReturn(skill("archived", false));
        assertThrows(MateClawException.class, () -> controller.archive(SID, false, null, 1L));
    }

    // ==================== restore ====================

    @Test
    void restoreDelegatesToLifecycleService() {
        SkillEntity s = skill("archived", false);
        when(skillService.getSkill(SID)).thenReturn(s);
        when(skillLifecycleService.restore(SID)).thenReturn(s);

        controller.restore(SID, 1L);

        verify(skillLifecycleService).restore(SID);
    }

    // ==================== curator control panel ====================

    @Test
    void curatorDryRunDelegatesToJob() {
        when(skillCuratorJob.dryRunNow())
                .thenReturn(SkillCuratorReport.builder().runAt(LocalDateTime.now()).build());
        controller.curatorDryRun();
        verify(skillCuratorJob).dryRunNow();
    }

    @Test
    void curatorActivateFlipsTheFlag() {
        when(skillCuratorJob.status()).thenReturn(Map.of());
        controller.curatorActivate(true);
        verify(skillCuratorJob).activate(true);
    }

    @Test
    void curatorPauseAndResumeToggleTheJob() {
        when(skillCuratorJob.status()).thenReturn(Map.of());
        controller.curatorPause();
        verify(skillCuratorJob).setPaused(true);
        controller.curatorResume();
        verify(skillCuratorJob).setPaused(false);
    }

    @Test
    void curatorReportsListsRunIds() {
        when(skillCuratorReportStore.listRunIds(20)).thenReturn(List.of("20260519-020000"));
        R<List<String>> r = controller.curatorReports();
        assertEquals(1, r.getData().size());
    }

    @Test
    void curatorReportReadsAKnownRun() {
        when(skillCuratorReportStore.readRun("20260519-020000")).thenReturn(Map.of("runId", "20260519-020000"));
        R<Object> r = controller.curatorReport("20260519-020000");
        assertEquals(200, r.getCode());
    }

    @Test
    void curatorReportThrowsForUnknownRun() {
        when(skillCuratorReportStore.readRun("nope")).thenReturn(null);
        assertThrows(MateClawException.class, () -> controller.curatorReport("nope"));
    }
}
