package vip.mate.skill.lifecycle;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.system.service.SystemSettingService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * Covers the daily sweep gates (enabled / paused / first-run throttle), the
 * dry-run vs applied count split, orphan reconciliation, and the status
 * payload.
 */
@ExtendWith(MockitoExtension.class)
class SkillCuratorJobTest {

    @Mock
    private SkillLifecycleService lifecycleService;
    @Mock
    private SkillMapper skillMapper;
    @Mock
    private SkillCuratorReportStore reportStore;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private AgentBindingService agentBindingService;
    @Mock
    private SkillWorkspaceManager workspaceManager;
    @Mock
    private CuratorRunNotifier notifier;
    @Mock
    private SkillConsolidationService consolidationService;
    @Mock
    private SkillSnapshotService snapshotService;

    private SkillLifecycleProperties properties;
    private SkillCuratorJob job;

    private final LocalDateTime now = LocalDateTime.now();

    private static String scoped(String key) {
        return key + ".workspace.1";
    }

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                SkillEntity.class);
    }

    @BeforeEach
    void setUp() {
        properties = new SkillLifecycleProperties();
        job = new SkillCuratorJob(lifecycleService, skillMapper, reportStore, properties,
                systemSettingService, agentBindingService, workspaceManager, notifier, consolidationService,
                snapshotService);
    }

    private SkillEntity candidate(long id, String state, LocalDateTime lastActivity) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setWorkspaceId(1L);
        s.setName("skill-" + id);
        s.setSkillType("dynamic");
        s.setBuiltin(false);
        s.setPinned(false);
        s.setLifecycleState(state);
        s.setLastActivityAt(lastActivity);
        s.setCreateTime(lastActivity);
        return s;
    }

    /** Stub the sweep collaborators with empty reconcile + the given candidates. */
    private void stubSweep(List<SkillEntity> candidates) {
        when(reportStore.write(any(), eq(1L))).thenAnswer(i -> i.getArgument(0));
        when(agentBindingService.skillIdsBoundToEnabledAgents(1L)).thenReturn(Set.of());
        when(agentBindingService.blockedByBindingCandidates(any(), eq(1L))).thenReturn(List.of());
        // reconcileOrphans queries archived rows first, loadCandidates second.
        when(skillMapper.selectList(any())).thenReturn(List.of(), candidates);
    }

    // ==================== Gates ====================

    @Test
    void disabledCuratorNeverSweeps() {
        properties.setEnabled(false);
        job.run();
        verify(reportStore, never()).write(any(), any());
    }

    @Test
    void offScopeNeverSweeps() {
        properties.setScope("OFF");
        job.run();
        verify(reportStore, never()).write(any(), any());
    }

    @Test
    void pausedCuratorNeverSweeps() {
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), anyBoolean())).thenReturn(true);
        job.run();
        verify(reportStore, never()).write(any(), any());
    }

    @Test
    void firstObservationSeedsTimestampAndDefers() {
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.FIRST_RUN_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getString(eq(scoped(SkillCuratorJob.LAST_OBSERVED_KEY)), any())).thenReturn(null);

        job.run();

        verify(systemSettingService).saveString(eq(scoped(SkillCuratorJob.LAST_OBSERVED_KEY)), anyString(), anyString());
        verify(reportStore, never()).write(any(), any());
    }

    @Test
    void dryRunIsThrottledWithinTheInterval() {
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.FIRST_RUN_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getString(eq(scoped(SkillCuratorJob.LAST_OBSERVED_KEY)), any()))
                .thenReturn(now.minusHours(2).toString());
        when(systemSettingService.getString(eq(scoped(SkillCuratorJob.LAST_DRY_RUN_KEY)), any()))
                .thenReturn(now.minusHours(2).toString());

        job.run();

        verify(reportStore, never()).write(any(), any());
    }

    @Test
    void dryRunSweepsOncePerIntervalWhenDue() {
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.FIRST_RUN_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getString(eq(scoped(SkillCuratorJob.LAST_OBSERVED_KEY)), any()))
                .thenReturn(now.minusHours(30).toString());
        when(systemSettingService.getString(eq(scoped(SkillCuratorJob.LAST_DRY_RUN_KEY)), any())).thenReturn(null);
        stubSweep(List.of());

        job.run();

        ArgumentCaptor<SkillCuratorReport> cap = ArgumentCaptor.forClass(SkillCuratorReport.class);
        verify(reportStore).write(cap.capture(), eq(1L));
        assertTrue(cap.getValue().isDryRun());
        verify(systemSettingService).saveString(eq(scoped(SkillCuratorJob.LAST_DRY_RUN_KEY)), anyString(), anyString());
    }

    // ==================== Sweep counts ====================

    @Test
    void dryRunReportShowsPlannedButNotApplied() {
        stubSweep(List.of(candidate(1L, "active", now.minusDays(40))));
        when(lifecycleService.planTransition(any(), any())).thenReturn(LifecycleTransition.TO_STALE);

        SkillCuratorReport report = job.dryRunNow();

        assertTrue(report.isDryRun());
        assertEquals(1, report.getPlanned().stale());
        assertEquals(0, report.getApplied().stale());
        assertEquals(1, report.getScanned());
        verify(lifecycleService, never()).apply(any(), any(), any());
    }

    @Test
    void activatedSweepAppliesTransitions() {
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.FIRST_RUN_KEY)), anyBoolean())).thenReturn(true);
        stubSweep(List.of(candidate(1L, "active", now.minusDays(40))));
        when(lifecycleService.planTransition(any(), any())).thenReturn(LifecycleTransition.TO_STALE);
        when(lifecycleService.apply(any(), any(), any())).thenReturn(true);

        job.run();

        ArgumentCaptor<SkillCuratorReport> cap = ArgumentCaptor.forClass(SkillCuratorReport.class);
        verify(reportStore).write(cap.capture(), eq(1L));
        assertEquals(1, cap.getValue().getPlanned().stale());
        assertEquals(1, cap.getValue().getApplied().stale());
    }

    @Test
    void activatedSweepStopsWhenRequiredSnapshotFails() {
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.FIRST_RUN_KEY)), anyBoolean())).thenReturn(true);
        doThrow(new IllegalStateException("snapshot unavailable"))
                .when(snapshotService).captureRequired("pre-sweep", 1L);

        job.run();

        verify(reportStore, never()).write(any(), any());
        verify(lifecycleService, never()).apply(any(), any(), any());
    }

    /** A candidate curation has never seen: no activity, no observation stamp. */
    private SkillEntity unobservedCandidate(long id, LocalDateTime createdAt) {
        SkillEntity s = candidate(id, "active", null);
        s.setCreateTime(createdAt);
        return s;
    }

    @Test
    void unobservedCandidateIsSeededAndNotJudged() {
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.FIRST_RUN_KEY)), anyBoolean())).thenReturn(true);
        stubSweep(List.of(unobservedCandidate(1L, now.minusDays(900))));

        job.run();

        // Stamped so the next sweep has a real anchor, but never judged this time.
        verify(lifecycleService).markObserved(any(), any());
        verify(lifecycleService, never()).planTransition(any(), any());
        verify(lifecycleService, never()).apply(any(), any(), any());

        ArgumentCaptor<SkillCuratorReport> cap = ArgumentCaptor.forClass(SkillCuratorReport.class);
        verify(reportStore).write(cap.capture(), eq(1L));
        assertEquals(1, cap.getValue().getNewlyObserved());
        assertEquals(0, cap.getValue().getPlanned().archived());
    }

    @Test
    void dryRunDoesNotSeedButReachesTheSameVerdict() {
        // The preview is what an operator reads before widening the scope, so
        // it must not predict archives that a real run would defer — while
        // still writing nothing.
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.FIRST_RUN_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getString(eq(scoped(SkillCuratorJob.LAST_OBSERVED_KEY)), any()))
                .thenReturn(now.minusDays(2).toString());
        stubSweep(List.of(unobservedCandidate(1L, now.minusDays(900))));

        job.run();

        verify(lifecycleService, never()).markObserved(any(), any());
        verify(lifecycleService, never()).planTransition(any(), any());

        ArgumentCaptor<SkillCuratorReport> cap = ArgumentCaptor.forClass(SkillCuratorReport.class);
        verify(reportStore).write(cap.capture(), eq(1L));
        assertEquals(1, cap.getValue().getNewlyObserved());
        assertEquals(0, cap.getValue().getPlanned().archived());
    }

    @Test
    void reconcileReactivatesArchivedRowWhoseWorkspaceReturned() {
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), anyBoolean())).thenReturn(false);
        when(systemSettingService.getBool(eq(scoped(SkillCuratorJob.FIRST_RUN_KEY)), anyBoolean())).thenReturn(true);
        when(reportStore.write(any(), eq(1L))).thenAnswer(i -> i.getArgument(0));
        when(agentBindingService.skillIdsBoundToEnabledAgents(1L)).thenReturn(Set.of());
        when(agentBindingService.blockedByBindingCandidates(any(), eq(1L))).thenReturn(List.of());
        SkillEntity orphan = candidate(9L, "archived", now.minusDays(100));
        // 1st selectList = reconcile (archived rows); 2nd = loadCandidates.
        when(skillMapper.selectList(any())).thenReturn(List.of(orphan), List.of(orphan), List.of());
        when(workspaceManager.conventionWorkspaceExists("skill-9", 1L)).thenReturn(true);

        job.run();

        // reconcileOrphans flips the divergent row back via a direct update.
        verify(skillMapper).update(any(), any());
    }

    // ==================== Status & setters ====================

    @Test
    void statusReturnsConfigControlAndCounts() {
        when(skillMapper.selectCount(any())).thenReturn(0L);
        when(agentBindingService.blockedByBindingCandidates(any(), eq(1L))).thenReturn(List.of());
        when(reportStore.latestRunId(1L)).thenReturn(null);

        Map<String, Object> status = job.status();

        assertTrue(status.containsKey("config"));
        assertTrue(status.containsKey("control"));
        assertTrue(status.containsKey("counts"));
    }

    @Test
    void activateAndPauseWriteSystemSettings() {
        job.activate(true);
        verify(systemSettingService).saveBool(eq(scoped(SkillCuratorJob.FIRST_RUN_KEY)), eq(true), anyString());
        job.setPaused(true);
        verify(systemSettingService).saveBool(eq(scoped(SkillCuratorJob.PAUSED_KEY)), eq(true), anyString());
    }
}
