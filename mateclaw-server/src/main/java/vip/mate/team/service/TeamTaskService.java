package vip.mate.team.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskCommentEntity;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.repository.TeamTaskCommentMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Shared task board service. All status transitions are guarded conditional
 * updates (state checked in the WHERE clause, success judged by affected-row
 * count), so concurrent agents cannot double-claim or double-complete a task —
 * the database is the arbiter, no in-process locking involved.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamTaskService {

    /** Execution lease length; renewed by the runner while the member works. */
    static final int LOCK_MINUTES = 60;

    /** Dispatch attempts before the circuit breaker auto-fails the task. */
    static final int MAX_DISPATCHES = 3;

    public static final String AUTHOR_AGENT = "agent";
    public static final String AUTHOR_USER = "user";
    public static final String AUTHOR_SYSTEM = "system";

    public static final String COMMENT_NOTE = "note";
    public static final String COMMENT_BLOCKER = "blocker";

    private final TeamTaskMapper taskMapper;
    private final TeamTaskCommentMapper commentMapper;
    private final TeamService teamService;

    // ==================== creation ====================

    @Transactional
    public TeamTaskEntity createTask(TeamTaskCreateCommand cmd) {
        AgentTeamEntity team = teamService.getTeam(cmd.getTeamId());
        if (team == null || !TeamService.STATUS_ACTIVE.equals(team.getStatus())) {
            throw new IllegalArgumentException("team not found or not active: " + cmd.getTeamId());
        }
        if (cmd.getSubject() == null || cmd.getSubject().isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        Long assignee = cmd.getAssigneeAgentId();
        if (assignee == null) {
            throw new IllegalArgumentException(
                    "assignee is required — specify which team member should handle this task");
        }
        if (assignee.equals(team.getLeadAgentId())) {
            throw new IllegalArgumentException(
                    "cannot assign a task to the team lead; the lead orchestrates, members execute");
        }
        if (!teamService.isMember(cmd.getTeamId(), assignee)) {
            throw new IllegalArgumentException("assignee " + assignee + " is not a member of this team");
        }

        List<Long> blockers = cmd.getBlockedBy() == null ? List.of() : cmd.getBlockedBy();
        for (Long blockerId : blockers) {
            TeamTaskEntity blocker = taskMapper.selectById(blockerId);
            if (blocker == null || !blocker.getTeamId().equals(cmd.getTeamId())) {
                throw new IllegalArgumentException("blocking task not found in this team: " + blockerId);
            }
            if (TeamTaskStatus.isTerminal(blocker.getStatus())) {
                throw new IllegalArgumentException("blocking task " + blockerId
                        + " is already " + blocker.getStatus()
                        + "; pass its result in the description instead of blocking on it");
            }
        }

        TeamTaskEntity task = new TeamTaskEntity();
        task.setTeamId(cmd.getTeamId());
        task.setTaskNumber(teamService.nextTaskNumber(cmd.getTeamId()));
        task.setSubject(cmd.getSubject());
        task.setDescription(cmd.getDescription());
        task.setStatus(blockers.isEmpty() ? TeamTaskStatus.PENDING : TeamTaskStatus.BLOCKED);
        task.setPriority(cmd.getPriority() == null ? 0 : cmd.getPriority());
        task.setTaskType(cmd.getTaskType() == null ? "general" : cmd.getTaskType());
        task.setAssigneeAgentId(assignee);
        task.setCreatedByAgentId(cmd.getCreatedByAgentId());
        task.setBlockedBy(blockers.isEmpty() ? null : toJsonIdArray(blockers));
        task.setRequireApproval(cmd.isRequireApproval());
        task.setDispatchCount(0);
        task.setLeadConversationId(cmd.getLeadConversationId());
        task.setUsername(cmd.getUsername());
        task.setChannel(cmd.getChannel());
        task.setMetadata(cmd.getMetadata());
        taskMapper.insert(task);
        log.info("Team {} task #{} created ({}), assignee={} status={}",
                cmd.getTeamId(), task.getTaskNumber(), task.getId(), assignee, task.getStatus());
        return task;
    }

    // ==================== claim / assign ====================

    /**
     * Atomically claim a pending, unowned task. Exactly one caller wins; losers
     * get false. The WHERE clause is the mutex.
     */
    public boolean claimTask(Long taskId, Long agentId) {
        return taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING)
                .isNull(TeamTaskEntity::getOwnerAgentId)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .set(TeamTaskEntity::getOwnerAgentId, agentId)
                .set(TeamTaskEntity::getLockExpiresAt, newLease())) == 1;
    }

    /**
     * Assign a pending task to an agent (dispatch / admin path). Unlike claim,
     * this overrides a previously set owner but still requires pending status.
     */
    public boolean assignTask(Long taskId, Long agentId) {
        return taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .set(TeamTaskEntity::getOwnerAgentId, agentId)
                .set(TeamTaskEntity::getLockExpiresAt, newLease())) == 1;
    }

    /** Record the member conversation executing the task. */
    public void attachConversation(Long taskId, String conversationId) {
        taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .set(TeamTaskEntity::getConversationId, conversationId));
    }

    // ==================== completion lifecycle ====================

    /**
     * Complete a task with a result summary. A pending task is auto-claimed
     * first (single-call convenience; safe because the claim is atomic). When
     * the task requires approval it parks in in_review instead of completed.
     *
     * @return ids of dependent tasks released to pending by this completion
     */
    @Transactional
    public List<Long> completeTask(Long taskId, Long agentId, String result) {
        TeamTaskEntity task = requireTask(taskId);
        if (TeamTaskStatus.PENDING.equals(task.getStatus()) && agentId != null) {
            claimTask(taskId, agentId);
            task = requireTask(taskId);
        }
        if (agentId != null && task.getOwnerAgentId() != null && !agentId.equals(task.getOwnerAgentId())) {
            throw new IllegalStateException("task #" + task.getTaskNumber()
                    + " is owned by another agent; only the owner can complete it");
        }
        boolean toReview = Boolean.TRUE.equals(task.getRequireApproval());
        String target = toReview ? TeamTaskStatus.IN_REVIEW : TeamTaskStatus.COMPLETED;
        int rows = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .set(TeamTaskEntity::getStatus, target)
                .set(TeamTaskEntity::getResult, result)
                .set(TeamTaskEntity::getLockExpiresAt, null)
                .set(TeamTaskEntity::getProgressPercent, 100));
        if (rows != 1) {
            throw new IllegalStateException("task #" + task.getTaskNumber()
                    + " is " + task.getStatus() + " and cannot be completed");
        }
        return toReview ? List.of() : releaseDependents(task);
    }

    /** Human approval of an in_review task; releases dependents. */
    @Transactional
    public List<Long> approveTask(Long taskId) {
        TeamTaskEntity task = requireTask(taskId);
        int rows = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_REVIEW)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.COMPLETED));
        if (rows != 1) {
            throw new IllegalStateException("task #" + task.getTaskNumber() + " is not awaiting review");
        }
        return releaseDependents(task);
    }

    /** Human rejection of an in_review task; cancels it and releases dependents. */
    @Transactional
    public List<Long> rejectTask(Long taskId, String reason) {
        TeamTaskEntity task = requireTask(taskId);
        int rows = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_REVIEW)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.CANCELLED)
                .set(TeamTaskEntity::getReason, reason));
        if (rows != 1) {
            throw new IllegalStateException("task #" + task.getTaskNumber() + " is not awaiting review");
        }
        return releaseDependents(task);
    }

    /** Fail a task (blocker escalation, runner error, circuit breaker). Does NOT release dependents. */
    public boolean failTask(Long taskId, String reason) {
        return taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .in(TeamTaskEntity::getStatus,
                        TeamTaskStatus.PENDING, TeamTaskStatus.IN_PROGRESS, TeamTaskStatus.STALE)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.FAILED)
                .set(TeamTaskEntity::getReason, reason)
                .set(TeamTaskEntity::getLockExpiresAt, null)) == 1;
    }

    /** Cancel a non-terminal task; releases dependents so siblings are not deadlocked. */
    @Transactional
    public List<Long> cancelTask(Long taskId, String reason) {
        TeamTaskEntity task = requireTask(taskId);
        int rows = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .notIn(TeamTaskEntity::getStatus,
                        TeamTaskStatus.COMPLETED, TeamTaskStatus.FAILED, TeamTaskStatus.CANCELLED)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.CANCELLED)
                .set(TeamTaskEntity::getReason, reason)
                .set(TeamTaskEntity::getLockExpiresAt, null));
        if (rows != 1) {
            throw new IllegalStateException("task #" + task.getTaskNumber() + " is already terminal");
        }
        return releaseDependents(task);
    }

    /** Manual retry of a failed/stale task: back to pending, owner and breaker reset. */
    public boolean retryTask(Long taskId) {
        return taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .in(TeamTaskEntity::getStatus, TeamTaskStatus.FAILED, TeamTaskStatus.STALE)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING)
                .set(TeamTaskEntity::getOwnerAgentId, null)
                .set(TeamTaskEntity::getLockExpiresAt, null)
                .set(TeamTaskEntity::getReason, null)
                .set(TeamTaskEntity::getDispatchCount, 0)) == 1;
    }

    // ==================== progress / comments ====================

    /** Update progress and renew the execution lease in one shot. */
    public boolean updateProgress(Long taskId, Long agentId, Integer percent, String step) {
        return taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .eq(agentId != null, TeamTaskEntity::getOwnerAgentId, agentId)
                .set(percent != null, TeamTaskEntity::getProgressPercent, percent)
                .set(step != null, TeamTaskEntity::getProgressStep, step)
                .set(TeamTaskEntity::getLockExpiresAt, newLease())) == 1;
    }

    /** Extend the execution lease (runner heartbeat). */
    public void renewLock(Long taskId) {
        taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .set(TeamTaskEntity::getLockExpiresAt, newLease()));
    }

    /**
     * Add a comment. A blocker comment on an in_progress task auto-fails the
     * task; the caller (dispatch layer) is responsible for escalating to the
     * lead when this returns true.
     *
     * @return true when the comment was a blocker that failed the task
     */
    @Transactional
    public boolean addComment(Long taskId, String authorType, String authorId,
                              String commentType, String content) {
        TeamTaskEntity task = requireTask(taskId);
        TeamTaskCommentEntity comment = new TeamTaskCommentEntity();
        comment.setTaskId(taskId);
        comment.setTeamId(task.getTeamId());
        comment.setAuthorType(authorType);
        comment.setAuthorId(authorId);
        comment.setCommentType(commentType == null ? COMMENT_NOTE : commentType);
        comment.setContent(content);
        commentMapper.insert(comment);

        if (COMMENT_BLOCKER.equals(comment.getCommentType())) {
            boolean failed = failTask(taskId, "blocked: " + content);
            if (failed) {
                log.info("Team task {} auto-failed by blocker comment from {}:{}",
                        taskId, authorType, authorId);
            }
            return failed;
        }
        return false;
    }

    public List<TeamTaskCommentEntity> listComments(Long taskId) {
        return commentMapper.selectList(Wrappers.<TeamTaskCommentEntity>lambdaQuery()
                .eq(TeamTaskCommentEntity::getTaskId, taskId)
                .orderByAsc(TeamTaskCommentEntity::getCreateTime));
    }

    // ==================== dispatch support ====================

    /**
     * Reserve one dispatch attempt. Returns false — and auto-fails the task —
     * once the circuit-breaker cap is exhausted, so a task that keeps bouncing
     * cannot loop forever.
     */
    @Transactional
    public boolean tryAcquireDispatch(Long taskId) {
        int rows = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .lt(TeamTaskEntity::getDispatchCount, MAX_DISPATCHES)
                .setSql("dispatch_count = dispatch_count + 1"));
        if (rows == 1) {
            return true;
        }
        boolean failed = failTask(taskId, "dispatch circuit breaker: exceeded "
                + MAX_DISPATCHES + " attempts");
        if (failed) {
            log.warn("Team task {} auto-failed by dispatch circuit breaker", taskId);
        }
        return false;
    }

    /**
     * Pending tasks eligible for dispatch, priority first. The dispatch layer
     * picks at most one per assignee so a member never runs two tasks at once.
     */
    public List<TeamTaskEntity> findDispatchable(Long teamId) {
        return taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getTeamId, teamId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING)
                .isNotNull(TeamTaskEntity::getAssigneeAgentId)
                .orderByDesc(TeamTaskEntity::getPriority)
                .orderByAsc(TeamTaskEntity::getCreateTime));
    }

    /** Whether the agent is already executing a task in this team. */
    public boolean hasActiveTask(Long teamId, Long agentId) {
        return taskMapper.selectCount(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getTeamId, teamId)
                .eq(TeamTaskEntity::getOwnerAgentId, agentId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)) > 0;
    }

    /**
     * Mark in_progress tasks whose lease expired as stale. Returns the affected
     * tasks so a scheduler can escalate or retry them.
     */
    @Transactional
    public List<TeamTaskEntity> recoverStaleTasks() {
        List<TeamTaskEntity> expired = taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .isNotNull(TeamTaskEntity::getLockExpiresAt)
                .lt(TeamTaskEntity::getLockExpiresAt, LocalDateTime.now()));
        for (TeamTaskEntity task : expired) {
            taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                    .eq(TeamTaskEntity::getId, task.getId())
                    .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                    .set(TeamTaskEntity::getStatus, TeamTaskStatus.STALE)
                    .set(TeamTaskEntity::getReason, "execution lease expired"));
        }
        if (!expired.isEmpty()) {
            log.warn("Marked {} team task(s) stale after lease expiry", expired.size());
        }
        return expired;
    }

    // ==================== queries ====================

    public TeamTaskEntity getTask(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    public List<TeamTaskEntity> listTasks(Long teamId, List<String> statuses) {
        return taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getTeamId, teamId)
                .in(statuses != null && !statuses.isEmpty(), TeamTaskEntity::getStatus, statuses)
                .orderByDesc(TeamTaskEntity::getPriority)
                .orderByDesc(TeamTaskEntity::getCreateTime));
    }

    // ==================== dependency release ====================

    /**
     * Release tasks blocked on the given task once ALL of their blockers have
     * reached a releasing status (completed / cancelled). Failed blockers keep
     * dependents blocked — a retry may still succeed.
     *
     * @return ids of tasks transitioned from blocked to pending
     */
    List<Long> releaseDependents(TeamTaskEntity finished) {
        List<TeamTaskEntity> blocked = taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getTeamId, finished.getTeamId())
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.BLOCKED));
        if (blocked.isEmpty()) {
            return List.of();
        }
        List<Long> released = new ArrayList<>();
        for (TeamTaskEntity candidate : blocked) {
            List<Long> blockerIds = parseIdArray(candidate.getBlockedBy());
            if (!blockerIds.contains(finished.getId())) {
                continue;
            }
            boolean allReleased = blockerIds.stream().allMatch(id -> {
                if (Objects.equals(id, finished.getId())) {
                    return true;
                }
                TeamTaskEntity blocker = taskMapper.selectById(id);
                // A vanished blocker must not deadlock its dependents forever.
                return blocker == null
                        || TeamTaskStatus.RELEASES_DEPENDENTS.contains(blocker.getStatus());
            });
            if (!allReleased) {
                continue;
            }
            int rows = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                    .eq(TeamTaskEntity::getId, candidate.getId())
                    .eq(TeamTaskEntity::getStatus, TeamTaskStatus.BLOCKED)
                    .set(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING));
            if (rows == 1) {
                released.add(candidate.getId());
            }
        }
        if (!released.isEmpty()) {
            log.info("Task {} released {} dependent task(s): {}",
                    finished.getId(), released.size(), released);
        }
        return released;
    }

    // ==================== helpers ====================

    private TeamTaskEntity requireTask(Long taskId) {
        TeamTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        return task;
    }

    private static LocalDateTime newLease() {
        return LocalDateTime.now().plusMinutes(LOCK_MINUTES);
    }

    /** Ids are serialized as JSON strings to stay safe across the JS frontend. */
    private static String toJsonIdArray(List<Long> ids) {
        return JSONUtil.toJsonStr(ids.stream().map(String::valueOf).toList());
    }

    static List<Long> parseIdArray(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSONUtil.toList(json, String.class).stream()
                    .map(Long::valueOf)
                    .toList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
