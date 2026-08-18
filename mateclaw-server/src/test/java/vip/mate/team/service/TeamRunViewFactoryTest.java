package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TeamRunViewFactoryTest {

    private static final String REPORT_URL = "/api/v1/files/generated/report.pdf";

    @Test
    void summaryProjectionIncludesLightweightTasksAndDropsHeavyFields() {
        TeamTaskEntity task = task(101L, 201L, "{\"phase\":\"research\"}");
        task.setRunId(1L);
        task.setSubject("Collect evidence");
        task.setDescription("large prompt");
        task.setProgressPercent(65);
        task.setProgressStep("verifying sources");
        task.setReason("waiting for source");
        task.setConversationId("worker-101");
        task.setResult("large result");
        task.setCreateTime(java.time.LocalDateTime.of(2026, 8, 14, 10, 0));
        task.setUpdateTime(java.time.LocalDateTime.of(2026, 8, 14, 10, 5));

        TeamRunView view = TeamRunViewFactory.create(run("{}"), TeamRunStatus.RUNNING,
                new TeamRunView.Progress(1, 0, 0, 0, 65), List.of(task), false);

        assertEquals("summary", view.projectionCompleteness());
        assertEquals(1, view.tasks().size());
        TeamRunView.Task summary = view.tasks().getFirst();
        assertEquals(101L, summary.id());
        assertEquals(1L, summary.runId());
        assertEquals(TeamTaskStatus.COMPLETED, summary.status());
        assertEquals(201L, summary.assigneeAgentId());
        assertEquals("worker-101", summary.conversationId());
        assertEquals("Collect evidence", summary.subject());
        assertEquals(65, summary.progressPercent());
        assertEquals("verifying sources", summary.progressStep());
        assertEquals("waiting for source", summary.reason());
        assertNull(summary.metadata());
        assertEquals(java.time.LocalDateTime.of(2026, 8, 14, 10, 0), summary.createTime());
        assertEquals(java.time.LocalDateTime.of(2026, 8, 14, 10, 5), summary.updateTime());
        assertNull(summary.description());
        assertNull(summary.result());
    }

    @Test
    void fullAndSummaryOutcomeQualityUseStableTaskStatusEvidence() {
        TeamTaskEntity completed = task(101L, 201L, null);
        completed.setResult("available only in full projection");
        TeamTaskEntity failed = task(102L, 202L, null);
        failed.setStatus(TeamTaskStatus.FAILED);

        TeamRunView full = TeamRunViewFactory.create(run("{}"), TeamRunStatus.PARTIAL,
                new TeamRunView.Progress(2, 1, 1, 0, 100), List.of(completed, failed), true);
        completed.setResult(null);
        TeamRunView summary = TeamRunViewFactory.create(run("{}"), TeamRunStatus.PARTIAL,
                new TeamRunView.Progress(2, 1, 1, 0, 100), List.of(completed, failed), false);

        assertEquals("partial", full.outcomeQuality());
        assertEquals(full.outcomeQuality(), summary.outcomeQuality());
    }

    @Test
    void aggregatesRunOnlyDeliverables() {
        TeamRunEntity run = run("{\"deliverables\":[{\"name\":\"report.pdf\","
                + "\"url\":\"" + REPORT_URL + "\"}]}");

        TeamRunView view = project(run, List.of());

        assertEquals(1, view.deliverables().size());
        assertEquals("report.pdf", view.deliverables().getFirst().name());
        assertEquals(REPORT_URL, view.deliverables().getFirst().url());
        assertEquals(List.of(), view.deliverables().getFirst().sourceTaskIds());
        assertEquals(List.of(), view.deliverables().getFirst().sourceAgentIds());
    }

    @Test
    void mergesExplicitAndImplicitSourcesForDuplicateRunAndTaskDeliverables() {
        TeamRunEntity run = run("{\"deliverables\":[{\"name\":\"report.pdf\","
                + "\"url\":\"" + REPORT_URL + "\","
                + "\"sourceTaskIds\":[90],\"sourceAgentIds\":[190]}]}");
        TeamTaskEntity task = task(101L, 201L,
                "{\"deliverables\":[{\"name\":\"report.pdf\","
                        + "\"url\":\"" + REPORT_URL + "\","
                        + "\"sourceTaskIds\":[91,90],\"sourceAgentIds\":[191,190]}]}");

        TeamRunView view = project(run, List.of(task));

        assertEquals(1, view.deliverables().size());
        assertEquals(List.of(90L, 91L, 101L), view.deliverables().getFirst().sourceTaskIds());
        assertEquals(List.of(190L, 191L, 201L), view.deliverables().getFirst().sourceAgentIds());
    }

    @Test
    void deduplicatesBySafeUrlWhenNamesDifferAndMergesSources() {
        TeamRunEntity run = run("{\"deliverables\":[{\"name\":\"draft.pdf\","
                + "\"url\":\"" + REPORT_URL + "\",\"sourceTaskIds\":[90]}]}");
        TeamTaskEntity task = task(101L, 201L,
                "{\"deliverables\":[{\"name\":\"final-report.pdf\","
                        + "\"url\":\"" + REPORT_URL + "\"}]}");

        TeamRunView view = project(run, List.of(task));

        assertEquals(1, view.deliverables().size());
        assertEquals("draft.pdf", view.deliverables().getFirst().name());
        assertEquals(List.of(90L, 101L), view.deliverables().getFirst().sourceTaskIds());
        assertEquals(List.of(201L), view.deliverables().getFirst().sourceAgentIds());
    }

    @Test
    void fillsMissingCreatedAtAndUsesTheStrongestVerificationStatusFromDuplicates() {
        TeamRunEntity run = run("{\"deliverables\":[{\"name\":\"report.pdf\","
                + "\"url\":\"" + REPORT_URL + "\"}]}");
        TeamTaskEntity verified = task(101L, 201L,
                "{\"deliverables\":[{\"name\":\"report.pdf\","
                        + "\"url\":\"" + REPORT_URL + "\","
                        + "\"createdAt\":\"2026-08-14T12:30:00\","
                        + "\"verificationStatus\":\"verified\"}]}");
        TeamTaskEntity degraded = task(102L, 202L,
                "{\"deliverables\":[{\"name\":\"report.pdf\","
                        + "\"url\":\"" + REPORT_URL + "\","
                        + "\"createdAt\":\"2026-08-14T12:31:00\","
                        + "\"verificationStatus\":\"failed\"}]}");

        TeamRunView view = project(run, List.of(verified, degraded));

        assertEquals(1, view.deliverables().size());
        assertEquals(java.time.LocalDateTime.of(2026, 8, 14, 12, 30),
                view.deliverables().getFirst().createdAt());
        assertEquals("verified", view.deliverables().getFirst().verificationStatus());
        assertEquals(List.of(101L, 102L), view.deliverables().getFirst().sourceTaskIds());
        assertEquals(List.of(201L, 202L), view.deliverables().getFirst().sourceAgentIds());
    }

    @Test
    void malformedRunAndTaskMetadataAreIgnoredWithoutDroppingOtherValidDeliverables() {
        TeamTaskEntity malformed = task(101L, 201L, "{not-json");
        TeamTaskEntity valid = task(102L, 202L,
                "{\"deliverables\":[{\"name\":\"valid.csv\","
                        + "\"url\":\"/api/v1/files/generated/valid.csv\"}]}");

        TeamRunView view = project(run("{"), List.of(malformed, valid));

        assertEquals(1, view.deliverables().size());
        assertEquals("valid.csv", view.deliverables().getFirst().name());
        assertEquals(List.of(102L), view.deliverables().getFirst().sourceTaskIds());
        assertEquals(List.of(202L), view.deliverables().getFirst().sourceAgentIds());
    }

    @Test
    void rejectsAbsoluteSchemeRelativeAndEncodedTraversalDeliverableUrls() {
        TeamRunEntity run = run("{\"deliverables\":["
                + "{\"name\":\"safe\",\"url\":\"" + REPORT_URL + "\"},"
                + "{\"name\":\"http\",\"url\":\"http://files.test/api/v1/files/generated/http.pdf\"},"
                + "{\"name\":\"https\",\"url\":\"https://files.test/api/v1/files/generated/https.pdf\"},"
                + "{\"name\":\"relative\",\"url\":\"//files.test/api/v1/files/generated/relative.pdf\"},"
                + "{\"name\":\"encoded\",\"url\":\"/api/v1/files/generated/%252e%252e/secret.txt\"}]} ");

        TeamRunView view = project(run, List.of());

        assertEquals(List.of(REPORT_URL),
                view.deliverables().stream().map(TeamRunView.Deliverable::url).toList());
    }

    @Test
    void mapsUnknownVerificationStatusToAvailableWithoutLeakingMetadataValue() {
        TeamRunEntity run = run("{\"deliverables\":[{\"name\":\"report.pdf\","
                + "\"url\":\"" + REPORT_URL + "\","
                + "\"verificationStatus\":\"INTERNAL_ONLY\"}]}");

        TeamRunView view = project(run, List.of());

        assertEquals("available", view.deliverables().getFirst().verificationStatus());
    }

    @Test
    void acceptsOnlyPositiveLongSourceIdsAndKeepsValidMixedArrayEntries() {
        TeamRunEntity run = run("{\"deliverables\":[{\"name\":\"report.pdf\","
                + "\"url\":\"" + REPORT_URL + "\","
                + "\"sourceTaskIds\":[1,2.5,0,-3,9223372036854775808,\"4\",\"5.5\",\"bad\"],"
                + "\"sourceAgentIds\":[6,0,-7,9223372036854775808,\"8\",\"9223372036854775808\"]}]}");

        TeamRunView view = project(run, List.of());

        assertEquals(List.of(1L, 4L), view.deliverables().getFirst().sourceTaskIds());
        assertEquals(List.of(6L, 8L), view.deliverables().getFirst().sourceAgentIds());
    }

    @Test
    void preservesLiteralPlusAndKeepsPlusAndSpaceUrlIdentitiesDistinct() {
        TeamRunEntity run = run("{\"deliverables\":["
                + "{\"name\":\"literal-plus\",\"url\":\"/api/v1/files/generated/a+b.pdf\",\"sourceTaskIds\":[1]},"
                + "{\"name\":\"encoded-plus\",\"url\":\"/api/v1/files/generated/a%2Bb.pdf\",\"sourceTaskIds\":[2]},"
                + "{\"name\":\"encoded-space\",\"url\":\"/api/v1/files/generated/a%20b.pdf\",\"sourceTaskIds\":[3]}]}");

        TeamRunView view = project(run, List.of());

        assertEquals(2, view.deliverables().size());
        assertEquals(List.of("/api/v1/files/generated/a+b.pdf", "/api/v1/files/generated/a%20b.pdf"),
                view.deliverables().stream().map(TeamRunView.Deliverable::url).toList());
        assertEquals(List.of(1L, 2L), view.deliverables().getFirst().sourceTaskIds());
        assertEquals(List.of(3L), view.deliverables().get(1).sourceTaskIds());
    }

    private static TeamRunView project(TeamRunEntity run, List<TeamTaskEntity> tasks) {
        return TeamRunViewFactory.create(run, run.getStatus(),
                new TeamRunView.Progress(tasks.size(), 0, 0, 0, 0), tasks, true);
    }

    private static TeamRunEntity run(String metadata) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(1L);
        run.setTeamId(2L);
        run.setWorkspaceId(3L);
        run.setLeadAgentId(4L);
        run.setStatus(TeamRunStatus.RUNNING);
        run.setMetadata(metadata);
        return run;
    }

    private static TeamTaskEntity task(Long id, Long assigneeId, String metadata) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(id);
        task.setTeamId(2L);
        task.setRunId(1L);
        task.setStatus(TeamTaskStatus.COMPLETED);
        task.setAssigneeAgentId(assigneeId);
        task.setMetadata(metadata);
        return task;
    }
}
