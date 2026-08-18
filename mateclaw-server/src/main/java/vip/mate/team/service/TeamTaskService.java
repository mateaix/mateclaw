package vip.mate.team.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
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
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamTaskEventEntity;
import vip.mate.team.repository.TeamTaskCommentMapper;
import vip.mate.team.repository.TeamTaskEventMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern CHECKPOINT_RANGE = Pattern.compile(
            "(?i)R(\\d{3})\\s*[-–—]\\s*R(\\d{3})");

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
    private final TeamTaskEventMapper eventMapper;
    private final TeamService teamService;
    private final TeamRunProjectionScheduler projectionScheduler;
    private final TeamRunService runService;

    // ==================== creation ====================

    @Transactional
    public TeamTaskEntity createTask(TeamTaskCreateCommand cmd) {
        AgentTeamEntity team = teamService.getTeam(cmd.getTeamId());
        if (team == null || !TeamService.STATUS_ACTIVE.equals(team.getStatus())) {
            throw new IllegalArgumentException("team not found or not active: " + cmd.getTeamId());
        }
        if (cmd.getRunId() != null) {
            TeamRunEntity run = runService.requireRun(cmd.getRunId(), team.getWorkspaceId());
            if (!cmd.getTeamId().equals(run.getTeamId())) {
                throw new IllegalArgumentException("team task and run must belong to the same team");
            }
            if (!TeamRunStatus.PLANNING.equals(run.getStatus())) {
                throw new IllegalStateException("team run must be planning to accept tasks: " + cmd.getRunId());
            }
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

        // Dependency edges can only reference pre-existing tasks and blockedBy is
        // immutable after creation, so the dependency graph is acyclic by
        // construction — adding an edit path for blockedBy would break this
        // invariant and require real cycle detection.
        List<Long> blockers = cmd.getBlockedBy() == null ? List.of() : cmd.getBlockedBy();
        for (Long blockerId : blockers) {
            TeamTaskEntity blocker = taskMapper.selectById(blockerId);
            if (blocker == null || !blocker.getTeamId().equals(cmd.getTeamId())) {
                throw new IllegalArgumentException("blocking task not found in this team: " + blockerId);
            }
            if (!Objects.equals(blocker.getRunId(), cmd.getRunId())) {
                throw new IllegalArgumentException("blocking task must belong to the same run: " + blockerId);
            }
            if (TeamTaskStatus.isTerminal(blocker.getStatus())) {
                throw new IllegalArgumentException("blocking task " + blockerId
                        + " is already " + blocker.getStatus()
                        + "; pass its result in the description instead of blocking on it");
            }
        }

        TeamTaskEntity task = new TeamTaskEntity();
        task.setTeamId(cmd.getTeamId());
        task.setRunId(cmd.getRunId());
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
        recordEvent(cmd.getTeamId(), task.getId(), TeamTaskEventEntity.CREATED,
                cmd.getCreatedByAgentId() != null ? AUTHOR_AGENT
                        : cmd.getUsername() != null ? AUTHOR_USER : AUTHOR_SYSTEM,
                cmd.getCreatedByAgentId() != null ? String.valueOf(cmd.getCreatedByAgentId())
                        : cmd.getUsername(),
                "assignee: agent " + assignee);
        log.info("Team {} task #{} created ({}), assignee={} status={}",
                cmd.getTeamId(), task.getTaskNumber(), task.getId(), assignee, task.getStatus());
        projectTask(task);
        return task;
    }

    // ==================== claim / assign ====================

    /**
     * Atomically claim a pending, unowned task. Exactly one caller wins; losers
     * get false. The WHERE clause is the mutex.
     */
    public boolean claimTask(Long taskId, Long agentId) {
        boolean claimed = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING)
                .isNull(TeamTaskEntity::getOwnerAgentId)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .set(TeamTaskEntity::getOwnerAgentId, agentId)
                .set(TeamTaskEntity::getLockExpiresAt, newLease())) == 1;
        if (claimed) {
            projectTask(taskId);
        }
        return claimed;
    }

    /**
     * Assign a pending task to an agent (dispatch / admin path). Unlike claim,
     * this overrides a previously set owner but still requires pending status.
     */
    public boolean assignTask(Long taskId, Long agentId) {
        boolean assigned = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .set(TeamTaskEntity::getOwnerAgentId, agentId)
                .set(TeamTaskEntity::getReason, null)
                .set(TeamTaskEntity::getLockExpiresAt, newLease())) == 1;
        if (assigned) {
            projectTask(taskId);
        }
        return assigned;
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
     * first (single-call convenience; safe because the claim is atomic), but
     * only by its assignee — otherwise any team member could complete another
     * member's not-yet-dispatched task. When the task requires approval it
     * parks in in_review instead of completed.
     *
     * @return ids of dependent tasks released to pending by this completion
     */
    @Transactional
    public List<Long> completeTask(Long taskId, Long agentId, String result) {
        TeamTaskEntity task = requireTask(taskId);
        if (TeamTaskStatus.PENDING.equals(task.getStatus()) && agentId != null) {
            if (task.getAssigneeAgentId() != null && !agentId.equals(task.getAssigneeAgentId())) {
                throw new IllegalStateException("task #" + task.getTaskNumber()
                        + " is assigned to another agent; only the assignee can claim and complete it");
            }
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
        recordEvent(task.getTeamId(), taskId,
                toReview ? TeamTaskEventEntity.IN_REVIEW : TeamTaskEventEntity.COMPLETED,
                agentId != null ? AUTHOR_AGENT : AUTHOR_SYSTEM,
                agentId != null ? String.valueOf(agentId) : null, null);
        List<Long> released = toReview ? List.of() : releaseDependents(task);
        projectTask(task);
        return released;
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
        List<Long> released = releaseDependents(task);
        projectTask(task);
        return released;
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
        List<Long> released = releaseDependents(task);
        projectTask(task);
        return released;
    }

    /** Fail a task (blocker escalation, runner error, circuit breaker). Does NOT release dependents. */
    public boolean failTask(Long taskId, String reason) {
        TeamTaskEntity task = taskMapper.selectById(taskId);
        boolean failed = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .in(TeamTaskEntity::getStatus,
                        TeamTaskStatus.PENDING, TeamTaskStatus.IN_PROGRESS, TeamTaskStatus.STALE)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.FAILED)
                .set(TeamTaskEntity::getReason, reason)
                .set(TeamTaskEntity::getLockExpiresAt, null)) == 1;
        if (failed) {
            recordEvent(task == null ? null : task.getTeamId(), taskId,
                    TeamTaskEventEntity.FAILED, AUTHOR_SYSTEM, null, reason);
            projectTask(taskId);
        }
        return failed;
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
        List<Long> released = releaseDependents(task);
        projectTask(task);
        return released;
    }

    /** Manual retry of a failed/stale task: back to pending, owner and breaker reset. */
    public boolean retryTask(Long taskId) {
        boolean retried = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .in(TeamTaskEntity::getStatus, TeamTaskStatus.FAILED, TeamTaskStatus.STALE)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING)
                .set(TeamTaskEntity::getOwnerAgentId, null)
                .set(TeamTaskEntity::getLockExpiresAt, null)
                .set(TeamTaskEntity::getReason, null)
                .set(TeamTaskEntity::getDispatchCount, 0)) == 1;
        if (retried) {
            projectTask(taskId);
        }
        return retried;
    }

    /**
     * Requeue an automatically dispatched task whose member result is unusable.
     * Unlike a manual retry this deliberately preserves {@code dispatchCount},
     * so the existing dispatch circuit breaker remains the hard upper bound.
     */
    public boolean requeueUnusableResult(Long taskId, String reason) {
        TeamTaskEntity task = taskMapper.selectById(taskId);
        boolean requeued = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .set(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING)
                .set(TeamTaskEntity::getOwnerAgentId, null)
                .set(TeamTaskEntity::getLockExpiresAt, null)
                .set(TeamTaskEntity::getReason, reason)) == 1;
        if (requeued) {
            recordEvent(task == null ? null : task.getTeamId(), taskId,
                    TeamTaskEventEntity.RETRIED, AUTHOR_SYSTEM, null, reason);
            projectTask(taskId);
        }
        return requeued;
    }

    // ==================== progress / comments ====================

    /** Update progress and renew the execution lease in one shot. */
    public boolean updateProgress(Long taskId, Long agentId, Integer percent, String step) {
        TeamTaskEntity task = taskMapper.selectById(taskId);
        boolean updated = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                .eq(agentId != null, TeamTaskEntity::getOwnerAgentId, agentId)
                .set(percent != null, TeamTaskEntity::getProgressPercent, percent)
                .set(step != null, TeamTaskEntity::getProgressStep, step)
                .set(TeamTaskEntity::getLockExpiresAt, newLease())) == 1;
        if (updated) {
            recordEvent(task == null ? null : task.getTeamId(), taskId,
                    TeamTaskEventEntity.PROGRESS, AUTHOR_AGENT,
                    agentId != null ? String.valueOf(agentId) : null,
                    (percent != null ? percent + "%" : "") + (step != null ? " — " + step : ""));
            projectTask(taskId);
        }
        return updated;
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
    public synchronized boolean addComment(Long taskId, String authorType, String authorId,
                                            String commentType, String content) {
        TeamTaskEntity task = requireTask(taskId);
        String normalizedType = commentType == null ? COMMENT_NOTE : commentType;
        String checkpointKey = checkpointEvidenceKey(content);
        if (COMMENT_NOTE.equals(normalizedType)
                && checkpointKey != null
                && checkpointTerminalTag(task) != null
                && hasCheckpointEvidence(taskId, checkpointKey)) {
            log.debug("Skipped duplicate checkpoint evidence {} on team task {}",
                    checkpointKey, taskId);
            return false;
        }
        TeamTaskCommentEntity comment = new TeamTaskCommentEntity();
        comment.setTaskId(taskId);
        comment.setTeamId(task.getTeamId());
        comment.setAuthorType(authorType);
        comment.setAuthorId(authorId);
        comment.setCommentType(normalizedType);
        comment.setContent(content);
        commentMapper.insert(comment);
        recordEvent(task.getTeamId(), taskId,
                COMMENT_BLOCKER.equals(comment.getCommentType())
                        ? TeamTaskEventEntity.BLOCKER : TeamTaskEventEntity.COMMENT,
                authorType, authorId, content);

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

    /** Persist a note once, using a semantic checkpoint key when present. */
    @Transactional
    public synchronized boolean addCommentOnce(Long taskId, String authorType, String authorId,
                                               String commentType, String content) {
        String checkpointKey = checkpointEvidenceKey(content);
        boolean exists = checkpointKey == null
                ? hasExactComment(taskId, content)
                : hasCheckpointEvidence(taskId, checkpointKey);
        if (exists) {
            return false;
        }
        addComment(taskId, authorType, authorId, commentType, content);
        return true;
    }

    private boolean hasExactComment(Long taskId, String content) {
        Long existing = commentMapper.selectCount(Wrappers.<TeamTaskCommentEntity>lambdaQuery()
                .eq(TeamTaskCommentEntity::getTaskId, taskId)
                .eq(TeamTaskCommentEntity::getContent, content));
        return existing != null && existing > 0;
    }

    private boolean hasCheckpointEvidence(Long taskId, String checkpointKey) {
        Long existing = commentMapper.selectCount(Wrappers.<TeamTaskCommentEntity>lambdaQuery()
                .eq(TeamTaskCommentEntity::getTaskId, taskId)
                .like(TeamTaskCommentEntity::getContent, checkpointKey));
        return existing != null && existing > 0;
    }

    static String checkpointEvidenceKey(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)\\[checkpoint:(R\\d{3,})]\\s*acknowledged")
                .matcher(content);
        return matcher.find()
                ? "[checkpoint:" + matcher.group(1).toUpperCase() + "] acknowledged"
                : null;
    }

    // ==================== timeline events ====================

    /** Timeline detail cap, matching the column width. */
    static final int MAX_EVENT_DETAIL_CHARS = 1000;

    /**
     * Record a lifecycle moment on the task's timeline. Best-effort side
     * channel: any failure is logged and swallowed — a missing timeline row
     * is acceptable, a task transition broken by the audit trail is not.
     */
    public void recordEvent(Long teamId, Long taskId, String eventType,
                            String actorType, String actorId, String detail) {
        try {
            TeamTaskEventEntity event = new TeamTaskEventEntity();
            event.setTeamId(teamId);
            event.setTaskId(taskId);
            event.setEventType(eventType);
            event.setActorType(actorType);
            event.setActorId(actorId);
            event.setDetail(detail == null || detail.length() <= MAX_EVENT_DETAIL_CHARS
                    ? detail : detail.substring(0, MAX_EVENT_DETAIL_CHARS));
            eventMapper.insert(event);
        } catch (Exception e) {
            log.warn("Team task {} timeline event '{}' not recorded: {}",
                    taskId, eventType, e.getMessage());
        }
    }

    /** The task's timeline, oldest first. */
    public List<TeamTaskEventEntity> listEvents(Long taskId) {
        return eventMapper.selectList(Wrappers.<TeamTaskEventEntity>lambdaQuery()
                .eq(TeamTaskEventEntity::getTaskId, taskId)
                .orderByAsc(TeamTaskEventEntity::getCreateTime)
                .orderByAsc(TeamTaskEventEntity::getId));
    }

    // ==================== deliverables ====================

    /** Maximum deliverables per task; the board is a summary surface, not a file store. */
    static final int MAX_DELIVERABLES = 10;

    /** Download-path prefix of the generated-file cache — the only accepted deliverable URL form. */
    static final String GENERATED_FILE_PATH = "/api/v1/files/generated/";

    /** A produced-file reference surfaced on the task card. */
    public record Deliverable(String name, String url, String time) {
    }

    /**
     * Attach a produced-file reference to the task, stored under the
     * "deliverables" key of the task's metadata JSON.
     *
     * Single-writer assumption: only the task owner's run thread calls this
     * (tool-side gating) and no other code path writes metadata, so a plain
     * read-modify-write is safe. If a second metadata writer ever appears,
     * switch to a SQL-level JSON merge or optimistic locking.
     */
    @Transactional
    public void addDeliverable(Long taskId, Long agentId, String name, String url) {
        TeamTaskEntity task = requireTask(taskId);
        if (TeamTaskStatus.isTerminal(task.getStatus())) {
            throw new IllegalStateException("task #" + task.getTaskNumber() + " is "
                    + task.getStatus() + "; deliverables can only be attached while it is active");
        }
        if (agentId != null && task.getOwnerAgentId() != null
                && !agentId.equals(task.getOwnerAgentId())) {
            throw new IllegalStateException("task #" + task.getTaskNumber()
                    + " is owned by another agent; only the owner can attach deliverables");
        }
        if (name == null || name.isBlank() || url == null || url.isBlank()) {
            throw new IllegalArgumentException("both name and url are required for a deliverable");
        }
        String trimmedUrl = url.trim();
        if (!isGeneratedFileUrl(trimmedUrl)) {
            throw new IllegalArgumentException("url must be a " + GENERATED_FILE_PATH
                    + " download link produced by a render tool; external links are not accepted");
        }

        JSONObject metadata = task.getMetadata() == null || task.getMetadata().isBlank()
                ? new JSONObject()
                : JSONUtil.parseObj(task.getMetadata());
        JSONArray deliverables = metadata.getJSONArray("deliverables");
        if (deliverables == null) {
            deliverables = new JSONArray();
        }
        if (deliverables.size() >= MAX_DELIVERABLES) {
            throw new IllegalStateException("task #" + task.getTaskNumber() + " already has "
                    + MAX_DELIVERABLES + " deliverables; consolidate outputs instead of adding more");
        }
        deliverables.add(new JSONObject()
                .set("name", name.trim())
                .set("url", trimmedUrl)
                .set("time", LocalDateTime.now().toString()));
        metadata.set("deliverables", deliverables);
        taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                .eq(TeamTaskEntity::getId, taskId)
                .set(TeamTaskEntity::getMetadata, metadata.toString()));
        recordEvent(task.getTeamId(), taskId, TeamTaskEventEntity.DELIVERABLE,
                AUTHOR_AGENT, agentId != null ? String.valueOf(agentId) : null, name.trim());
        log.info("Team task {} deliverable attached: {}", taskId, name.trim());
    }

    /** Parse the task's deliverable list; empty on missing/malformed metadata. */
    public List<Deliverable> listDeliverables(TeamTaskEntity task) {
        if (task == null || task.getMetadata() == null || task.getMetadata().isBlank()) {
            return List.of();
        }
        try {
            JSONArray arr = JSONUtil.parseObj(task.getMetadata())
                    .getJSONArray("deliverables");
            if (arr == null) {
                return List.of();
            }
            List<Deliverable> result = new ArrayList<>();
            for (Object entry : arr) {
                JSONObject obj = (JSONObject) entry;
                result.add(new Deliverable(obj.getStr("name"), obj.getStr("url"), obj.getStr("time")));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Accept the cache's relative download path, or an absolute URL whose path is one. */
    private static boolean isGeneratedFileUrl(String url) {
        if (url.startsWith(GENERATED_FILE_PATH)) {
            return true;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                String path = URI.create(url).getPath();
                return path != null && path.startsWith(GENERATED_FILE_PATH);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
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
        List<TeamTaskEntity> candidates = taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getTeamId, teamId)
                .eq(TeamTaskEntity::getStatus, TeamTaskStatus.PENDING)
                .isNotNull(TeamTaskEntity::getAssigneeAgentId)
                .orderByDesc(TeamTaskEntity::getPriority)
                .orderByAsc(TeamTaskEntity::getCreateTime));
        Set<Long> runIds = candidates.stream()
                .map(TeamTaskEntity::getRunId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> planningRunIds = runService.findPlanningRunIds(runIds);
        return candidates.stream()
                .filter(task -> task.getRunId() == null || !planningRunIds.contains(task.getRunId()))
                .toList();
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
            int rows = taskMapper.update(null, Wrappers.<TeamTaskEntity>lambdaUpdate()
                    .eq(TeamTaskEntity::getId, task.getId())
                    .eq(TeamTaskEntity::getStatus, TeamTaskStatus.IN_PROGRESS)
                    .set(TeamTaskEntity::getStatus, TeamTaskStatus.STALE)
                    .set(TeamTaskEntity::getReason, "execution lease expired"));
            if (rows == 1) {
                recordEvent(task.getTeamId(), task.getId(), TeamTaskEventEntity.STALE,
                        AUTHOR_SYSTEM, null, "execution lease expired");
                projectTask(task);
            }
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

    public List<TeamTaskEntity> listTasksByRun(Long runId) {
        return taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getRunId, runId)
                .orderByAsc(TeamTaskEntity::getTaskNumber));
    }

    /**
     * Tasks created from a delegated plan's steps, ordered by creation. The
     * plan linkage lives in the task metadata JSON ({@code "planId"} written
     * as a string), matched with a LIKE — team boards are small and the
     * pattern includes the quoted key, so false positives are not a concern.
     */
    public List<TeamTaskEntity> listTasksByPlan(Long teamId, Long planId) {
        return taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getTeamId, teamId)
                .like(TeamTaskEntity::getMetadata, "\"planId\":\"" + planId + "\"")
                .orderByAsc(TeamTaskEntity::getCreateTime));
    }

    /**
     * Locate the team's dedicated long-running checkpoint tracker, even when
     * the current checkpoint belongs to a later run. Highest priority and
     * newest creation win when historical tests left more than one candidate.
     */
    public java.util.Optional<TeamTaskEntity> findCheckpointTracker(Long teamId) {
        return java.util.Optional.ofNullable(taskMapper.selectOne(
                Wrappers.<TeamTaskEntity>lambdaQuery()
                        .eq(TeamTaskEntity::getTeamId, teamId)
                        .and(candidate -> candidate
                                .like(TeamTaskEntity::getSubject, "共享跟踪")
                                .or().like(TeamTaskEntity::getSubject, "检查点")
                                .or().like(TeamTaskEntity::getSubject, "checkpoint"))
                        .orderByDesc(TeamTaskEntity::getPriority)
                        .orderByDesc(TeamTaskEntity::getCreateTime)
                        .last("LIMIT 1")));
    }

    /** Terminal checkpoint tag declared by a long-running tracker, e.g. R300. */
    public String checkpointTerminalTag(TeamTaskEntity task) {
        if (task == null) {
            return null;
        }
        String description = task.getDescription() == null ? "" : task.getDescription();
        int contextStart = description.indexOf("[Plan context]");
        if (contextStart >= 0) {
            description = description.substring(0, contextStart);
        }
        String text = (task.getSubject() == null ? "" : task.getSubject()) + " " + description;
        String lower = text.toLowerCase();
        if (!text.contains("共享跟踪") && !text.contains("检查点")
                && !lower.contains("checkpoint")) {
            return null;
        }
        Matcher matcher = CHECKPOINT_RANGE.matcher(text);
        return matcher.find() ? "R" + matcher.group(2) : null;
    }

    public List<TeamTaskEntity> listTasks(Long teamId, List<String> statuses) {
        return listTasks(teamId, statuses, null, null);
    }

    /**
     * Board query with optional windowing. Terminal columns grow without
     * bound on long-lived teams, so the UI pages them (newest first) while
     * active columns stay unwindowed. LIMIT/OFFSET is valid across all three
     * supported dialects.
     */
    public List<TeamTaskEntity> listTasks(Long teamId, List<String> statuses,
                                          Integer limit, Integer offset) {
        return listTasks(teamId, statuses, limit, offset, null);
    }

    /** Board query optionally scoped to one run to avoid mixing history. */
    public List<TeamTaskEntity> listTasks(Long teamId, List<String> statuses,
                                          Integer limit, Integer offset, Long runId) {
        return taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getTeamId, teamId)
                .eq(runId != null, TeamTaskEntity::getRunId, runId)
                .in(statuses != null && !statuses.isEmpty(), TeamTaskEntity::getStatus, statuses)
                .orderByDesc(TeamTaskEntity::getPriority)
                .orderByDesc(TeamTaskEntity::getCreateTime)
                .last(limit != null,
                        "LIMIT " + (limit == null ? 0 : Math.max(1, limit))
                                + " OFFSET " + (offset == null ? 0 : Math.max(0, offset))));
    }

    /** Per-status task counts for the board header, computed in the database. */
    public Map<String, Long> countByStatus(Long teamId) {
        return countByStatus(teamId, null);
    }

    public Map<String, Long> countByStatus(Long teamId, Long runId) {
        Map<String, Long> counts = new HashMap<>();
        taskMapper.selectMaps(Wrappers.<TeamTaskEntity>query()
                        .select("status", "count(*) as cnt")
                        .eq("team_id", teamId)
                        .eq(runId != null, "run_id", runId)
                        .eq("deleted", 0)
                        .groupBy("status"))
                .forEach(row -> counts.put(String.valueOf(row.get("status")),
                        ((Number) row.get("cnt")).longValue()));
        return counts;
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
                projectTask(candidate);
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

    private void projectTask(Long taskId) {
        try {
            projectionScheduler.scheduleTask(taskId);
        } catch (RuntimeException error) {
            log.warn("Team run projection failed after task {} changed: {}", taskId, error.getMessage());
        }
    }

    private void projectTask(TeamTaskEntity task) {
        if (task == null || task.getRunId() == null) {
            return;
        }
        try {
            projectionScheduler.scheduleRun(task.getRunId());
        } catch (RuntimeException error) {
            log.warn("Team run {} projection failed after task {} changed: {}",
                    task.getRunId(), task.getId(), error.getMessage());
        }
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
