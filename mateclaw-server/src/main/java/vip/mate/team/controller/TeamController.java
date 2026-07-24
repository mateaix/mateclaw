package vip.mate.team.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.common.result.R;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import vip.mate.team.model.TeamTaskCommentEntity;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.service.TeamAnnounceService;
import vip.mate.team.service.TeamDispatchService;
import vip.mate.team.service.TeamService;
import vip.mate.team.service.TeamTaskService;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final TeamDispatchService dispatchService;
    private final TeamAnnounceService announceService;
    private final AgentMapper agentMapper;

    // ==================== team CRUD ====================

    @Operation(summary = "团队列表")
    @GetMapping
    public R<List<TeamVO>> list() {
        return R.ok(teamService.listTeams().stream().map(this::toVO).toList());
    }

    @Operation(summary = "团队详情（含成员）")
    @GetMapping("/{id}")
    public R<TeamDetailVO> get(@PathVariable Long id) {
        AgentTeamEntity team = teamService.getTeam(id);
        if (team == null) {
            return R.fail("team not found");
        }
        List<MemberVO> members = teamService.listMembers(id).stream()
                .map(m -> {
                    AgentEntity agent = agentMapper.selectById(m.getAgentId());
                    return new MemberVO(m.getAgentId(),
                            agent != null && agent.getName() != null ? agent.getName()
                                    : String.valueOf(m.getAgentId()),
                            m.getRole(),
                            agent != null ? agent.getIcon() : null);
                })
                .toList();
        return R.ok(new TeamDetailVO(toVO(team), members));
    }

    @Operation(summary = "创建团队")
    @PostMapping
    public R<TeamVO> create(@RequestBody CreateTeamRequest req, Principal principal) {
        AgentTeamEntity team = teamService.createTeam(req.getName(), req.getDescription(),
                req.getLeadAgentId(), req.getMemberAgentIds(),
                principal != null ? principal.getName() : "admin");
        return R.ok(toVO(team));
    }

    @Operation(summary = "更新团队")
    @PutMapping("/{id}")
    public R<TeamVO> update(@PathVariable Long id, @RequestBody UpdateTeamRequest req) {
        return R.ok(toVO(teamService.updateTeam(id, req.getName(), req.getDescription(),
                req.getSettings())));
    }

    @Operation(summary = "删除团队")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        teamService.deleteTeam(id);
        return R.ok(null);
    }

    // ==================== membership ====================

    @Operation(summary = "添加成员")
    @PostMapping("/{id}/members")
    public R<Void> addMember(@PathVariable Long id, @RequestBody MemberRequest req) {
        teamService.addMember(id, req.getAgentId(), req.getRole());
        return R.ok(null);
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{id}/members/{agentId}")
    public R<Void> removeMember(@PathVariable Long id, @PathVariable Long agentId) {
        teamService.removeMember(id, agentId);
        return R.ok(null);
    }

    // ==================== task board ====================

    @Operation(summary = "任务板列表")
    @GetMapping("/{id}/tasks")
    public R<List<TaskVO>> listTasks(@PathVariable Long id,
                                     @RequestParam(required = false) List<String> status) {
        return R.ok(taskService.listTasks(id, status).stream().map(this::toTaskVO).toList());
    }

    @Operation(summary = "任务详情（含评论）")
    @GetMapping("/{id}/tasks/{taskId}")
    public R<TaskDetailVO> getTask(@PathVariable Long id, @PathVariable Long taskId) {
        TeamTaskEntity task = requireTask(id, taskId);
        return R.ok(new TaskDetailVO(toTaskVO(task), taskService.listComments(taskId)));
    }

    @Operation(summary = "手动创建任务")
    @PostMapping("/{id}/tasks")
    public R<TaskVO> createTask(@PathVariable Long id, @RequestBody CreateTaskRequest req,
                                Principal principal) {
        TeamTaskEntity task = taskService.createTask(TeamTaskCreateCommand.builder()
                .teamId(id)
                .subject(req.getSubject())
                .description(req.getDescription())
                .assigneeAgentId(req.getAssigneeAgentId())
                .priority(req.getPriority())
                .blockedBy(req.getBlockedBy())
                .requireApproval(Boolean.TRUE.equals(req.getRequireApproval()))
                .username(principal != null ? principal.getName() : null)
                .channel("dashboard")
                .build());
        if (TeamTaskStatus.PENDING.equals(task.getStatus())) {
            dispatchService.requestDispatch(id);
        }
        return R.ok(toTaskVO(task));
    }

    @Operation(summary = "批准 in_review 任务")
    @PostMapping("/{id}/tasks/{taskId}/approve")
    public R<TaskVO> approve(@PathVariable Long id, @PathVariable Long taskId) {
        requireTask(id, taskId);
        List<Long> released = taskService.approveTask(taskId);
        if (!released.isEmpty()) {
            dispatchService.requestDispatch(id);
        }
        return R.ok(toTaskVO(taskService.getTask(taskId)));
    }

    @Operation(summary = "驳回 in_review 任务")
    @PostMapping("/{id}/tasks/{taskId}/reject")
    public R<TaskVO> reject(@PathVariable Long id, @PathVariable Long taskId,
                            @RequestBody(required = false) ReasonRequest req) {
        requireTask(id, taskId);
        taskService.rejectTask(taskId, req == null ? null : req.getReason());
        TeamTaskEntity task = taskService.getTask(taskId);
        // The lead must hear about the rejection to re-plan or retry.
        announceService.announceTaskSettled(task);
        dispatchService.requestDispatch(id);
        return R.ok(toTaskVO(task));
    }

    @Operation(summary = "重试 failed/stale 任务")
    @PostMapping("/{id}/tasks/{taskId}/retry")
    public R<TaskVO> retry(@PathVariable Long id, @PathVariable Long taskId) {
        requireTask(id, taskId);
        if (!taskService.retryTask(taskId)) {
            return R.fail("only failed or stale tasks can be retried");
        }
        dispatchService.requestDispatch(id);
        return R.ok(toTaskVO(taskService.getTask(taskId)));
    }

    @Operation(summary = "取消任务")
    @PostMapping("/{id}/tasks/{taskId}/cancel")
    public R<TaskVO> cancel(@PathVariable Long id, @PathVariable Long taskId,
                            @RequestBody(required = false) ReasonRequest req) {
        requireTask(id, taskId);
        List<Long> released = taskService.cancelTask(taskId, req == null ? null : req.getReason());
        if (!released.isEmpty()) {
            dispatchService.requestDispatch(id);
        }
        return R.ok(toTaskVO(taskService.getTask(taskId)));
    }

    @Operation(summary = "添加评论")
    @PostMapping("/{id}/tasks/{taskId}/comments")
    public R<Void> comment(@PathVariable Long id, @PathVariable Long taskId,
                           @RequestBody CommentRequest req, Principal principal) {
        requireTask(id, taskId);
        taskService.addComment(taskId, TeamTaskService.AUTHOR_USER,
                principal != null ? principal.getName() : "admin",
                TeamTaskService.COMMENT_NOTE, req.getContent());
        return R.ok(null);
    }

    @Operation(summary = "任务状态统计（看板列头）")
    @GetMapping("/{id}/tasks/stats")
    public R<Map<String, Long>> taskStats(@PathVariable Long id) {
        return R.ok(taskService.listTasks(id, null).stream()
                .collect(Collectors.groupingBy(TeamTaskEntity::getStatus, Collectors.counting())));
    }

    // ==================== helpers / DTOs ====================

    private TeamTaskEntity requireTask(Long teamId, Long taskId) {
        TeamTaskEntity task = taskService.getTask(taskId);
        if (task == null || !task.getTeamId().equals(teamId)) {
            throw new IllegalArgumentException("task not found on this team's board");
        }
        return task;
    }

    private TeamVO toVO(AgentTeamEntity team) {
        long memberCount = teamService.listMembers(team.getId()).size();
        AgentEntity lead = agentMapper.selectById(team.getLeadAgentId());
        return new TeamVO(team,
                lead != null && lead.getName() != null ? lead.getName()
                        : String.valueOf(team.getLeadAgentId()),
                lead != null ? lead.getIcon() : null,
                memberCount);
    }

    private TaskVO toTaskVO(TeamTaskEntity task) {
        return new TaskVO(task,
                agentName(task.getAssigneeAgentId()),
                task.getOwnerAgentId() == null ? null : agentName(task.getOwnerAgentId()));
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

    public record TaskVO(TeamTaskEntity task, String assigneeName, String ownerName) {
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
