package vip.mate.team.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.common.result.R;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.team.model.TeamTaskCommentEntity;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskEventEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.service.TeamAnnounceService;
import vip.mate.team.service.TeamDispatchService;
import vip.mate.team.service.TeamEventChannel;
import vip.mate.team.service.TeamManualTaskService;
import vip.mate.team.service.TeamService;
import vip.mate.team.service.TeamTaskService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/**
 * Admin REST surface for agent teams: team/membership CRUD, the shared task
 * board, and the human-in-the-loop approve / reject / retry actions.
 *
 * All Long ids serialize as JSON strings (global Jackson config) and inbound
 * bodies accept both string and numeric forms, keeping Snowflake ids intact
 * across the JS frontend.
 *
 * @author MateClaw Team
 */
@Tag(name = "Agent 团队管理")
@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final TeamTaskService taskService;
    private final TeamManualTaskService manualTaskService;
    private final TeamDispatchService dispatchService;
    private final TeamAnnounceService announceService;
    private final TeamEventChannel eventChannel;
    private final AgentMapper agentMapper;

    // ==================== team CRUD ====================

    @Operation(summary = "团队列表")
    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<TeamVO>> list() {
        return R.ok(teamService.listTeams(currentWorkspaceId()).stream().map(this::toVO).toList());
    }

    @Operation(summary = "团队详情（含成员）")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("viewer")
    public R<TeamDetailVO> get(@PathVariable Long id) {
        AgentTeamEntity team = teamService.getTeam(id, currentWorkspaceId());
        if (team == null) {
            return R.fail("team not found");
        }
        List<MemberVO> members = teamService.listMembers(id).stream()
                .map(m -> toMemberVO(team, m))
                .filter(java.util.Objects::nonNull)
                .toList();
        return R.ok(new TeamDetailVO(toVO(team), members));
    }

    @Operation(summary = "创建团队")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<TeamVO> create(@RequestBody CreateTeamRequest req, Principal principal) {
        return guarded(() -> {
            AgentTeamEntity team = teamService.createTeam(currentWorkspaceId(), req.getName(), req.getDescription(),
                    req.getLeadAgentId(), req.getMemberAgentIds(),
                    principal != null ? principal.getName() : "admin");
            return R.ok(toVO(team));
        });
    }

    @Operation(summary = "更新团队")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<TeamVO> update(@PathVariable Long id, @RequestBody UpdateTeamRequest req) {
        return guarded(() -> R.ok(toVO(teamService.updateTeam(id, currentWorkspaceId(), req.getName(),
                req.getDescription(), req.getSettings()))));
    }

    @Operation(summary = "删除团队")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> delete(@PathVariable Long id) {
        return guarded(() -> {
            teamService.deleteTeam(id, currentWorkspaceId());
            return R.ok(null);
        });
    }

    // ==================== membership ====================

    @Operation(summary = "添加成员")
    @PostMapping("/{id}/members")
    @RequireWorkspaceRole("admin")
    public R<Void> addMember(@PathVariable Long id, @RequestBody MemberRequest req) {
        return guarded(() -> {
            teamService.addMember(id, currentWorkspaceId(), req.getAgentId(), req.getRole());
            return R.ok(null);
        });
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{id}/members/{agentId}")
    @RequireWorkspaceRole("admin")
    public R<Void> removeMember(@PathVariable Long id, @PathVariable Long agentId) {
        return guarded(() -> {
            teamService.removeMember(id, currentWorkspaceId(), agentId);
            return R.ok(null);
        });
    }

    // ==================== task board ====================

    @Operation(summary = "任务板列表")
    @GetMapping("/{id}/tasks")
    @RequireWorkspaceRole("viewer")
    public R<List<TaskVO>> listTasks(@PathVariable Long id,
                                     @RequestParam(required = false) List<String> status,
                                     @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) Integer offset,
                                     @RequestParam(required = false) Long runId) {
        return guarded(() -> {
            requireTeam(id);
            List<TeamTaskEntity> tasks = taskService.listTasks(id, status, limit, offset, runId);
            Set<Long> agentIds = tasks.stream()
                    .flatMap(task -> java.util.stream.Stream.of(
                            task.getAssigneeAgentId(), task.getOwnerAgentId()))
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<Long, AgentEntity> agents = agentIds.isEmpty()
                    ? Map.of()
                    : agentMapper.selectBatchIds(agentIds).stream()
                            .collect(Collectors.toMap(AgentEntity::getId, agent -> agent));
            return R.ok(tasks.stream().map(task -> toTaskVO(task, agents)).toList());
        });
    }

    @Operation(summary = "任务详情（含评论）")
    @GetMapping("/{id}/tasks/{taskId}")
    @RequireWorkspaceRole("viewer")
    public R<TaskDetailVO> getTask(@PathVariable Long id, @PathVariable Long taskId) {
        return guarded(() -> {
            requireTeam(id);
            TeamTaskEntity task = requireTask(id, taskId);
            return R.ok(new TaskDetailVO(toTaskVO(task), taskService.listComments(taskId)));
        });
    }

    @Operation(summary = "手动创建任务")
    @PostMapping("/{id}/tasks")
    @RequireWorkspaceRole("admin")
    public R<TaskVO> createTask(@PathVariable Long id, @RequestBody CreateTaskRequest req,
                                Principal principal) {
        return guarded(() -> {
            AgentTeamEntity team = requireTeam(id);
            TeamTaskEntity task = manualTaskService.createTask(team, TeamTaskCreateCommand.builder()
                    .teamId(id)
                    .runId(req.getRunId())
                    .subject(req.getSubject())
                    .description(req.getDescription())
                    .assigneeAgentId(req.getAssigneeAgentId())
                    .priority(req.getPriority())
                    .blockedBy(req.getBlockedBy())
                    .requireApproval(Boolean.TRUE.equals(req.getRequireApproval()))
                    .username(principal != null ? principal.getName() : null)
                    .channel("dashboard")
                    .build());
            eventChannel.publishTaskEvent(task, "team_task_created", Map.of());
            return R.ok(toTaskVO(task));
        });
    }

    @Operation(summary = "批准 in_review 任务")
    @PostMapping("/{id}/tasks/{taskId}/approve")
    @RequireWorkspaceRole("admin")
    public R<TaskVO> approve(@PathVariable Long id, @PathVariable Long taskId,
                             Principal principal) {
        return guarded(() -> {
            requireTeam(id);
            requireTask(id, taskId);
            List<Long> released = taskService.approveTask(taskId);
            recordUserEvent(id, taskId, TeamTaskEventEntity.APPROVED, principal, null);
            publishBoardEvent(taskId, "team_task_approved");
            if (!released.isEmpty()) {
                dispatchService.requestDispatch(id);
            }
            return R.ok(toTaskVO(taskService.getTask(taskId)));
        });
    }

    @Operation(summary = "驳回 in_review 任务")
    @PostMapping("/{id}/tasks/{taskId}/reject")
    @RequireWorkspaceRole("admin")
    public R<TaskVO> reject(@PathVariable Long id, @PathVariable Long taskId,
                            @RequestBody(required = false) ReasonRequest req,
                            Principal principal) {
        return guarded(() -> {
            requireTeam(id);
            requireTask(id, taskId);
            taskService.rejectTask(taskId, req == null ? null : req.getReason());
            recordUserEvent(id, taskId, TeamTaskEventEntity.REJECTED, principal,
                    req == null ? null : req.getReason());
            publishBoardEvent(taskId, "team_task_rejected");
            TeamTaskEntity task = taskService.getTask(taskId);
            // The lead must hear about the rejection to re-plan or retry.
            announceService.announceTaskSettled(task);
            dispatchService.requestDispatch(id);
            return R.ok(toTaskVO(task));
        });
    }

    @Operation(summary = "重试 failed/stale 任务")
    @PostMapping("/{id}/tasks/{taskId}/retry")
    @RequireWorkspaceRole("admin")
    public R<TaskVO> retry(@PathVariable Long id, @PathVariable Long taskId,
                           Principal principal) {
        return guarded(() -> {
            requireTeam(id);
            requireTask(id, taskId);
            if (!taskService.retryTask(taskId)) {
                return R.fail("only failed or stale tasks can be retried");
            }
            recordUserEvent(id, taskId, TeamTaskEventEntity.RETRIED, principal, null);
            publishBoardEvent(taskId, "team_task_retried");
            dispatchService.requestDispatch(id);
            return R.ok(toTaskVO(taskService.getTask(taskId)));
        });
    }

    @Operation(summary = "取消任务")
    @PostMapping("/{id}/tasks/{taskId}/cancel")
    @RequireWorkspaceRole("admin")
    public R<TaskVO> cancel(@PathVariable Long id, @PathVariable Long taskId,
                            @RequestBody(required = false) ReasonRequest req,
                            Principal principal) {
        return guarded(() -> {
            requireTeam(id);
            TeamTaskEntity task = requireTask(id, taskId);
            List<Long> released = taskService.cancelTask(taskId, req == null ? null : req.getReason());
            recordUserEvent(id, taskId, TeamTaskEventEntity.CANCELLED, principal,
                    req == null ? null : req.getReason());
            publishBoardEvent(taskId, "team_task_cancelled");
            // Stop the member run mid-flight instead of letting it burn to the end.
            dispatchService.interruptRun(task);
            if (!released.isEmpty()) {
                dispatchService.requestDispatch(id);
            }
            return R.ok(toTaskVO(taskService.getTask(taskId)));
        });
    }

    @Operation(summary = "任务时间线")
    @GetMapping("/{id}/tasks/{taskId}/events")
    @RequireWorkspaceRole("viewer")
    public R<List<TeamTaskEventEntity>> taskEvents(@PathVariable Long id, @PathVariable Long taskId) {
        return guarded(() -> {
            requireTeam(id);
            requireTask(id, taskId);
            return R.ok(taskService.listEvents(taskId));
        });
    }

    @Operation(summary = "团队事件流（SSE）")
    @GetMapping("/{id}/events")
    @RequireWorkspaceRole("viewer")
    public SseEmitter events(@PathVariable Long id,
                             @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        requireTeam(id);
        SseEmitter emitter = new SseEmitter(0L);
        // A fresh subscription is an activity ticker, not a transcript: skip
        // the ring-buffer replay (stale events would render as breaking news)
        // and deliver live events only. A reconnect carrying Last-Event-ID
        // keeps the resume-from-where-I-left semantics.
        eventChannel.attach(id, emitter, lastEventId == null ? Long.MAX_VALUE : lastEventId);
        return emitter;
    }

    private void recordUserEvent(Long teamId, Long taskId, String eventType,
                                 Principal principal, String detail) {
        taskService.recordEvent(teamId, taskId, eventType, TeamTaskService.AUTHOR_USER,
                principal != null ? principal.getName() : null, detail);
    }

    private void publishBoardEvent(Long taskId, String event) {
        eventChannel.publishTaskEvent(taskService.getTask(taskId), event, Map.of());
    }

    @Operation(summary = "添加评论")
    @PostMapping("/{id}/tasks/{taskId}/comments")
    @RequireWorkspaceRole("admin")
    public R<Void> comment(@PathVariable Long id, @PathVariable Long taskId,
                           @RequestBody CommentRequest req, Principal principal) {
        return guarded(() -> {
            requireTeam(id);
            requireTask(id, taskId);
            taskService.addComment(taskId, TeamTaskService.AUTHOR_USER,
                    principal != null ? principal.getName() : "admin",
                    TeamTaskService.COMMENT_NOTE, req.getContent());
            return R.ok(null);
        });
    }

    @Operation(summary = "任务状态统计（看板列头）")
    @GetMapping("/{id}/tasks/stats")
    @RequireWorkspaceRole("viewer")
    public R<Map<String, Long>> taskStats(@PathVariable Long id,
                                          @RequestParam(required = false) Long runId) {
        return guarded(() -> {
            requireTeam(id);
            return R.ok(taskService.countByStatus(id, runId));
        });
    }

    // ==================== helpers / DTOs ====================

    /**
     * Runs an endpoint body whose service layer reports validation verdicts
     * (unknown assignee, wrong task status, cross-team task id…) via
     * IllegalArgumentException / IllegalStateException. Those must reach the
     * client as readable text in the R envelope, not the catch-all 500 handler.
     */
    private <T> R<T> guarded(Supplier<R<T>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    private TeamTaskEntity requireTask(Long teamId, Long taskId) {
        TeamTaskEntity task = taskService.getTask(taskId);
        if (task == null || !task.getTeamId().equals(teamId)) {
            throw new IllegalArgumentException("task not found on this team's board");
        }
        return task;
    }

    private AgentTeamEntity requireTeam(Long teamId) {
        AgentTeamEntity team = teamService.getTeam(teamId, currentWorkspaceId());
        if (team == null) {
            throw new IllegalArgumentException("team not found: " + teamId);
        }
        return team;
    }

    private long currentWorkspaceId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String header = attrs.getRequest().getHeader("X-Workspace-Id");
            if (header != null && !header.isBlank()) {
                try {
                    return Long.parseLong(header.trim());
                } catch (NumberFormatException ignored) {
                    // Keep the same defaulting semantics as WorkspaceAccessInterceptor.
                }
            }
        }
        return 1L;
    }

    private TeamVO toVO(AgentTeamEntity team) {
        long memberCount = teamService.listMembers(team.getId()).stream()
                .filter(member -> memberBelongsToWorkspace(team, member))
                .count();
        AgentEntity lead = agentMapper.selectById(team.getLeadAgentId());
        return new TeamVO(team,
                lead != null && lead.getName() != null ? lead.getName()
                        : String.valueOf(team.getLeadAgentId()),
                lead != null ? lead.getIcon() : null,
                memberCount);
    }

    private MemberVO toMemberVO(AgentTeamEntity team, AgentTeamMemberEntity member) {
        AgentEntity agent = agentMapper.selectById(member.getAgentId());
        if (agent == null || !team.getWorkspaceId().equals(agent.getWorkspaceId())) {
            return null;
        }
        return new MemberVO(member.getAgentId(),
                agent.getName() != null ? agent.getName() : String.valueOf(member.getAgentId()),
                member.getRole(), agent.getIcon());
    }

    private boolean memberBelongsToWorkspace(AgentTeamEntity team, AgentTeamMemberEntity member) {
        AgentEntity agent = agentMapper.selectById(member.getAgentId());
        return agent != null && team.getWorkspaceId().equals(agent.getWorkspaceId());
    }

    private TaskVO toTaskVO(TeamTaskEntity task) {
        return new TaskVO(task,
                agentName(task.getAssigneeAgentId()),
                task.getOwnerAgentId() == null ? null : agentName(task.getOwnerAgentId()),
                task.getRunId());
    }

    private TaskVO toTaskVO(TeamTaskEntity task, Map<Long, AgentEntity> agents) {
        return new TaskVO(task,
                agentName(task.getAssigneeAgentId(), agents),
                agentName(task.getOwnerAgentId(), agents),
                task.getRunId());
    }

    private String agentName(Long agentId, Map<Long, AgentEntity> agents) {
        if (agentId == null) {
            return null;
        }
        AgentEntity agent = agents.get(agentId);
        return agent != null && agent.getName() != null ? agent.getName() : String.valueOf(agentId);
    }

    private String agentName(Long agentId) {
        if (agentId == null) {
            return null;
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        return agent != null && agent.getName() != null ? agent.getName() : String.valueOf(agentId);
    }

    public record TeamVO(AgentTeamEntity team, String leadName, String leadIcon, long memberCount) {
    }

    public record TeamDetailVO(TeamVO team, List<MemberVO> members) {
    }

    public record MemberVO(Long agentId, String name, String role, String icon) {
    }

    public record TaskVO(TeamTaskEntity task, String assigneeName, String ownerName, Long runId) {
    }

    public record TaskDetailVO(TaskVO task, List<TeamTaskCommentEntity> comments) {
    }

    @Data
    public static class CreateTeamRequest {
        private String name;
        private String description;
        private Long leadAgentId;
        private List<Long> memberAgentIds;
    }

    @Data
    public static class UpdateTeamRequest {
        private String name;
        private String description;
        private String settings;
    }

    @Data
    public static class MemberRequest {
        private Long agentId;
        private String role;
    }

    @Data
    public static class CreateTaskRequest {
        private Long runId;
        private String subject;
        private String description;
        private Long assigneeAgentId;
        private Integer priority;
        private List<Long> blockedBy;
        private Boolean requireApproval;
    }

    @Data
    public static class ReasonRequest {
        private String reason;
    }

    @Data
    public static class CommentRequest {
        private String content;
    }
}
