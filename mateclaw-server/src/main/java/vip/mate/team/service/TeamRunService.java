package vip.mate.team.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamRunCreateCommand;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.repository.TeamRunMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;

/** Owns team run creation, lifecycle transitions, authorization, and reads. */
@Service
@Slf4j
public class TeamRunService {

    public record RunPage(List<TeamRunView> items, String nextCursor) {
    }

    public record SealResult(TeamRunEntity run, boolean transitioned) {
    }

    public record CancelResult(TeamRunEntity run, boolean transitioned) {
    }

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int TASK_SUMMARY_BATCH_SIZE = 500;
    private static final Set<String> FINAL_OUTCOMES = Set.of(
            TeamRunStatus.COMPLETED, TeamRunStatus.PARTIAL, TeamRunStatus.FAILED);

    private final TeamRunMapper runMapper;
    private final TeamTaskMapper taskMapper;
    private final TeamService teamService;
    private final TeamRunStateMachine stateMachine;

    public TeamRunService(TeamRunMapper runMapper, TeamTaskMapper taskMapper, TeamService teamService) {
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
        this.teamService = teamService;
        this.stateMachine = new TeamRunStateMachine();
    }

    public TeamRunEntity startRun(TeamRunCreateCommand command) {
        validateCreate(command);
        TeamRunEntity existing = findByOrigin(command.getWorkspaceId(), command.getLeadConversationId(),
                command.getOriginMessageId());
        if (existing != null) {
            return existing;
        }

        TeamRunEntity run = new TeamRunEntity();
        run.setTeamId(command.getTeamId());
        run.setWorkspaceId(command.getWorkspaceId());
        run.setLeadAgentId(command.getLeadAgentId());
        run.setLeadConversationId(command.getLeadConversationId());
        run.setOriginMessageId(command.getOriginMessageId());
        run.setTitle(deriveTitle(command));
        run.setObjective(command.getObjective().trim());
        run.setStatus(TeamRunStatus.PLANNING);
        run.setMetadata(command.getMetadata());
        try {
            runMapper.insert(run);
            return run;
        } catch (DuplicateKeyException duplicate) {
            TeamRunEntity winner = findByOrigin(command.getWorkspaceId(), command.getLeadConversationId(),
                    command.getOriginMessageId());
            if (winner != null) {
                return winner;
            }
            throw duplicate;
        }
    }

    public TeamRunEntity requireRun(Long runId, Long workspaceId) {
        TeamRunEntity run = runMapper.selectById(runId);
        if (run == null || workspaceId == null || !workspaceId.equals(run.getWorkspaceId())) {
            throw new IllegalArgumentException("team run not found in workspace: " + runId);
        }
        return run;
    }

    public Set<Long> findPlanningRunIds(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Set.of();
        }
        return runMapper.selectBatchIds(runIds).stream()
                .filter(run -> TeamRunStatus.PLANNING.equals(run.getStatus()))
                .map(TeamRunEntity::getId)
                .collect(Collectors.toSet());
    }

    public TeamRunView getRun(Long runId, Long workspaceId) {
        return buildView(requireRun(runId, workspaceId));
    }

    /** Reconciles runs stranded after the optional LLM summary step failed. */
    @Scheduled(fixedDelayString = "${mateclaw.team.finalizing-reconcile-ms:30000}", initialDelay = 30000)
    @Transactional
    public void reconcileFinalizingRuns() {
        List<TeamRunEntity> runs = runMapper.selectList(Wrappers.<TeamRunEntity>lambdaQuery()
                .eq(TeamRunEntity::getStatus, TeamRunStatus.FINALIZING));
        for (TeamRunEntity run : runs) {
            List<TeamTaskEntity> tasks = tasksForRun(run.getId());
            if (tasks.isEmpty() || tasks.stream().anyMatch(task -> !TeamTaskStatus.isTerminal(task.getStatus()))) {
                continue;
            }
            String outcome = tasks.stream().allMatch(task -> TeamTaskStatus.COMPLETED.equals(task.getStatus()))
                    ? TeamRunStatus.COMPLETED
                    : tasks.stream().anyMatch(task -> TeamTaskStatus.COMPLETED.equals(task.getStatus()))
                    ? TeamRunStatus.PARTIAL : TeamRunStatus.FAILED;
            String fallback = "执行摘要（汇总模型不可用，以下为步骤原始结果）：\n"
                    + tasks.stream().map(task -> {
                        String result = TeamTaskStatus.COMPLETED.equals(task.getStatus())
                                ? task.getResult() : task.getReason();
                        return "- #" + task.getTaskNumber() + " " + (result == null ? task.getStatus() : result);
                    }).collect(Collectors.joining("\n"));
            finalizeWithoutSummary(run, outcome, fallback);
        }
    }

    private void finalizeWithoutSummary(TeamRunEntity run, String outcome, String summary) {
        LocalDateTime completedAt = LocalDateTime.now();
        String fallbackMetadata = metadata(run.getMetadata()).set("summaryQuality", "fallback").toString();
        runMapper.update(null, Wrappers.<TeamRunEntity>lambdaUpdate()
                .eq(TeamRunEntity::getId, run.getId())
                .eq(TeamRunEntity::getStatus, TeamRunStatus.FINALIZING)
                .set(TeamRunEntity::getStatus, outcome)
                .set(TeamRunEntity::getFinalSummary, summary)
                .set(TeamRunEntity::getMetadata, fallbackMetadata)
                .set(TeamRunEntity::getCompletedAt, completedAt));
        log.warn("Reconciled stranded team run {} from finalizing to {}", run.getId(), outcome);
    }

    public RunPage pageTeamRuns(Long teamId, Long workspaceId, boolean activeOnly,
                                String cursor, int requestedLimit) {
        return pageRuns(teamId, null, workspaceId, activeOnly, cursor, requestedLimit);
    }

    /** Backward-compatible array response used until clients migrate to cursor pagination. */
    public List<TeamRunView> listTeamRuns(Long teamId, Long workspaceId, boolean activeOnly) {
        return listRuns(teamId, null, workspaceId, activeOnly);
    }

    /** Backward-compatible array response used until clients migrate to cursor pagination. */
    public List<TeamRunView> listConversationRuns(String conversationId, Long workspaceId) {
        return listRuns(null, conversationId, workspaceId, false);
    }

    /** Latest run linked to an internal lead conversation, including terminal runs. */
    public Optional<TeamRunEntity> findLatestConversationRun(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(runMapper.selectOne(Wrappers.<TeamRunEntity>lambdaQuery()
                .eq(TeamRunEntity::getLeadConversationId, conversationId)
                .orderByDesc(TeamRunEntity::getCreateTime)
                .orderByDesc(TeamRunEntity::getId)
                .last("LIMIT 1")));
    }

    private List<TeamRunView> listRuns(Long teamId, String conversationId, Long workspaceId,
                                       boolean activeOnly) {
        var query = Wrappers.<TeamRunEntity>lambdaQuery()
                .eq(teamId != null, TeamRunEntity::getTeamId, teamId)
                .eq(conversationId != null, TeamRunEntity::getLeadConversationId, conversationId)
                .eq(TeamRunEntity::getWorkspaceId, workspaceId);
        if (activeOnly) {
            query.in(TeamRunEntity::getStatus, TeamRunStatus.PLANNING, TeamRunStatus.RUNNING,
                    TeamRunStatus.AWAITING_REVIEW, TeamRunStatus.FINALIZING);
        }
        List<TeamRunEntity> runs = runMapper.selectList(query
                .orderByDesc(TeamRunEntity::getCreateTime).orderByDesc(TeamRunEntity::getId));
        return summaryViews(runs);
    }

    public RunPage pageConversationRuns(String conversationId, Long workspaceId,
                                        String cursor, int requestedLimit) {
        return pageRuns(null, conversationId, workspaceId, false, cursor, requestedLimit);
    }

    private RunPage pageRuns(Long teamId, String conversationId, Long workspaceId,
                             boolean activeOnly, String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? 20 : requestedLimit, 100));
        Cursor decoded = decodeCursor(cursor);
        var query = Wrappers.<TeamRunEntity>lambdaQuery()
                .eq(teamId != null, TeamRunEntity::getTeamId, teamId)
                .eq(conversationId != null, TeamRunEntity::getLeadConversationId, conversationId)
                .eq(TeamRunEntity::getWorkspaceId, workspaceId);
        if (activeOnly) {
            query.in(TeamRunEntity::getStatus, TeamRunStatus.PLANNING, TeamRunStatus.RUNNING,
                    TeamRunStatus.AWAITING_REVIEW, TeamRunStatus.FINALIZING);
        }
        if (decoded != null) {
            query.and(nested -> nested.lt(TeamRunEntity::getCreateTime, decoded.createTime())
                    .or(equal -> equal.eq(TeamRunEntity::getCreateTime, decoded.createTime())
                            .lt(TeamRunEntity::getId, decoded.id())));
        }
        List<TeamRunEntity> fetched = runMapper.selectList(query
                .orderByDesc(TeamRunEntity::getCreateTime).orderByDesc(TeamRunEntity::getId)
                .last("LIMIT " + (limit + 1)));
        boolean hasMore = fetched.size() > limit;
        List<TeamRunEntity> runs = hasMore ? fetched.subList(0, limit) : fetched;
        List<TeamRunView> items = summaryViews(runs);
        TeamRunEntity last = runs.isEmpty() ? null : runs.getLast();
        return new RunPage(items, hasMore && last != null ? encodeCursor(last) : null);
    }

    private List<TeamRunView> summaryViews(List<TeamRunEntity> runs) {
        Map<Long, List<TeamTaskEntity>> tasksByRun = summaryTasks(runs);
        return runs.stream().map(run -> {
            List<TeamTaskEntity> tasks = tasksByRun.getOrDefault(run.getId(), List.of());
            var projection = stateMachine.project(run, tasks);
            return TeamRunViewFactory.create(run, projection.status(), projection.progress(), tasks, false);
        }).toList();
    }

    private Map<Long, List<TeamTaskEntity>> summaryTasks(List<TeamRunEntity> runs) {
        if (runs.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TeamTaskEntity>> grouped = new LinkedHashMap<>();
        List<Long> runIds = runs.stream().map(TeamRunEntity::getId).toList();
        for (int start = 0; start < runIds.size(); start += TASK_SUMMARY_BATCH_SIZE) {
            List<Long> batch = runIds.subList(start, Math.min(start + TASK_SUMMARY_BATCH_SIZE, runIds.size()));
            List<TeamTaskEntity> tasks = taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                    .select(TeamTaskEntity::getId, TeamTaskEntity::getTeamId, TeamTaskEntity::getRunId,
                            TeamTaskEntity::getTaskNumber, TeamTaskEntity::getSubject,
                            TeamTaskEntity::getStatus, TeamTaskEntity::getPriority,
                            TeamTaskEntity::getTaskType, TeamTaskEntity::getAssigneeAgentId,
                            TeamTaskEntity::getOwnerAgentId, TeamTaskEntity::getBlockedBy,
                            TeamTaskEntity::getRequireApproval, TeamTaskEntity::getProgressPercent,
                            TeamTaskEntity::getProgressStep, TeamTaskEntity::getReason,
                            TeamTaskEntity::getConversationId, TeamTaskEntity::getMetadata,
                            TeamTaskEntity::getLockExpiresAt, TeamTaskEntity::getCreateTime,
                            TeamTaskEntity::getUpdateTime)
                    .in(TeamTaskEntity::getRunId, batch)
                    .orderByAsc(TeamTaskEntity::getTaskNumber));
            for (TeamTaskEntity task : tasks) {
                grouped.computeIfAbsent(task.getRunId(), ignored -> new ArrayList<>()).add(task);
            }
        }
        return grouped;
    }

    private record Cursor(LocalDateTime createTime, Long id) {
    }

    private String encodeCursor(TeamRunEntity run) {
        String raw = run.getCreateTime() + "|" + run.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            return new Cursor(LocalDateTime.parse(raw.substring(0, separator)),
                    Long.valueOf(raw.substring(separator + 1)));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid team run cursor");
        }
    }

    @Transactional
    public TeamRunEntity sealRun(Long runId, Long workspaceId) {
        return sealRunWithResult(runId, workspaceId).run();
    }

    @Transactional
    public SealResult sealRunWithResult(Long runId, Long workspaceId) {
        TeamRunEntity run = requireRun(runId, workspaceId);
        if (!TeamRunStatus.PLANNING.equals(run.getStatus())) {
            return new SealResult(run, false);
        }
        long taskCount = taskMapper.selectCount(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getRunId, runId));
        if (taskCount == 0) {
            throw new IllegalStateException("cannot seal a team run without tasks");
        }

        LocalDateTime startedAt = LocalDateTime.now();
        int changed = runMapper.update(null, Wrappers.<TeamRunEntity>lambdaUpdate()
                .eq(TeamRunEntity::getId, runId)
                .eq(TeamRunEntity::getStatus, TeamRunStatus.PLANNING)
                .set(TeamRunEntity::getStatus, TeamRunStatus.RUNNING)
                .set(TeamRunEntity::getStartedAt, startedAt));
        if (changed == 1) {
            run.setStatus(TeamRunStatus.RUNNING);
            run.setStartedAt(startedAt);
            return new SealResult(run, true);
        }
        TeamRunEntity current = requireRun(runId, workspaceId);
        if (!TeamRunStatus.PLANNING.equals(current.getStatus())) {
            return new SealResult(current, false);
        }
        throw new IllegalStateException("failed to seal team run: " + runId);
    }

    @Transactional
    public TeamRunEntity markFinalized(Long runId, Long workspaceId, String finalSummary) {
        TeamRunEntity run = requireRun(runId, workspaceId);
        if (TeamRunStatus.isTerminal(run.getStatus())) {
            return run;
        }
        if (!TeamRunStatus.FINALIZING.equals(run.getStatus())) {
            throw new IllegalStateException("team run is not finalizing: " + runId);
        }
        String outcome = metadata(run.getMetadata()).getStr("projectedOutcome");
        if (!FINAL_OUTCOMES.contains(outcome)) {
            throw new IllegalStateException("team run has no valid projected outcome: " + runId);
        }

        LocalDateTime completedAt = LocalDateTime.now();
        int changed = runMapper.update(null, Wrappers.<TeamRunEntity>lambdaUpdate()
                .eq(TeamRunEntity::getId, runId)
                .eq(TeamRunEntity::getStatus, TeamRunStatus.FINALIZING)
                .set(TeamRunEntity::getStatus, outcome)
                .set(TeamRunEntity::getFinalSummary, finalSummary)
                .set(TeamRunEntity::getCompletedAt, completedAt));
        if (changed == 1) {
            run.setStatus(outcome);
            run.setFinalSummary(finalSummary);
            run.setCompletedAt(completedAt);
            return run;
        }
        TeamRunEntity current = requireRun(runId, workspaceId);
        if (TeamRunStatus.isTerminal(current.getStatus())) {
            return current;
        }
        throw new IllegalStateException("failed to finalize team run: " + runId);
    }

    @Transactional
    public TeamRunEntity cancelRun(Long runId, Long workspaceId, String reason) {
        return cancelRunWithResult(runId, workspaceId, reason).run();
    }

    @Transactional
    public CancelResult cancelRunWithResult(Long runId, Long workspaceId, String reason) {
        TeamRunEntity run = requireRun(runId, workspaceId);
        if (TeamRunStatus.isTerminal(run.getStatus())) {
            return new CancelResult(run, false);
        }
        LocalDateTime completedAt = LocalDateTime.now();
        int changed = runMapper.update(null, Wrappers.<TeamRunEntity>lambdaUpdate()
                .eq(TeamRunEntity::getId, runId)
                .notIn(TeamRunEntity::getStatus, TeamRunStatus.TERMINAL)
                .set(TeamRunEntity::getStatus, TeamRunStatus.CANCELLED)
                .set(TeamRunEntity::getStopReason, reason)
                .set(TeamRunEntity::getCompletedAt, completedAt));
        if (changed == 1) {
            run.setStatus(TeamRunStatus.CANCELLED);
            run.setStopReason(reason);
            run.setCompletedAt(completedAt);
            return new CancelResult(run, true);
        }
        return new CancelResult(requireRun(runId, workspaceId), false);
    }

    public TeamRunView buildView(TeamRunEntity run) {
        List<TeamTaskEntity> tasks = tasksForRun(run.getId());
        TeamRunStateMachine.Projection projection = stateMachine.project(run, tasks);
        return TeamRunViewFactory.create(run, projection.status(), projection.progress(), tasks, true);
    }

    private List<TeamTaskEntity> tasksForRun(Long runId) {
        return taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getRunId, runId)
                .orderByAsc(TeamTaskEntity::getTaskNumber));
    }

    private void validateCreate(TeamRunCreateCommand command) {
        if (command == null || command.getTeamId() == null || command.getWorkspaceId() == null
                || command.getLeadAgentId() == null) {
            throw new IllegalArgumentException("team, workspace, and lead are required");
        }
        AgentTeamEntity team = teamService.getTeam(command.getTeamId());
        if (team == null || !TeamService.STATUS_ACTIVE.equals(team.getStatus())) {
            throw new IllegalArgumentException("team not found or not active: " + command.getTeamId());
        }
        if (!command.getWorkspaceId().equals(team.getWorkspaceId())) {
            throw new IllegalArgumentException("team is not in workspace: " + command.getWorkspaceId());
        }
        if (!command.getLeadAgentId().equals(team.getLeadAgentId())) {
            throw new IllegalArgumentException("agent is not the team lead: " + command.getLeadAgentId());
        }
        if (command.getLeadConversationId() == null || command.getLeadConversationId().isBlank()) {
            throw new IllegalArgumentException("lead conversation is required");
        }
        if (command.getObjective() == null || command.getObjective().isBlank()) {
            throw new IllegalArgumentException("objective is required");
        }
    }

    private TeamRunEntity findByOrigin(Long workspaceId, String conversationId, Long originMessageId) {
        if (originMessageId == null) {
            return null;
        }
        return runMapper.selectOne(Wrappers.<TeamRunEntity>lambdaQuery()
                .eq(TeamRunEntity::getWorkspaceId, workspaceId)
                .eq(TeamRunEntity::getLeadConversationId, conversationId)
                .eq(TeamRunEntity::getOriginMessageId, originMessageId));
    }

    private String deriveTitle(TeamRunCreateCommand command) {
        String title = command.getTitle() == null || command.getTitle().isBlank()
                ? command.getObjective().trim() : command.getTitle().trim();
        return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH);
    }

    private JSONObject metadata(String value) {
        if (value == null || value.isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(value);
        } catch (RuntimeException invalidJson) {
            return new JSONObject();
        }
    }
}
