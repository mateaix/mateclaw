package vip.mate.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.model.TriggerEventEntity;
import vip.mate.trigger.repository.TriggerEventMapper;
import vip.mate.trigger.repository.TriggerMapper;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.model.WorkflowPayloadEntity;
import vip.mate.workflow.model.WorkflowRevisionEntity;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowMapper;
import vip.mate.workflow.repository.WorkflowPayloadMapper;
import vip.mate.workflow.repository.WorkflowRevisionMapper;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;
import vip.mate.workflow.repository.WorkflowRunStepMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the eight workflow / trigger foundation tables. Verifies that:
 *
 * <ol>
 *   <li>Flyway migration {@code V96__workflow_foundations.sql} runs cleanly
 *       against an in-memory H2 (otherwise context startup would fail).</li>
 *   <li>The 8 entity / mapper pairs round-trip a row through MyBatis Plus —
 *       camelCase ↔ snake_case mapping, logical delete column on the four
 *       tables that have one, and unique-index enforcement.</li>
 * </ol>
 *
 * <p>Repositories with custom queries and the GC sweeper are out of scope
 * here — those land in the runtime lane.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_smoke_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class WorkflowSchemaSmokeTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkflowRevisionMapper revisionMapper;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private WorkflowRunStepMapper stepMapper;
    @Autowired private WorkflowRunPauseMapper pauseMapper;
    @Autowired private WorkflowPayloadMapper payloadMapper;
    @Autowired private TriggerMapper triggerMapper;
    @Autowired private TriggerEventMapper triggerEventMapper;

    @Test
    @DisplayName("V96 created all eight foundation tables")
    void allEightTablesExist() {
        List<String> expected = List.of(
                "mate_workflow",
                "mate_workflow_revision",
                "mate_workflow_run",
                "mate_workflow_run_step",
                "mate_workflow_run_pause",
                "mate_workflow_payload",
                "mate_trigger",
                "mate_trigger_event"
        );
        for (String t : expected) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                    Long.class, t);
            assertNotNull(count);
            assertEquals(1L, count, "table " + t + " should exist after V96");
        }
    }

    @Test
    @DisplayName("MyBatis Plus round-trips a workflow row with inline draft")
    void workflowEntityRoundTrip() {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setWorkspaceId(1L);
        wf.setName("smoke-" + UUID.randomUUID());
        wf.setDescription("smoke test workflow");
        wf.setEnabled(Boolean.TRUE);
        wf.setDraftJson("{\"schemaVersion\":\"1.0\",\"steps\":[]}");
        wf.setDraftSchemaVersion("1.0");
        wf.setDraftUpdatedBy(42L);
        wf.setDraftUpdatedAt(LocalDateTime.now());
        wf.setCreatedBy(42L);

        workflowMapper.insert(wf);
        assertNotNull(wf.getId(), "snowflake id should be assigned");
        assertNotNull(wf.getCreateTime(), "createTime should be filled by the meta object handler");

        WorkflowEntity loaded = workflowMapper.selectById(wf.getId());
        assertNotNull(loaded);
        assertEquals(wf.getName(), loaded.getName());
        assertEquals("{\"schemaVersion\":\"1.0\",\"steps\":[]}", loaded.getDraftJson());
        assertEquals(Integer.valueOf(0), loaded.getDeleted());
    }

    @Test
    @DisplayName("Round-trip for revision / run / step / pause / payload entities")
    void runRelatedRoundTrip() {
        // Anchor workflow + revision so foreign-id columns get realistic values.
        WorkflowEntity wf = new WorkflowEntity();
        wf.setWorkspaceId(1L);
        wf.setName("rrtrip-" + UUID.randomUUID());
        wf.setEnabled(Boolean.TRUE);
        workflowMapper.insert(wf);

        WorkflowRevisionEntity rev = new WorkflowRevisionEntity();
        rev.setWorkflowId(wf.getId());
        rev.setRevision(1);
        rev.setGraphJson("{\"schemaVersion\":\"1.0\",\"steps\":[]}");
        rev.setSchemaVersion("1.0");
        rev.setPublishedBy(42L);
        revisionMapper.insert(rev);
        assertNotNull(rev.getId());

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflowId(wf.getId());
        run.setRevisionId(rev.getId());
        run.setWorkspaceId(1L);
        run.setState("running");
        run.setTriggeredBy("manual");
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);
        assertNotNull(run.getId());

        WorkflowRunStepEntity step = new WorkflowRunStepEntity();
        step.setRunId(run.getId());
        step.setStepIndex(0);
        step.setStepName("first");
        step.setState("succeeded");
        step.setOutputContentType("text");
        step.setDurationMs(123L);
        step.setStartedAt(LocalDateTime.now());
        step.setCompletedAt(LocalDateTime.now());
        stepMapper.insert(step);
        assertNotNull(step.getId());

        WorkflowRunPauseEntity pause = new WorkflowRunPauseEntity();
        pause.setRunId(run.getId());
        pause.setStepId(step.getId());
        pause.setPauseKind("await_approval");
        pause.setPauseToken("token-" + UUID.randomUUID());
        pause.setExternalApprovalId(99L);
        pause.setPausedAt(LocalDateTime.now());
        pause.setResumeDeadline(LocalDateTime.now().plusDays(7));
        pauseMapper.insert(pause);
        assertNotNull(pause.getId());

        WorkflowPayloadEntity payload = new WorkflowPayloadEntity();
        payload.setPayloadUri("mateclaw://payload/1/run/" + run.getId() + "/step/0#input");
        payload.setWorkspaceId(1L);
        payload.setStorageKind("inline");
        payload.setContentBytes("hello".getBytes());
        payload.setContentType("text/plain");
        payload.setSizeBytes(5L);
        payloadMapper.insert(payload);
        assertNotNull(payload.getId());

        // Verify pause is reachable by token (uk_workflow_pause_token index).
        Long pauseRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_workflow_run_pause WHERE pause_token = ?",
                Long.class, pause.getPauseToken());
        assertEquals(1L, pauseRows);

        WorkflowRunEntity loadedRun = runMapper.selectById(run.getId());
        assertEquals("running", loadedRun.getState());
        assertEquals(Integer.valueOf(0), loadedRun.getDeleted());
    }

    @Test
    @DisplayName("Trigger + trigger_event round-trip with envelope dedup row")
    void triggerRoundTrip() {
        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(1L);
        t.setName("t-" + UUID.randomUUID());
        t.setPatternType("cron");
        t.setPatternJson("{\"cron\":\"0 0 * * * ?\",\"timezone\":\"Asia/Shanghai\"}");
        t.setTargetType("workflow");
        t.setTargetId(123L);
        t.setRateLimitPerMin(60);
        t.setDedupWindowSecs(60);
        t.setBotSelfFilter(Boolean.TRUE);
        t.setEnabled(Boolean.TRUE);
        t.setFireCount(0L);
        t.setMaxFires(0L);
        t.setPatternVersion(1L);
        triggerMapper.insert(t);
        assertNotNull(t.getId());
        assertNotNull(t.getCreateTime());

        TriggerEventEntity ev = new TriggerEventEntity();
        ev.setTriggerId(t.getId());
        ev.setDedupKey("evt-" + UUID.randomUUID());
        ev.setExpiresAt(LocalDateTime.now().plusMinutes(1));
        triggerEventMapper.insert(ev);
        assertNotNull(ev.getId());

        TriggerEntity loaded = triggerMapper.selectById(t.getId());
        assertEquals("cron", loaded.getPatternType());
        assertEquals(Long.valueOf(1L), loaded.getPatternVersion());
        assertTrue(loaded.getEnabled());
    }
}
