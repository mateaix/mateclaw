package vip.mate.team.service;

import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.List;
import java.util.Set;

/** Pure task-to-run lifecycle projection. */
public final class TeamRunStateMachine {

    private static final Set<String> ACTIVE_TASK_STATUSES = Set.of(
            TeamTaskStatus.PENDING,
            TeamTaskStatus.BLOCKED,
            TeamTaskStatus.IN_PROGRESS,
            TeamTaskStatus.STALE
    );
    private static final Set<String> KNOWN_TASK_STATUSES = Set.of(
            TeamTaskStatus.PENDING,
            TeamTaskStatus.BLOCKED,
            TeamTaskStatus.IN_PROGRESS,
            TeamTaskStatus.IN_REVIEW,
            TeamTaskStatus.COMPLETED,
            TeamTaskStatus.FAILED,
            TeamTaskStatus.CANCELLED,
            TeamTaskStatus.STALE
    );

    public Projection project(TeamRunEntity run, List<TeamTaskEntity> tasks) {
        List<TeamTaskEntity> safeTasks = tasks == null ? List.of() : tasks;
        TeamRunView.Progress progress = progress(safeTasks);
        String currentStatus = run.getStatus();

        if (TeamRunStatus.isTerminal(currentStatus) || TeamRunStatus.PLANNING.equals(currentStatus)) {
            return new Projection(currentStatus, null, progress);
        }
        if (safeTasks.isEmpty()
                || safeTasks.stream().anyMatch(task -> !KNOWN_TASK_STATUSES.contains(task.getStatus()))) {
            return new Projection(currentStatus, null, progress);
        }
        if (safeTasks.stream().anyMatch(task -> ACTIVE_TASK_STATUSES.contains(task.getStatus()))) {
            return new Projection(TeamRunStatus.RUNNING, null, progress);
        }
        if (safeTasks.stream().anyMatch(task -> TeamTaskStatus.IN_REVIEW.equals(task.getStatus()))) {
            return new Projection(TeamRunStatus.AWAITING_REVIEW, null, progress);
        }

        String outcome = progress.done() == progress.total()
                ? TeamRunStatus.COMPLETED
                : progress.done() > 0 ? TeamRunStatus.PARTIAL : TeamRunStatus.FAILED;
        return new Projection(TeamRunStatus.FINALIZING, outcome, progress);
    }

    private TeamRunView.Progress progress(List<TeamTaskEntity> tasks) {
        int done = 0;
        int failed = 0;
        int inReview = 0;
        for (TeamTaskEntity task : tasks) {
            if (TeamTaskStatus.COMPLETED.equals(task.getStatus())) {
                done++;
            } else if (TeamTaskStatus.FAILED.equals(task.getStatus())
                    || TeamTaskStatus.CANCELLED.equals(task.getStatus())) {
                failed++;
            } else if (TeamTaskStatus.IN_REVIEW.equals(task.getStatus())) {
                inReview++;
            }
        }
        int total = tasks.size();
        int percent = total == 0 ? 0 : done * 100 / total;
        return new TeamRunView.Progress(total, done, failed, inReview, percent);
    }

    public record Projection(String status, String projectedOutcome, TeamRunView.Progress progress) {
    }
}
