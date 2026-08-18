package vip.mate.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.planning.model.PlanEntity;
import vip.mate.planning.model.SubPlanEntity;
import vip.mate.planning.service.PlanningService;
import vip.mate.team.event.TeamTasksDelegatedEvent;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import vip.mate.team.model.TeamRunCreateCommand;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRole;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the plan→board hand-off contract: all-or-nothing member resolution,
 * dependency→blockedBy mapping with plan linkage in task metadata, parking
 * via the delegated status + dispatch event, and the three-way resume gate.
 */
class TeamPlanBridgeTest {

    private static final Long TEAM_ID = 10L;
    private static final Long LEAD_ID = 1L;
    private static final Long WRITER_ID = 2L;
    private static final Long ANALYST_ID = 3L;
    private static final Long PLAN_ID = 77L;
    private static final String CONV = "lead-conv";
    private static final Long WORKSPACE_ID = 30L;
    private static final Long RUN_ID = 20L;

    private TeamService teamService;
    private TeamTaskService taskService;
    private PlanningService planningService;
    private TeamRunService runService;
    private AgentMapper agentMapper;
    private ApplicationEventPublisher eventPublisher;
    private TeamPlanBridge bridge;
    private AgentTeamEntity team;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        taskService = mock(TeamTaskService.class);
        planningService = mock(PlanningService.class);
        runService = mock(TeamRunService.class);
        agentMapper = mock(AgentMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        bridge = new TeamPlanBridge(teamService, taskService, runService, planningService,
                agentMapper, eventPublisher);

        team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setName("编队");
        team.setLeadAgentId(LEAD_ID);
        team.setWorkspaceId(WORKSPACE_ID);

        when(teamService.listMembers(TEAM_ID)).thenReturn(List.of(
                member(LEAD_ID, TeamRole.LEAD),
                member(WRITER_ID, TeamRole.MEMBER),
                member(ANALYST_ID, TeamRole.MEMBER)));
        when(agentMapper.selectById(WRITER_ID)).thenReturn(agent(WRITER_ID, "写手"));
        when(agentMapper.selectById(ANALYST_ID)).thenReturn(agent(ANALYST_ID, "分析师"));
    }

    private static AgentTeamMemberEntity member(Long agentId, String role) {
        AgentTeamMemberEntity m = new AgentTeamMemberEntity();
        m.setTeamId(TEAM_ID);
        m.setAgentId(agentId);
        m.setRole(role);
        return m;
    }

    private static AgentEntity agent(Long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private static TeamTaskEntity task(Long id, int number, int stepIndex, String status) {
        TeamTaskEntity t = new TeamTaskEntity();
        t.setId(id);
        t.setTeamId(TEAM_ID);
        t.setRunId(RUN_ID);
        t.setTaskNumber(number);
        t.setSubject("task " + number);
        t.setStatus(status);
        t.setAssigneeAgentId(WRITER_ID);
        t.setMetadata("{\"planId\":\"" + PLAN_ID + "\",\"stepIndex\":" + stepIndex + "}");
        return t;
    }

    // ==================== member resolution ====================

    @Test
    @DisplayName("resolution is all-or-nothing: one unknown name keeps the legacy pipeline")
    void resolutionAllOrNothing() {
        assertEquals(List.of(WRITER_ID, ANALYST_ID),
                bridge.resolveMembers(team, List.of("s1", "s2"), List.of("写手", "分析师")));
        assertNull(bridge.resolveMembers(team, List.of("s1", "s2"), List.of("写手", "路人")));
        assertNull(bridge.resolveMembers(team, List.of("s1", "s2"), List.of("写手", "")));
        assertNull(bridge.resolveMembers(team, List.of("s1", "s2"), null));
    }

    @Test
    @DisplayName("explicitly requested workspace agents missing from the team are reported")
    void reportsNamedAgentsOutsideRoster() {
        AgentEntity general = agent(4L, "通用助手");

        assertEquals(List.of("通用助手"), bridge.namedAgentsOutsideRoster(
                team, "请让写手、分析师和通用助手共同完成", List.of(
                        agent(WRITER_ID, "写手"), agent(ANALYST_ID, "分析师"), general)));
        assertTrue(bridge.namedAgentsOutsideRoster(
                team, "请让写手和分析师共同完成", List.of(general)).isEmpty());
    }

    // ==================== hand-off ====================

    @Test
    @DisplayName("delegatePlan maps deps to blockedBy, stamps plan linkage, parks and nudges dispatch")
    void delegatePlanCreatesLinkedTasks() {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setStatus(TeamRunStatus.PLANNING);
        when(runService.startRun(any())).thenReturn(run);
        when(taskService.listTasksByRun(RUN_ID)).thenReturn(List.of());
        when(runService.sealRunWithResult(RUN_ID, WORKSPACE_ID))
                .thenReturn(new TeamRunService.SealResult(run, true));
        when(taskService.createTask(any())).thenAnswer(inv -> {
            TeamTaskCreateCommand cmd = inv.getArgument(0);
            TeamTaskEntity created = new TeamTaskEntity();
            created.setId((long) (100 + cmd.getMetadata().hashCode() % 1000));
            created.setId(cmd.getMetadata().contains("\"stepIndex\":0") ? 101L : 102L);
            created.setTaskNumber(created.getId().intValue() - 100);
            created.setSubject(cmd.getSubject());
            created.setAssigneeAgentId(cmd.getAssigneeAgentId());
            return created;
        });

        String announcement = bridge.delegatePlan(team, PLAN_ID, "整体请求",
                List.of("第一步", "第二步"), List.of(List.of(), List.of(0)),
                List.of(WRITER_ID, ANALYST_ID), CONV);

        ArgumentCaptor<TeamTaskCreateCommand> captor =
                ArgumentCaptor.forClass(TeamTaskCreateCommand.class);
        verify(taskService, times(2)).createTask(captor.capture());
        TeamTaskCreateCommand first = captor.getAllValues().get(0);
        TeamTaskCreateCommand second = captor.getAllValues().get(1);

        assertNull(first.getBlockedBy());
        assertEquals(List.of(101L), second.getBlockedBy());
        assertTrue(first.getMetadata().contains("\"planId\":\"" + PLAN_ID + "\""));
        assertTrue(second.getMetadata().contains("\"stepIndex\":1"));
        assertEquals(CONV, first.getLeadConversationId());
        assertEquals(LEAD_ID, first.getCreatedByAgentId());
        assertEquals(RUN_ID, first.getRunId());
        assertEquals(RUN_ID, second.getRunId());
        assertTrue(first.getDescription().contains("整体请求"));

        ArgumentCaptor<TeamRunCreateCommand> runCaptor = ArgumentCaptor.forClass(TeamRunCreateCommand.class);
        verify(runService).startRun(runCaptor.capture());
        assertEquals(WORKSPACE_ID, runCaptor.getValue().getWorkspaceId());
        assertEquals(-PLAN_ID, runCaptor.getValue().getOriginMessageId());
        assertTrue(runCaptor.getValue().getMetadata().contains("\"planId\":\"" + PLAN_ID + "\""));

        InOrder order = inOrder(runService, taskService, planningService, eventPublisher);
        order.verify(runService).startRun(any());
        order.verify(taskService, times(2)).createTask(any());
        order.verify(runService).sealRunWithResult(RUN_ID, WORKSPACE_ID);
        order.verify(planningService).markPlanDelegated(PLAN_ID);
        order.verify(eventPublisher).publishEvent(new TeamTasksDelegatedEvent(TEAM_ID));
        assertTrue(announcement.contains("并行"));
        assertTrue(announcement.contains("前置"));
    }

    @Test
    @DisplayName("a repeated delegation for a sealed run returns existing tasks without side effects")
    void sealedRunDelegationIsIdempotent() {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setStatus(TeamRunStatus.RUNNING);
        List<TeamTaskEntity> existing = List.of(
                task(101L, 1, 0, TeamTaskStatus.PENDING),
                task(102L, 2, 1, TeamTaskStatus.PENDING));
        when(runService.startRun(any())).thenReturn(run);
        when(taskService.listTasksByRun(RUN_ID)).thenReturn(existing);

        String announcement = bridge.delegatePlan(team, PLAN_ID, "整体请求",
                List.of("第一步", "第二步"), List.of(List.of(), List.of(0)),
                List.of(WRITER_ID, ANALYST_ID), CONV);

        assertTrue(announcement.contains("task 1"));
        verify(taskService, never()).createTask(any());
        verify(runService, never()).sealRunWithResult(any(), any());
        verify(planningService, never()).markPlanDelegated(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("a retry with existing planning tasks seals once without recreating tasks")
    void existingPlanningTasksAreSealedWithoutDuplication() {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setStatus(TeamRunStatus.PLANNING);
        List<TeamTaskEntity> existing = List.of(
                task(101L, 1, 0, TeamTaskStatus.PENDING),
                task(102L, 2, 1, TeamTaskStatus.PENDING));
        when(runService.startRun(any())).thenReturn(run);
        when(taskService.listTasksByRun(RUN_ID)).thenReturn(existing);
        when(runService.sealRunWithResult(RUN_ID, WORKSPACE_ID))
                .thenReturn(new TeamRunService.SealResult(run, true));

        bridge.delegatePlan(team, PLAN_ID, "整体请求",
                List.of("第一步", "第二步"), List.of(List.of(), List.of(0)),
                List.of(WRITER_ID, ANALYST_ID), CONV);

        verify(taskService, never()).createTask(any());
        verify(runService).sealRunWithResult(RUN_ID, WORKSPACE_ID);
        verify(planningService).markPlanDelegated(PLAN_ID);
        verify(eventPublisher).publishEvent(new TeamTasksDelegatedEvent(TEAM_ID));
    }

    @Test
    @DisplayName("delegatePlan is transactional")
    void delegatePlanIsTransactional() throws NoSuchMethodException {
        assertNotNull(TeamPlanBridge.class.getMethod("delegatePlan", AgentTeamEntity.class,
                Long.class, String.class, List.class, List.class, List.class, String.class)
                .getAnnotation(Transactional.class));
    }

    // ==================== resume gate ====================

    private void parkedPlan() {
        PlanEntity plan = new PlanEntity();
        plan.setId(PLAN_ID);
        plan.setAgentId(String.valueOf(LEAD_ID));
        plan.setConversationId(CONV);
        plan.setStatus("delegated");
        plan.setGoal("整体请求");
        when(planningService.findDelegatedPlan(CONV)).thenReturn(plan);
        when(teamService.getTeamForAgent(LEAD_ID)).thenReturn(Optional.of(team));
        when(teamService.isLead(team, LEAD_ID)).thenReturn(true);
        SubPlanEntity sub0 = new SubPlanEntity();
        sub0.setStepIndex(0);
        sub0.setDescription("第一步");
        SubPlanEntity sub1 = new SubPlanEntity();
        sub1.setStepIndex(1);
        sub1.setDescription("第二步");
        when(planningService.getSubPlans(PLAN_ID)).thenReturn(List.of(sub0, sub1));
    }

    @Test
    @DisplayName("no parked plan yields None; in-flight boards yield a progress answer")
    void gateNoneAndInFlight() {
        when(planningService.findDelegatedPlan(CONV)).thenReturn(null);
        assertInstanceOf(TeamPlanBridge.None.class, bridge.checkParkedPlan(CONV));

        parkedPlan();
        when(taskService.listTasksByPlan(TEAM_ID, PLAN_ID)).thenReturn(List.of(
                task(101L, 1, 0, TeamTaskStatus.COMPLETED),
                task(102L, 2, 1, TeamTaskStatus.IN_PROGRESS)));

        TeamPlanBridge.ParkedPlanState state = bridge.checkParkedPlan(CONV);
        TeamPlanBridge.InFlight inFlight = assertInstanceOf(TeamPlanBridge.InFlight.class, state);
        assertTrue(inFlight.progressText().contains("in_progress"));
        verify(planningService, never()).updateSubPlanResult(any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("checkpoint status requests get a single-line deterministic response")
    void compactCheckpointProgress() {
        parkedPlan();
        TeamTaskEntity completed = task(101L, 46, 0, TeamTaskStatus.COMPLETED);
        TeamTaskEntity active = task(102L, 47, 1, TeamTaskStatus.IN_PROGRESS);
        active.setProgressPercent(80);
        when(taskService.listTasksByPlan(TEAM_ID, PLAN_ID)).thenReturn(List.of(completed, active));
        when(taskService.findCheckpointTracker(TEAM_ID)).thenReturn(Optional.of(active));

        TeamPlanBridge.InFlight state = assertInstanceOf(TeamPlanBridge.InFlight.class,
                bridge.checkParkedPlan(CONV,
                        "R100/100 最终检查点：仅用一行回复，并确认已连续完成100轮"));

        assertEquals("R100｜执行中 1/2｜#47 in_progress 80%（已完成第100轮检查点）"
                        + "｜证据 [checkpoint:R100] acknowledged",
                state.progressText());
        assertFalse(state.progressText().contains("\n"));
        verify(taskService).addCommentOnce(102L, TeamTaskService.AUTHOR_SYSTEM,
                "team-plan-bridge", TeamTaskService.COMMENT_NOTE,
                "[checkpoint:R100] acknowledged");
        assertEquals("R002", TeamPlanBridge.checkpointTagOf("R002/100 checkpoint"));
        assertNull(TeamPlanBridge.checkpointTagOf("R002 ordinary status"));
    }

    @Test
    @DisplayName("checkpoint fast path survives after the delegated plan has settled")
    void compactCheckpointUsesLatestTerminalRun() {
        when(planningService.findDelegatedPlan(CONV)).thenReturn(null);
        TeamRunEntity latest = new TeamRunEntity();
        latest.setId(RUN_ID);
        when(runService.findLatestConversationRun(CONV)).thenReturn(Optional.of(latest));
        TeamTaskEntity first = task(101L, 46, 0, TeamTaskStatus.COMPLETED);
        TeamTaskEntity tracker = task(102L, 47, 1, TeamTaskStatus.COMPLETED);
        tracker.setSubject("R001-R100 共享跟踪检查点");
        tracker.setProgressPercent(100);
        when(taskService.listTasksByRun(RUN_ID)).thenReturn(List.of(first, tracker));
        latest.setTeamId(TEAM_ID);
        TeamTaskEntity crossRunTracker = task(103L, 54, 2, TeamTaskStatus.COMPLETED);
        crossRunTracker.setSubject("R001-R100 共享跟踪检查点");
        when(taskService.findCheckpointTracker(TEAM_ID)).thenReturn(Optional.of(crossRunTracker));

        TeamPlanBridge.InFlight state = assertInstanceOf(TeamPlanBridge.InFlight.class,
                bridge.checkParkedPlan(CONV, "R047/100 checkpoint"));

        assertEquals("R047｜已完成 2/2｜#47 completed 100%"
                + "｜证据 [checkpoint:R047] acknowledged", state.progressText());
        verify(taskService).addCommentOnce(102L, TeamTaskService.AUTHOR_SYSTEM,
                "team-plan-bridge", TeamTaskService.COMMENT_NOTE,
                "[checkpoint:R047] acknowledged");
        verify(taskService, never()).addCommentOnce(eq(103L), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("the terminal checkpoint completes the current run tracker and releases dispatch")
    void terminalCheckpointCompletesCurrentTracker() {
        parkedPlan();
        TeamTaskEntity tracker = task(102L, 59, 0, TeamTaskStatus.IN_PROGRESS);
        tracker.setSubject("R001-R300 唯一共享跟踪条目");
        when(taskService.listTasksByPlan(TEAM_ID, PLAN_ID)).thenReturn(List.of(tracker));
        when(taskService.checkpointTerminalTag(tracker)).thenReturn("R300");
        when(taskService.completeTask(102L, null,
                "Checkpoint tracking completed at R300")).thenReturn(List.of());

        TeamPlanBridge.InFlight state = assertInstanceOf(TeamPlanBridge.InFlight.class,
                bridge.checkParkedPlan(CONV, "最终检查点 R300"));

        assertTrue(state.progressText().contains("[checkpoint:R300] acknowledged"));
        verify(taskService).addCommentOnce(102L, TeamTaskService.AUTHOR_SYSTEM,
                "team-plan-bridge", TeamTaskService.COMMENT_NOTE,
                "[checkpoint:R300] acknowledged");
        verify(taskService).completeTask(102L, null,
                "Checkpoint tracking completed at R300");
        verify(eventPublisher).publishEvent(new TeamTasksDelegatedEvent(TEAM_ID));
    }

    @Test
    @DisplayName("a settled board syncs the sub-plan mirror and returns summary-ready results")
    void gateSettled() {
        parkedPlan();
        TeamTaskEntity done = task(101L, 1, 0, TeamTaskStatus.COMPLETED);
        done.setResult("卖点已产出");
        done.setMetadata("{\"planId\":\"" + PLAN_ID + "\",\"stepIndex\":0,"
                + "\"deliverables\":[{\"name\":\"a.docx\",\"url\":\"/api/v1/files/generated/x\"}]}");
        TeamTaskEntity failed = task(102L, 2, 1, TeamTaskStatus.FAILED);
        failed.setReason("blocked: 缺输入");
        when(taskService.listTasksByPlan(TEAM_ID, PLAN_ID)).thenReturn(List.of(done, failed));
        when(taskService.listDeliverables(done)).thenReturn(List.of(
                new TeamTaskService.Deliverable("a.docx", "/api/v1/files/generated/x", null)));

        TeamPlanBridge.ParkedPlanState state = bridge.checkParkedPlan(CONV);
        TeamPlanBridge.Settled settled = assertInstanceOf(TeamPlanBridge.Settled.class, state);

        assertEquals(PLAN_ID, settled.planId());
        assertEquals("整体请求", settled.goal());
        assertEquals(List.of("第一步", "第二步"), settled.steps());
        assertTrue(settled.completedResults().get(0).contains("步骤1结果：卖点已产出"));
        assertTrue(settled.completedResults().get(0).contains("a.docx → /api/v1/files/generated/x"));
        assertTrue(settled.completedResults().get(1).contains("步骤2未完成"));
        verify(planningService).updateSubPlanResult(PLAN_ID, 0, "卖点已产出");
        verify(planningService).updateSubPlanFailure(eq(PLAN_ID), eq(1), anyString());
        verify(runService).markFinalized(eq(RUN_ID), eq(WORKSPACE_ID), contains("执行摘要"));
    }

    @Test
    @DisplayName("a vanished board fails the plan instead of wedging the conversation")
    void gateVanishedBoardFailsPlan() {
        parkedPlan();
        when(taskService.listTasksByPlan(TEAM_ID, PLAN_ID)).thenReturn(List.of());

        assertInstanceOf(TeamPlanBridge.None.class, bridge.checkParkedPlan(CONV));
        verify(planningService).markPlanFailed(eq(PLAN_ID), anyString());
    }
}
