package vip.mate.team.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.planning.model.PlanEntity;
import vip.mate.planning.service.PlanningService;
import vip.mate.team.event.TeamTasksDelegatedEvent;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import vip.mate.team.model.TeamRole;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamRunCreateCommand;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges the Plan-Execute graph onto the team task board. When a plan's
 * lead-of-team owner assigns every step to a team member, the steps become
 * board tasks (dependencies mapped to blockedBy), the plan parks in the
 * "delegated" status and the lead's turn ends — execution then runs through
 * the board's dispatch/announce machinery instead of the serial per-step
 * delegation pipeline. Any later inbound message resumes through
 * {@link #checkParkedPlan}: settled boards feed the plan summary, in-flight
 * boards produce a progress answer.
 *
 * Deliberately does NOT depend on the dispatch service (bean cycle through
 * the agent graph builder) — a {@link TeamTasksDelegatedEvent} triggers the
 * immediate sweep instead.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamPlanBridge {

    /** Task subject cap; the full step text rides in the description. */
    static final int SUBJECT_MAX_CHARS = 120;
    private static final Pattern CHECKPOINT_TAG = Pattern.compile("(?i)(?:^|\\b)(R\\d{3})(?:\\b|/)");
    private static final Pattern DELIVERABLE_REQUEST = Pattern.compile(
            "(?i)(交付物|生成.{0,8}(文件|文档)|文档成稿|报告成稿|"
                    + "docx|xlsx|pptx|pdf|deliverable|document|spreadsheet|presentation)");

    private final TeamService teamService;
    private final TeamTaskService taskService;
    private final TeamRunService runService;
    private final PlanningService planningService;
    private final AgentMapper agentMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== triage support ====================

    /** The team this agent leads, if any. */
    public Optional<AgentTeamEntity> leadTeam(Long agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        return teamService.getTeamForAgent(agentId)
                .filter(team -> teamService.isLead(team, agentId));
    }

    /** Assignable members (lead excluded), for the planner's roster message. */
    public List<AgentEntity> roster(AgentTeamEntity team) {
        List<AgentEntity> members = new ArrayList<>();
        for (AgentTeamMemberEntity member : teamService.listMembers(team.getId())) {
            if (TeamRole.LEAD.equals(member.getRole())) {
                continue;
            }
            AgentEntity agent = agentMapper.selectById(member.getAgentId());
            if (agent != null) {
                members.add(agent);
            }
        }
        return members;
    }

    /**
     * Map the planner's step_agents names onto team member ids. Returns null
     * unless EVERY step resolves to a member — the hand-off is all-or-nothing
     * (mixed local/board plans are out of scope), and a null keeps the plan
     * on the legacy serial pipeline.
     */
    public List<Long> resolveMembers(AgentTeamEntity team, List<String> steps,
                                     List<String> stepAgents) {
        if (steps == null || steps.isEmpty() || stepAgents == null) {
            return null;
        }
        Map<String, Long> byName = new HashMap<>();
        for (AgentEntity member : roster(team)) {
            if (member.getName() != null) {
                byName.put(member.getName().trim().toLowerCase(), member.getId());
            }
        }
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            String name = i < stepAgents.size() ? stepAgents.get(i) : null;
            Long id = (name == null || name.isBlank()) ? null
                    : byName.get(name.trim().toLowerCase());
            if (id == null) {
                return null;
            }
            ids.add(id);
        }
        return ids;
    }

    /**
     * Detect enabled workspace agents explicitly named by the user but absent
     * from this team. Silently substituting another member violates the
     * requested roster and makes team runs look successful when a participant
     * never took part.
     */
    public List<String> namedAgentsOutsideRoster(AgentTeamEntity team, String goal,
                                                  List<AgentEntity> workspaceAgents) {
        if (goal == null || goal.isBlank() || workspaceAgents == null || workspaceAgents.isEmpty()) {
            return List.of();
        }
        Set<Long> memberIds = teamService.listMembers(team.getId()).stream()
                .map(AgentTeamMemberEntity::getAgentId)
                .collect(java.util.stream.Collectors.toSet());
        return workspaceAgents.stream()
                .filter(agent -> agent.getId() != null && !memberIds.contains(agent.getId()))
                .filter(agent -> agent.getName() != null && !agent.getName().isBlank())
                .filter(agent -> goal.contains(agent.getName()))
                .map(AgentEntity::getName)
                .distinct()
                .toList();
    }

    // ==================== hand-off ====================

    /**
     * Create one board task per step (dependencies → blockedBy), park the plan
     * as "delegated" and nudge the dispatcher. Returns the announcement text
     * the lead streams to the user before ending its turn.
     *
     * @param stepDeps per-step prerequisite step indices (0-based, each
     *                 referencing an earlier step); the caller guarantees
     *                 validity via its sequential-chain fallback
     */
    @Transactional
    public String delegatePlan(AgentTeamEntity team, Long planId, String goal,
                               List<String> steps, List<List<Integer>> stepDeps,
                               List<Long> memberIds, String leadConversationId) {
        TeamRunEntity run = runService.startRun(TeamRunCreateCommand.builder()
                .teamId(team.getId())
                .workspaceId(team.getWorkspaceId())
                .leadAgentId(team.getLeadAgentId())
                .leadConversationId(leadConversationId)
                .originMessageId(-Math.abs(planId))
                .title(goal)
                .objective(goal)
                .metadata(new JSONObject().set("planId", String.valueOf(planId)).toString())
                .build());
        List<TeamTaskEntity> existing = taskService.listTasksByRun(run.getId());
        if (!existing.isEmpty()) {
            if (TeamRunStatus.PLANNING.equals(run.getStatus())) {
                sealAndPublish(team, planId, run);
            }
            return buildAnnouncement(existing, stepDeps);
        }
        if (!TeamRunStatus.PLANNING.equals(run.getStatus())) {
            throw new IllegalStateException("sealed team run has no tasks: " + run.getId());
        }
        List<TeamTaskEntity> created = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            List<Long> blockedBy = new ArrayList<>();
            for (Integer depIndex : stepDeps.get(i)) {
                blockedBy.add(created.get(depIndex).getId());
            }
            TeamTaskEntity task = taskService.createTask(TeamTaskCreateCommand.builder()
                    .teamId(team.getId())
                    .runId(run.getId())
                    .subject(subjectOf(step))
                    .description(step + "\n\n[Plan context]\nOverall request: " + goal)
                    .assigneeAgentId(memberIds.get(i))
                    .createdByAgentId(team.getLeadAgentId())
                    .blockedBy(blockedBy.isEmpty() ? null : blockedBy)
                    .leadConversationId(leadConversationId)
                    .channel("plan")
                    .metadata(new JSONObject()
                            .set("planId", String.valueOf(planId))
                            .set("stepIndex", i)
                            .set("deliverableRequired", DELIVERABLE_REQUEST.matcher(step).find())
                            .toString())
                    .build());
            created.add(task);
        }
        sealAndPublish(team, planId, run);
        log.info("Plan {} delegated to team {} board as {} task(s)", planId, team.getId(),
                created.size());
        return buildAnnouncement(created, stepDeps);
    }

    private void sealAndPublish(AgentTeamEntity team, Long planId, TeamRunEntity run) {
        TeamRunService.SealResult seal = runService.sealRunWithResult(
                run.getId(), team.getWorkspaceId());
        planningService.markPlanDelegated(planId);
        if (seal.transitioned()) {
            eventPublisher.publishEvent(new TeamTasksDelegatedEvent(team.getId()));
        }
    }

    // ==================== resume gate ====================

    /** Outcome of the parked-plan check on an inbound message. */
    public sealed interface ParkedPlanState permits None, Settled, InFlight {
    }

    public record None() implements ParkedPlanState {
    }

    /** All board tasks terminal — resume into the plan summary. */
    public record Settled(Long planId, String goal, List<String> steps,
                          List<String> completedResults) implements ParkedPlanState {
    }

    /** Board still working — answer with live progress, stay parked. */
    public record InFlight(String progressText) implements ParkedPlanState {
    }

    /**
     * Inspect the conversation's parked plan, if any. Settled boards sync the
     * sub-plan mirror and return the step results (with deliverable links) in
     * the summary node's expected format; in-flight boards return a rendered
     * progress snapshot.
     */
    public ParkedPlanState checkParkedPlan(String conversationId) {
        return checkParkedPlan(conversationId, null);
    }

    /**
     * Variant that can honor a user's compact checkpoint response contract.
     * Normal status questions retain the detailed board snapshot.
     */
    public ParkedPlanState checkParkedPlan(String conversationId, String currentMessage) {
        String checkpointTag = checkpointTagOf(currentMessage);
        PlanEntity plan = planningService.findDelegatedPlan(conversationId);
        if (plan == null) {
            if (checkpointTag != null) {
                Optional<TeamRunEntity> latest = runService.findLatestConversationRun(conversationId);
                if (latest.isPresent()) {
                    List<TeamTaskEntity> tasks = taskService.listTasksByRun(latest.get().getId());
                    if (!tasks.isEmpty()) {
                        recordCheckpointEvidence(latest.get().getTeamId(), tasks, checkpointTag);
                        tasks = taskService.listTasksByRun(latest.get().getId());
                        return new InFlight(buildCheckpointText(tasks, checkpointTag));
                    }
                }
            }
            return new None();
        }
        Optional<AgentTeamEntity> teamOpt = leadTeam(parseAgentId(plan.getAgentId()));
        if (teamOpt.isEmpty()) {
            // Team dissolved or lead reassigned while parked — nothing to wait
            // for; fail the plan so the conversation is not wedged forever.
            planningService.markPlanFailed(plan.getId(), "team no longer available");
            return new None();
        }
        List<TeamTaskEntity> tasks = taskService.listTasksByPlan(teamOpt.get().getId(), plan.getId());
        if (tasks.isEmpty()) {
            planningService.markPlanFailed(plan.getId(), "board tasks vanished");
            return new None();
        }
        boolean allTerminal = tasks.stream()
                .allMatch(task -> TeamTaskStatus.isTerminal(task.getStatus()));
        List<String> steps = planningService.getSubPlans(plan.getId()).stream()
                .map(sub -> sub.getDescription())
                .toList();
        if (checkpointTag != null) {
            recordCheckpointEvidence(teamOpt.get().getId(), tasks, checkpointTag);
            tasks = taskService.listTasksByPlan(teamOpt.get().getId(), plan.getId());
            return new InFlight(buildCheckpointText(tasks, checkpointTag));
        }
        if (!allTerminal) {
            return new InFlight(buildProgressText(tasks, currentMessage));
        }
        List<String> results = settle(plan.getId(), tasks);
        finalizeRunWithFallback(teamOpt.get().getWorkspaceId(), tasks, results);
        return new Settled(plan.getId(), plan.getGoal(), steps, results);
    }

    /**
     * The lead wake-up is the completion boundary for a delegated run. Do not
     * leave the run in FINALIZING when the later LLM summary call fails.
     */
    private void finalizeRunWithFallback(Long workspaceId, List<TeamTaskEntity> tasks,
                                         List<String> results) {
        Long runId = tasks.stream()
                .map(TeamTaskEntity::getRunId)
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
        if (runId == null) {
            return;
        }
        String fallback = "执行摘要（汇总模型不可用，以下为步骤原始结果）：\n"
                + String.join("\n", results);
        try {
            runService.markFinalized(runId, workspaceId, fallback);
        } catch (IllegalStateException error) {
            // A concurrent projector may still be moving the run to FINALIZING.
            // The next lead wake-up can retry; never wedge the conversation here.
            log.warn("Unable to finalize settled team run {}: {}", runId, error.getMessage());
        }
    }

    /** Sync the sub-plan mirror from terminal tasks and render step results. */
    private List<String> settle(Long planId, List<TeamTaskEntity> tasks) {
        List<String> results = new ArrayList<>();
        for (TeamTaskEntity task : tasks) {
            int stepIndex = stepIndexOf(task);
            StringBuilder line = new StringBuilder();
            if (TeamTaskStatus.COMPLETED.equals(task.getStatus())) {
                planningService.updateSubPlanResult(planId, stepIndex,
                        task.getResult() == null ? "" : task.getResult());
                line.append(String.format("步骤%d结果：%s", stepIndex + 1,
                        task.getResult() == null ? "(无输出)" : task.getResult()));
            } else {
                String reason = task.getReason() == null ? task.getStatus() : task.getReason();
                planningService.updateSubPlanFailure(planId, stepIndex, reason);
                line.append(String.format("步骤%d未完成（%s）：%s", stepIndex + 1,
                        task.getStatus(), reason));
            }
            for (TeamTaskService.Deliverable file : taskService.listDeliverables(task)) {
                line.append("\n交付物：").append(file.name()).append(" → ").append(file.url());
            }
            results.add(line.toString());
        }
        return results;
    }

    // ==================== rendering ====================

    private String buildAnnouncement(List<TeamTaskEntity> tasks, List<List<Integer>> stepDeps) {
        StringBuilder sb = new StringBuilder("已将计划分派到团队任务板并行执行：\n");
        for (int i = 0; i < tasks.size(); i++) {
            TeamTaskEntity task = tasks.get(i);
            sb.append("- #").append(task.getTaskNumber()).append(' ')
                    .append(task.getSubject())
                    .append("（").append(agentName(task.getAssigneeAgentId())).append("）");
            if (!stepDeps.get(i).isEmpty()) {
                sb.append(" — 前置：");
                for (Integer depIndex : stepDeps.get(i)) {
                    sb.append('#').append(tasks.get(depIndex).getTaskNumber()).append(' ');
                }
            }
            sb.append('\n');
        }
        sb.append("成员完成后我会汇总结果给你。");
        return sb.toString();
    }

    private String buildProgressText(List<TeamTaskEntity> tasks, String currentMessage) {
        String checkpointTag = checkpointTagOf(currentMessage);
        if (checkpointTag != null) {
            return buildCheckpointText(tasks, checkpointTag);
        }
        StringBuilder sb = new StringBuilder("计划仍在团队任务板上执行中：\n");
        for (TeamTaskEntity task : tasks) {
            sb.append("- #").append(task.getTaskNumber()).append(' ')
                    .append(task.getSubject())
                    .append("：").append(task.getStatus());
            if (task.getProgressPercent() != null
                    && TeamTaskStatus.IN_PROGRESS.equals(task.getStatus())) {
                sb.append("（").append(task.getProgressPercent()).append('%');
                if (task.getProgressStep() != null) {
                    sb.append(" — ").append(task.getProgressStep());
                }
                sb.append('）');
            }
            sb.append('\n');
        }
        sb.append("全部完成后我会汇总；如需调整可在团队任务板上操作。");
        return sb.toString();
    }

    private String buildCheckpointText(List<TeamTaskEntity> tasks, String checkpointTag) {
        long completed = tasks.stream()
                .filter(task -> TeamTaskStatus.COMPLETED.equals(task.getStatus()))
                .count();
        boolean allTerminal = tasks.stream().allMatch(task -> TeamTaskStatus.isTerminal(task.getStatus()));
        TeamTaskEntity focus = tasks.stream()
                .filter(task -> !TeamTaskStatus.isTerminal(task.getStatus()))
                .findFirst()
                .orElse(tasks.get(tasks.size() - 1));
        StringBuilder compact = new StringBuilder(checkpointTag)
                .append(allTerminal ? "｜已完成 " : "｜执行中 ")
                .append(completed).append('/').append(tasks.size())
                .append("｜#").append(focus.getTaskNumber()).append(' ')
                .append(focus.getStatus());
        if (focus.getProgressPercent() != null) {
            compact.append(' ').append(focus.getProgressPercent()).append('%');
        }
        if ("R100".equalsIgnoreCase(checkpointTag)) {
            compact.append("（已完成第100轮检查点）");
        }
        compact.append("｜证据 [checkpoint:").append(checkpointTag).append("] acknowledged");
        return compact.toString();
    }

    private void recordCheckpointEvidence(Long teamId, List<TeamTaskEntity> tasks,
                                          String checkpointTag) {
        TeamTaskEntity tracker = tasks.stream()
                .filter(task -> taskService.checkpointTerminalTag(task) != null)
                .findFirst()
                .orElseGet(() -> tasks.stream()
                        .filter(this::isCheckpointTracker)
                        .findFirst()
                        .orElseGet(() -> taskService.findCheckpointTracker(teamId)
                                .orElse(tasks.get(tasks.size() - 1))));
        String content = "[checkpoint:" + checkpointTag + "] acknowledged";
        taskService.addCommentOnce(tracker.getId(), TeamTaskService.AUTHOR_SYSTEM,
                "team-plan-bridge", TeamTaskService.COMMENT_NOTE, content);
        String terminalTag = taskService.checkpointTerminalTag(tracker);
        if (terminalTag != null && TeamTaskStatus.IN_PROGRESS.equals(tracker.getStatus())) {
            int current = Integer.parseInt(checkpointTag.substring(1));
            int terminal = Integer.parseInt(terminalTag.substring(1));
            int percent = terminal <= 0 ? 1
                    : Math.min(99, Math.max(1, current * 100 / terminal));
            if (checkpointTag.equalsIgnoreCase(terminalTag)) {
                taskService.completeTask(tracker.getId(), null,
                        "Checkpoint tracking completed at " + checkpointTag);
                eventPublisher.publishEvent(new TeamTasksDelegatedEvent(teamId));
            } else {
                taskService.updateProgress(tracker.getId(), null, percent,
                        checkpointTag + "/" + terminalTag + " acknowledged");
            }
        }
    }

    private boolean isCheckpointTracker(TeamTaskEntity task) {
        if (taskService.checkpointTerminalTag(task) != null) {
            return true;
        }
        String text = task.getSubject() == null ? "" : task.getSubject();
        String lower = text.toLowerCase();
        return text.contains("检查点") || text.contains("共享跟踪")
                || lower.contains("checkpoint") || lower.contains("r001-r100");
    }

    static String checkpointTagOf(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String lower = message.toLowerCase();
        if (!message.contains("检查点") && !lower.contains("checkpoint")) {
            return null;
        }
        Matcher matcher = CHECKPOINT_TAG.matcher(message);
        return matcher.find() ? matcher.group(1).toUpperCase() : null;
    }

    // ==================== helpers ====================

    private static String subjectOf(String step) {
        String firstLine = step.strip().lines().findFirst().orElse(step.strip());
        return firstLine.length() <= SUBJECT_MAX_CHARS ? firstLine
                : firstLine.substring(0, SUBJECT_MAX_CHARS);
    }

    private static int stepIndexOf(TeamTaskEntity task) {
        try {
            return JSONUtil.parseObj(task.getMetadata()).getInt("stepIndex", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static Long parseAgentId(String agentId) {
        try {
            return Long.valueOf(agentId);
        } catch (Exception e) {
            return null;
        }
    }

    private String agentName(Long agentId) {
        AgentEntity agent = agentId == null ? null : agentMapper.selectById(agentId);
        return agent != null && agent.getName() != null ? agent.getName()
                : String.valueOf(agentId);
    }
}
