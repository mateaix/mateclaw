package vip.mate.planning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.planning.model.PlanEntity;
import vip.mate.planning.model.SubPlanEntity;
import vip.mate.planning.repository.PlanMapper;
import vip.mate.planning.repository.SubPlanMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

/**
 * 任务规划服务
 * 管理 Plan-and-Execute 模式下的计划和子任务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanningService {

    private final PlanMapper planMapper;
    private final SubPlanMapper subPlanMapper;

    /**
     * 创建执行计划（由 StateGraphPlanExecuteAgent 调用）
     */
    @Transactional
    public PlanEntity createPlan(String agentId, String goal, List<String> steps) {
        return createPlan(agentId, null, goal, steps);
    }

    /**
     * 创建执行计划，并绑定到产生它的对话/运行。
     * conversationId 可空（历史调用方），便于把计划归到某次运行，支撑跨员工/协同看板。
     */
    @Transactional
    public PlanEntity createPlan(String agentId, String conversationId, String goal, List<String> steps) {
        return createPlan(agentId, conversationId, goal, steps, null);
    }

    /**
     * 创建执行计划，并为每个步骤可选地指派专职子 agent。
     * stepAgentIds 与 steps 等长同序（按位置对应）；某位为 null 表示该步骤由父 agent 执行。
     * stepAgentIds 整体可空（无委派的历史调用方）。
     */
    @Transactional
    public PlanEntity createPlan(String agentId, String conversationId, String goal,
                                 List<String> steps, List<Long> stepAgentIds) {
        PlanEntity plan = new PlanEntity();
        plan.setAgentId(agentId);
        plan.setConversationId(conversationId);
        plan.setGoal(goal);
        // A freshly generated plan is queued, not yet running: it sits in the
        // board's "pending" column until its first step actually starts
        // (updateSubPlanStatus promotes the plan to "running" then). This makes
        // the board's pending column meaningful for queued / approval-gated
        // plans instead of being perpetually empty.
        plan.setStatus("pending");
        plan.setTotalSteps(steps.size());
        plan.setCompletedSteps(0);
        planMapper.insert(plan);

        IntStream.range(0, steps.size()).forEach(i -> {
            SubPlanEntity sub = new SubPlanEntity();
            sub.setPlanId(plan.getId());
            sub.setStepIndex(i);
            sub.setDescription(steps.get(i));
            sub.setStatus("pending");
            // Per-step delegation: only set when an assignment exists for this
            // index; otherwise the step runs with the parent agent.
            if (stepAgentIds != null && i < stepAgentIds.size()) {
                sub.setAssignedAgentId(stepAgentIds.get(i));
            }
            subPlanMapper.insert(sub);
        });

        log.info("Created plan {} with {} steps for agent {}", plan.getId(), steps.size(), agentId);
        return plan;
    }

    /**
     * 更新子计划状态
     */
    public void updateSubPlanStatus(Long planId, int stepIndex, String status) {
        SubPlanEntity sub = getSubPlan(planId, stepIndex);
        if (sub != null) {
            sub.setStatus(status);
            if ("running".equals(status)) {
                sub.setStartTime(LocalDateTime.now());
                // Promote the parent plan out of the "pending" (queued) column
                // the moment its first step actually starts executing.
                PlanEntity plan = planMapper.selectById(planId);
                if (plan != null && "pending".equals(plan.getStatus())) {
                    plan.setStatus("running");
                    planMapper.updateById(plan);
                }
            }
            subPlanMapper.updateById(sub);
        }
    }

    /**
     * 更新子计划执行结果
     */
    public void updateSubPlanResult(Long planId, int stepIndex, String result) {
        SubPlanEntity sub = getSubPlan(planId, stepIndex);
        if (sub != null) {
            sub.setResult(result);
            sub.setStatus("completed");
            sub.setEndTime(LocalDateTime.now());
            subPlanMapper.updateById(sub);

            // 更新主计划完成步骤数
            PlanEntity plan = planMapper.selectById(planId);
            if (plan != null) {
                plan.setCompletedSteps(plan.getCompletedSteps() + 1);
                planMapper.updateById(plan);
            }
        }
    }

    /**
     * 完成计划
     */
    public void completePlan(Long planId, String summary) {
        PlanEntity plan = planMapper.selectById(planId);
        if (plan != null) {
            plan.setStatus("completed");
            plan.setSummary(summary);
            plan.setEndTime(LocalDateTime.now());
            planMapper.updateById(plan);
        }
    }

    /**
     * 获取 Agent 的计划列表
     */
    public List<PlanEntity> listPlansByAgent(String agentId) {
        return planMapper.selectList(new LambdaQueryWrapper<PlanEntity>()
                .eq(PlanEntity::getAgentId, agentId)
                .orderByDesc(PlanEntity::getCreateTime));
    }

    /**
     * 跨员工获取最近的计划列表（用于团队/泳道看板）。
     * 按创建时间倒序，limit 兜底防止全表拉取。
     */
    public List<PlanEntity> listRecentPlans(int limit) {
        int capped = limit <= 0 ? 100 : Math.min(limit, 500);
        return planMapper.selectList(new LambdaQueryWrapper<PlanEntity>()
                .orderByDesc(PlanEntity::getCreateTime)
                .last("LIMIT " + capped));
    }

    /**
     * 获取计划详情（含子计划）
     */
    public PlanEntity getPlanWithSteps(Long planId) {
        PlanEntity plan = planMapper.selectById(planId);
        if (plan != null) {
            List<SubPlanEntity> steps = subPlanMapper.selectList(
                    new LambdaQueryWrapper<SubPlanEntity>()
                            .eq(SubPlanEntity::getPlanId, planId)
                            .orderByAsc(SubPlanEntity::getStepIndex));
            plan.setSteps(steps);
        }
        return plan;
    }

    /**
     * 获取子计划
     */
    public List<SubPlanEntity> getSubPlans(Long planId) {
        return subPlanMapper.selectList(new LambdaQueryWrapper<SubPlanEntity>()
                .eq(SubPlanEntity::getPlanId, planId)
                .orderByAsc(SubPlanEntity::getStepIndex));
    }

    /**
     * The agent delegated to run a given step, or {@code null} when the step
     * runs with the parent (plan) agent. Read by the executor to route a step to
     * its specialist agent. Sourced from the DB (not graph state) so it survives
     * replay / approval-resume.
     */
    public Long getStepAssignedAgent(Long planId, int stepIndex) {
        SubPlanEntity sub = getSubPlan(planId, stepIndex);
        return sub != null ? sub.getAssignedAgentId() : null;
    }

    /**
     * 标记计划失败
     */
    public void markPlanFailed(Long planId, String reason) {
        PlanEntity plan = planMapper.selectById(planId);
        if (plan != null) {
            plan.setStatus("failed");
            plan.setSummary(reason);
            plan.setEndTime(LocalDateTime.now());
            planMapper.updateById(plan);
        }
    }

    /**
     * 更新子计划失败状态
     */
    public void updateSubPlanFailure(Long planId, int stepIndex, String error) {
        SubPlanEntity sub = getSubPlan(planId, stepIndex);
        if (sub != null) {
            sub.setStatus("failed");
            sub.setResult(error);
            sub.setEndTime(LocalDateTime.now());
            subPlanMapper.updateById(sub);
        }
    }

    /**
     * 审批 replay 上下文：找到最近一条 running 且含 awaiting_approval 步骤的计划，
     * 返回恢复图执行所需的全部状态。
     * <p>
     * 按 conversationId 过滤，防止并发会话误取兄弟会话的计划。
     */
    public PlanResumeContext findAwaitingApprovalContext(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            log.warn("[PlanningService] findAwaitingApprovalContext called without conversationId, " +
                    "risk of cross-conversation plan pickup in concurrent scenarios");
        }
        LambdaQueryWrapper<PlanEntity> wrapper = new LambdaQueryWrapper<PlanEntity>()
                .eq(PlanEntity::getStatus, "running")
                .orderByDesc(PlanEntity::getCreateTime)
                .last("LIMIT 1");
        if (conversationId != null && !conversationId.isBlank()) {
            wrapper.eq(PlanEntity::getConversationId, conversationId);
        }
        PlanEntity plan = planMapper.selectOne(wrapper);
        if (plan == null) return null;

        List<SubPlanEntity> subPlans = subPlanMapper.selectList(
                new LambdaQueryWrapper<SubPlanEntity>()
                        .eq(SubPlanEntity::getPlanId, plan.getId())
                        .orderByAsc(SubPlanEntity::getStepIndex));

        int awaitingIndex = subPlans.stream()
                .filter(s -> "awaiting_approval".equals(s.getStatus()))
                .mapToInt(SubPlanEntity::getStepIndex)
                .findFirst()
                .orElse(-1);
        if (awaitingIndex < 0) return null;

        List<String> steps = subPlans.stream()
                .map(SubPlanEntity::getDescription)
                .collect(Collectors.toList());

        List<String> completedResults = subPlans.stream()
                .filter(s -> "completed".equals(s.getStatus()))
                .map(s -> String.format("步骤%d结果：%s", s.getStepIndex() + 1, s.getResult()))
                .collect(Collectors.toList());

        log.info("[PlanningService] Found awaiting-approval context: planId={}, steps={}, awaitingStep={}",
                plan.getId(), steps.size(), awaitingIndex);
        return new PlanResumeContext(plan.getId(), steps, awaitingIndex, completedResults);
    }

    /** replay 恢复上下文 DTO */
    public record PlanResumeContext(Long planId, List<String> steps, int awaitingStepIndex,
                                    List<String> completedResults) {}

    private SubPlanEntity getSubPlan(Long planId, int stepIndex) {
        return subPlanMapper.selectOne(new LambdaQueryWrapper<SubPlanEntity>()
                .eq(SubPlanEntity::getPlanId, planId)
                .eq(SubPlanEntity::getStepIndex, stepIndex));
    }
}
