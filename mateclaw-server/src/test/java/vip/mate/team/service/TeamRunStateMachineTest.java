package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TeamRunStateMachineTest {

    private final TeamRunStateMachine stateMachine = new TeamRunStateMachine();

    @ParameterizedTest(name = "{0}")
    @MethodSource("projections")
    void projectsTaskState(String name, String runStatus, List<TeamTaskEntity> tasks,
                           String expectedStatus, String expectedOutcome,
                           int done, int failed, int inReview, int percent) {
        TeamRunEntity run = run(runStatus);

        TeamRunStateMachine.Projection projection = stateMachine.project(run, tasks);

        assertEquals(expectedStatus, projection.status());
        assertEquals(expectedOutcome, projection.projectedOutcome());
        assertEquals(new TeamRunView.Progress(tasks.size(), done, failed, inReview, percent),
                projection.progress());
    }

    static Stream<Arguments> projections() {
        return Stream.of(
                Arguments.of("empty planning run", TeamRunStatus.PLANNING, tasks(),
                        TeamRunStatus.PLANNING, null, 0, 0, 0, 0),
                Arguments.of("planning run with tasks", TeamRunStatus.PLANNING,
                        tasks(TeamTaskStatus.PENDING), TeamRunStatus.PLANNING, null, 0, 0, 0, 0),
                Arguments.of("active tasks", TeamRunStatus.RUNNING,
                        tasks(TeamTaskStatus.COMPLETED, TeamTaskStatus.IN_PROGRESS),
                        TeamRunStatus.RUNNING, null, 1, 0, 0, 50),
                Arguments.of("blocked tasks are active", TeamRunStatus.AWAITING_REVIEW,
                        tasks(TeamTaskStatus.BLOCKED), TeamRunStatus.RUNNING, null, 0, 0, 0, 0),
                Arguments.of("review only", TeamRunStatus.RUNNING,
                        tasks(TeamTaskStatus.COMPLETED, TeamTaskStatus.IN_REVIEW),
                        TeamRunStatus.AWAITING_REVIEW, null, 1, 0, 1, 50),
                Arguments.of("all completed", TeamRunStatus.RUNNING,
                        tasks(TeamTaskStatus.COMPLETED, TeamTaskStatus.COMPLETED),
                        TeamRunStatus.FINALIZING, TeamRunStatus.COMPLETED, 2, 0, 0, 100),
                Arguments.of("mixed completed and failed", TeamRunStatus.RUNNING,
                        tasks(TeamTaskStatus.COMPLETED, TeamTaskStatus.FAILED),
                        TeamRunStatus.FINALIZING, TeamRunStatus.PARTIAL, 1, 1, 0, 50),
                Arguments.of("mixed completed and cancelled", TeamRunStatus.RUNNING,
                        tasks(TeamTaskStatus.COMPLETED, TeamTaskStatus.CANCELLED),
                        TeamRunStatus.FINALIZING, TeamRunStatus.PARTIAL, 1, 1, 0, 50),
                Arguments.of("no successful tasks", TeamRunStatus.RUNNING,
                        tasks(TeamTaskStatus.FAILED, TeamTaskStatus.CANCELLED),
                        TeamRunStatus.FINALIZING, TeamRunStatus.FAILED, 0, 2, 0, 0)
        );
    }

    @Test
    void cancelledRunIsImmutable() {
        TeamRunStateMachine.Projection projection = stateMachine.project(
                run(TeamRunStatus.CANCELLED), tasks(TeamTaskStatus.PENDING));

        assertEquals(TeamRunStatus.CANCELLED, projection.status());
        assertNull(projection.projectedOutcome());
    }

    @Test
    void otherTerminalRunsAreImmutable() {
        for (String status : List.of(TeamRunStatus.COMPLETED, TeamRunStatus.PARTIAL, TeamRunStatus.FAILED)) {
            assertEquals(status, stateMachine.project(run(status), tasks(TeamTaskStatus.PENDING)).status());
        }
    }

    @Test
    void emptyNonPlanningRunKeepsItsCurrentNonTerminalStatus() {
        for (String status : List.of(
                TeamRunStatus.RUNNING, TeamRunStatus.AWAITING_REVIEW, TeamRunStatus.FINALIZING)) {
            TeamRunStateMachine.Projection projection = stateMachine.project(run(status), tasks());

            assertEquals(status, projection.status());
            assertNull(projection.projectedOutcome());
        }
    }

    @Test
    void unknownTaskStatusKeepsCurrentNonTerminalStatus() {
        TeamRunStateMachine.Projection projection = stateMachine.project(
                run(TeamRunStatus.RUNNING), tasks("custom_status"));

        assertEquals(TeamRunStatus.RUNNING, projection.status());
        assertNull(projection.projectedOutcome());
        assertEquals(new TeamRunView.Progress(1, 0, 0, 0, 0), projection.progress());
    }

    private static TeamRunEntity run(String status) {
        TeamRunEntity run = new TeamRunEntity();
        run.setStatus(status);
        return run;
    }

    private static List<TeamTaskEntity> tasks(String... statuses) {
        return Arrays.stream(statuses).map(status -> {
            TeamTaskEntity task = new TeamTaskEntity();
            task.setStatus(status);
            return task;
        }).toList();
    }
}
