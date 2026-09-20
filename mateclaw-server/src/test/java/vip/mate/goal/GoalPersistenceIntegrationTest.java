package vip.mate.goal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import vip.mate.memory.spi.MemoryManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.MateClawApplication;
import vip.mate.approval.event.ApprovalResolutionEvent;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.service.GoalService;
import vip.mate.goal.service.GoalContinuationStore;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for goal persistence and transaction boundaries:
 *
 * <ol>
 *   <li>{@code GoalStatus} persists as lowercase strings ({@code "active"}
 *       etc., NOT {@code "ACTIVE"}). The V120 predicate unique index
 *       compares {@code status = 'active'} as a literal — any uppercase
 *       write would silently defeat the uniqueness guarantee.</li>
 *   <li>The {@code uk_agent_goal_active_conv} unique index rejects a
 *       second active-row insert for the same conversation. Service-layer
 *       pre-check is a UX nicety; this is the source of truth.</li>
 *   <li>Resume and approval decisions commit both goal and continuation state
 *       before independent transaction observers are allowed to continue.</li>
 * </ol>
 *
 * <p>Uses an in-memory H2 MySQL-compat database so Flyway runs V120
 * exactly as it would in dev. The {@code DATABASE_TO_LOWER=TRUE} flag is
 * standard across mateclaw's other Spring tests.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:goal_persistence_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.goal.enabled=false", "mateclaw.plugin.enabled=false", "mateclaw.skill.workspace.auto-init=false",
        "mateclaw.skill.workspace.root=${java.io.tmpdir}/mateclaw-goal-persistence-skills-${random.uuid}"
})
class GoalPersistenceIntegrationTest {

    @MockBean private MemoryManager memory;
    @Autowired private GoalService goalService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GoalContinuationStore continuations;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEventPublisher events;

    private GoalCreateRequest req(String convId, String title) {
        GoalCreateRequest r = new GoalCreateRequest();
        r.setConversationId(convId);
        r.setAgentId(1L);
        r.setWorkspaceId(1L);
        r.setTitle(title);
        r.setDescription("desc");
        return r;
    }

    @Test
    void lateBootstrapCannotReplaceUserCriterionCommittedWhileModelWasRunning() {
        GoalEntity created = goalService.create(req("bootstrap-append-boundary", "prepare a report"), "alice");
        // The evaluator started with an empty checklist. A user append commits
        // before its delayed bootstrap result reaches recordEvaluation.
        goalService.appendCriterion(created.getId(), "include the user requested appendix", "alice");
        var delayed = new vip.mate.goal.model.GoalEvaluationResult(0.0, "checklist created", "continue", false,
                "fixture", 1, 0, java.util.List.of(), java.util.List.of(
                new vip.mate.goal.model.GoalCriterion("C1", "model draft", false, "")));
        goalService.recordEvaluation(created.getId(), delayed, 2, 1);
        GoalEntity saved = goalService.getById(created.getId());
        var criteria = vip.mate.goal.model.GoalCriteriaCodec.parse(saved.getCriteria(), new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(1, criteria.size());
        assertEquals("include the user requested appendix", criteria.getFirst().text());
        assertEquals(1, saved.getEvalLlmCallsUsed());
        assertEquals(2, saved.getAgentLlmCallsUsed());
    }

    @Test
    void lateVerdictProjectsProgressFromCurrentChecklist() throws Exception {
        GoalEntity created = goalService.create(req("late-verdict-current-progress", "prepare a report"), "alice");
        goalService.appendCriterion(created.getId(), "write the report", "alice");
        // The model evaluated only C1. A second condition commits before the result.
        goalService.appendCriterion(created.getId(), "include an appendix", "alice");
        var delayed = new vip.mate.goal.model.GoalEvaluationResult(1.0, "", "completed", true,
                "fixture", 1, 0, java.util.List.of(
                new vip.mate.goal.model.GoalChecklistVerdict.CriterionVerdict("C1", true, "report written")), null);
        goalService.recordEvaluation(created.getId(), delayed, 2, 1);
        GoalEntity saved = goalService.getById(created.getId());
        assertEquals(0.5, saved.getCompletionScore());
        var event = goalService.listEvents(created.getId(), 20).stream()
                .filter(e -> "evaluated".equals(e.getEventType())).findFirst().orElseThrow();
        var detail = new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.getDetailJson());
        assertEquals(0.5, detail.get("completionScore").asDouble());
        assertEquals(1.0, detail.get("evaluatorScore").asDouble());
        org.junit.jupiter.api.Assertions.assertTrue(detail.get("gap").asText().contains("include an appendix"));
        org.junit.jupiter.api.Assertions.assertTrue(saved.getProgressSummary().contains("include an appendix"));
        assertEquals(GoalStatus.ACTIVE, saved.getStatus());
        assertEquals(1, saved.getEvalLlmCallsUsed());
        assertThrows(MateClawException.class, () -> goalService.markEvaluatedCompleted(created.getId(), delayed));
    }

    @Test
    void replacingExitCriteriaRevokesOldCompletion() {
        GoalEntity created = goalService.create(req("edited-definition-completion", "report"), "alice");
        goalService.appendCriterion(created.getId(), "old report", "alice");
        var passed = new vip.mate.goal.model.GoalEvaluationResult(1.0, "", "completed", true, "fixture", 1, 0,
                java.util.List.of(new vip.mate.goal.model.GoalChecklistVerdict.CriterionVerdict(
                        "C1", true, "old report written")), null);
        goalService.recordEvaluation(created.getId(), passed, 1, 1);
        var edit = new vip.mate.goal.model.GoalUpdateRequest();
        edit.setExitCriteria("require a different report with an appendix");
        goalService.update(created.getId(), edit, "alice");
        assertThrows(MateClawException.class, () -> goalService.markEvaluatedCompleted(created.getId(), passed));
        assertEquals(0.0, goalService.getById(created.getId()).getCompletionScore());
    }

    @Test
    void staleDraftAndVerdictCannotCrossDefinitionRevisionButCurrentOnesCan() {
        GoalEntity created = goalService.create(req("definition-revision-carriers", "report"), "alice");
        var oldDraft = new vip.mate.goal.model.GoalEvaluationResult(0.0, "draft", "continue", false,
                "fixture", 1, 0, java.util.List.of(), java.util.List.of(
                new vip.mate.goal.model.GoalCriterion("C1", "old report", false, "")));
        var edit = new vip.mate.goal.model.GoalUpdateRequest(); edit.setExitCriteria("new report");
        goalService.update(created.getId(), edit, "alice");
        assertEquals(1L, goalService.getById(created.getId()).getEvaluationRevision());
        goalService.recordEvaluation(created.getId(), oldDraft, 1, 1);
        assertEquals(null, goalService.getById(created.getId()).getCriteria());
        var newDraft = new vip.mate.goal.model.GoalEvaluationResult(0.0, "draft", "continue", false,
                "fixture", 1, 0, java.util.List.of(), java.util.List.of(
                new vip.mate.goal.model.GoalCriterion("C1", "new report", false, ""))).withEvaluationRevision(1);
        goalService.recordEvaluation(created.getId(), newDraft, 1, 1);
        var oldPass = new vip.mate.goal.model.GoalEvaluationResult(1.0, "", "completed", true, "fixture", 1, 0,
                java.util.List.of(new vip.mate.goal.model.GoalChecklistVerdict.CriterionVerdict(
                        "C1", true, "old report evidence")), null);
        goalService.recordEvaluation(created.getId(), oldPass, 1, 1);
        assertEquals(0.0, goalService.getById(created.getId()).getCompletionScore());
        var currentPass = new vip.mate.goal.model.GoalEvaluationResult(1.0, "", "completed", true, "fixture", 1, 0,
                java.util.List.of(new vip.mate.goal.model.GoalChecklistVerdict.CriterionVerdict(
                        "C1", true, "new report evidence")), null).withEvaluationRevision(1);
        goalService.recordEvaluation(created.getId(), currentPass, 1, 1);
        // Even after current criteria pass, an old result cannot perform the final transition.
        assertThrows(MateClawException.class, () -> goalService.markEvaluatedCompleted(created.getId(), oldPass));
        assertEquals(4, goalService.getById(created.getId()).getEvalLlmCallsUsed());
        assertEquals(GoalStatus.COMPLETED, goalService.markEvaluatedCompleted(created.getId(), currentPass).getStatus());
    }

    @Test
    void definitionRevisionSurvivesAbaAndIgnoresIdenticalAndBudgetEdits() {
        var request = req("definition-revision-aba", "report"); request.setExitCriteria("A");
        GoalEntity created = goalService.create(request, "alice");
        var edit = new vip.mate.goal.model.GoalUpdateRequest(); edit.setExitCriteria("A"); edit.setTurnBudget(12);
        goalService.update(created.getId(), edit, "alice");
        assertEquals(0L, goalService.getById(created.getId()).getEvaluationRevision());
        edit.setExitCriteria("B"); goalService.update(created.getId(), edit, "alice");
        edit.setExitCriteria("A"); goalService.update(created.getId(), edit, "alice");
        assertEquals(2L, goalService.getById(created.getId()).getEvaluationRevision());
        var stale = new vip.mate.goal.model.GoalEvaluationResult(0.0, "draft", "continue", false,
                "fixture", 1, 0, java.util.List.of(), java.util.List.of(
                new vip.mate.goal.model.GoalCriterion("C1", "A", false, "")));
        goalService.recordEvaluation(created.getId(), stale, 0, 1);
        assertEquals(null, goalService.getById(created.getId()).getCriteria());
    }

    @Test
    void contextEditPreservesCriterionTextButRevokesPriorPass() {
        GoalEntity created = goalService.create(req("definition-context-edit", "report"), "alice");
        goalService.appendCriterion(created.getId(), "user criterion", "alice");
        var passed = new vip.mate.goal.model.GoalEvaluationResult(1.0, "", "completed", true, "fixture", 1, 0,
                java.util.List.of(new vip.mate.goal.model.GoalChecklistVerdict.CriterionVerdict(
                        "C1", true, "old evidence")), null);
        goalService.recordEvaluation(created.getId(), passed, 0, 1);
        var edit = new vip.mate.goal.model.GoalUpdateRequest(); edit.setDescription("changed context");
        var saved = goalService.update(created.getId(), edit, "alice");
        var criteria = vip.mate.goal.model.GoalCriteriaCodec.parse(saved.getCriteria(), new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals("user criterion", criteria.getFirst().text());
        org.junit.jupiter.api.Assertions.assertFalse(criteria.getFirst().passed());
        assertEquals("", criteria.getFirst().evidence());
        assertEquals(1L, saved.getEvaluationRevision());
    }

    @Test
    void repeatedCompletionWritesOneEventAndSyncsMemoryOnce() {
        GoalEntity goal = goalService.create(req("completion-event-idempotence", "report"), "alice");
        goalService.appendCriterion(goal.getId(), "report", "alice");
        var passed = new vip.mate.goal.model.GoalEvaluationResult(1.0, "", "completed", true, "fixture", 1, 0,
                java.util.List.of(new vip.mate.goal.model.GoalChecklistVerdict.CriterionVerdict("C1", true, "report evidence")), null);
        goalService.recordEvaluation(goal.getId(), passed, 0, 1);
        goalService.markEvaluatedCompleted(goal.getId(), passed);
        goalService.markEvaluatedCompleted(goal.getId(), passed);
        goalService.markCompleted(goal.getId(), null);
        assertEquals(1L, goalService.listEvents(goal.getId(), 30).stream()
                .filter(event -> "completed".equals(event.getEventType())).count());
        org.mockito.Mockito.verify(memory, org.mockito.Mockito.times(1)).syncAll(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(goal.getConversationId()),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void abandonedGoalCannotBeRecordedAsCompletedByAnIdempotentCall() {
        GoalEntity goal = goalService.create(req("abandoned-no-completion-event", "report"), "alice");
        goalService.abandon(goal.getId(), "alice");
        assertEquals(GoalStatus.ABANDONED, goalService.markCompleted(goal.getId(), null).getStatus());
        assertEquals(0L, goalService.listEvents(goal.getId(), 30).stream()
                .filter(event -> "completed".equals(event.getEventType())).count());
        org.mockito.Mockito.verifyNoInteractions(memory);
    }

    private GoalEntity readyForCompletion(String conversation, String title) {
        GoalEntity goal = goalService.create(req(conversation, title), "alice");
        goalService.appendCriterion(goal.getId(), "report", "alice");
        var passed = new vip.mate.goal.model.GoalEvaluationResult(1.0, "", "completed", true, "fixture", 1, 0,
                java.util.List.of(new vip.mate.goal.model.GoalChecklistVerdict.CriterionVerdict("C1", true, "report evidence")), null);
        goalService.recordEvaluation(goal.getId(), passed, 0, 1);
        return goalService.getById(goal.getId());
    }

    @Test
    void appendedRequirementImmediatelyRefreshesProgressWithoutLosingPriorEvidence() {
        GoalEntity goal = readyForCompletion("append-progress", "report");
        assertEquals(1.0, goal.getCompletionScore());
        GoalEntity appended = goalService.appendCriterion(goal.getId(), "appendix", "alice");
        assertEquals(0.5, appended.getCompletionScore());
        assertEquals("Still missing: appendix", appended.getProgressSummary());
        var criteria = vip.mate.goal.model.GoalCriteriaCodec.parse(appended.getCriteria(), new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(true, criteria.getFirst().passed());
        assertEquals("report evidence", criteria.getFirst().evidence());
        assertEquals(false, criteria.getLast().passed());
        assertEquals(goal.getEvaluationRevision(), appended.getEvaluationRevision());
        GoalEntity again = goalService.appendCriterion(goal.getId(), "sources", "alice");
        assertEquals(1.0 / 3, again.getCompletionScore(), 0.00001);
        assertEquals("Still missing: appendix; sources", again.getProgressSummary());
        assertEquals(GoalStatus.ACTIVE, goalService.getById(goal.getId()).getStatus());
    }

    @Test
    void rolledBackCompletionDoesNotSyncMemory() {
        GoalEntity goal = readyForCompletion("completion-memory-rollback", "report");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            goalService.markCompleted(goal.getId(), null);
            status.setRollbackOnly();
        });
        assertEquals(GoalStatus.ACTIVE, goalService.getById(goal.getId()).getStatus());
        assertEquals(0L, goalService.listEvents(goal.getId(), 30).stream()
                .filter(event -> "completed".equals(event.getEventType())).count());
        org.mockito.Mockito.verifyNoInteractions(memory);
    }

    @Test
    void completionMemoryRunsAfterCommitAndItsDatabaseWritesCommitIndependently() {
        GoalEntity goal = readyForCompletion("completion-memory-after-commit", "original title");
        jdbc.execute("CREATE TABLE IF NOT EXISTS goal_memory_callback_probe(goal_id BIGINT PRIMARY KEY)");
        org.mockito.Mockito.doAnswer(call -> {
            inIndependentTransaction(() -> assertEquals(GoalStatus.COMPLETED,
                    goalService.getById(goal.getId()).getStatus()));
            assertEquals("[goal completed] original title", call.getArgument(2));
            jdbc.update("INSERT INTO goal_memory_callback_probe(goal_id) VALUES(?)", goal.getId());
            return null;
        }).when(memory).syncAll(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            GoalEntity returned = goalService.markCompleted(goal.getId(), null);
            returned.setTitle("mutated after return");
            org.mockito.Mockito.verifyNoInteractions(memory);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    inIndependentTransaction(() -> assertEquals(1, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM goal_memory_callback_probe WHERE goal_id=?", Integer.class, goal.getId())));
                }
            });
        });
        org.mockito.Mockito.verify(memory, org.mockito.Mockito.times(1)).syncAll(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void memoryFailureCannotUndoCommittedCompletion() {
        GoalEntity goal = readyForCompletion("completion-memory-failure", "report");
        org.mockito.Mockito.doThrow(new IllegalStateException("fixture failure")).when(memory).syncAll(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        goalService.markCompleted(goal.getId(), null);
        assertEquals(GoalStatus.COMPLETED, goalService.getById(goal.getId()).getStatus());
        assertEquals(1L, goalService.listEvents(goal.getId(), 30).stream()
                .filter(event -> "completed".equals(event.getEventType())).count());
    }

    @Test
    @DisplayName("GoalStatus values persist as lowercase literals — load-bearing for uk_agent_goal_active_conv")
    void status_persistsAsLowercaseString() {
        GoalEntity created = goalService.create(req("conv-status-1", "lower-case check"), "alice");
        String raw = jdbc.queryForObject(
                "SELECT status FROM mate_agent_goal WHERE id = ?",
                String.class, created.getId());
        assertEquals("active", raw,
                "GoalStatus must persist as lowercase 'active' — uppercase 'ACTIVE' would " +
                        "silently bypass the V120 predicate unique index uk_agent_goal_active_conv.");
    }

    @Test
    @DisplayName("Each terminal status also persists lowercase")
    void terminalStatuses_alsoPersistLowercase() {
        GoalEntity g = goalService.create(req("conv-status-terminal", "terminal check"), "alice");

        goalService.abandon(g.getId(), "alice");
        String s = jdbc.queryForObject(
                "SELECT status FROM mate_agent_goal WHERE id = ?",
                String.class, g.getId());
        assertEquals("abandoned", s);
    }

    @Test
    @DisplayName("Service rejects a second active goal on the same conversation (UX pre-check 409)")
    void servicePreCheck_blocksDuplicateActiveCreation() {
        goalService.create(req("conv-dup-1", "first"), "alice");
        MateClawException ex = assertThrows(MateClawException.class,
                () -> goalService.create(req("conv-dup-1", "second"), "alice"));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("DB unique index rejects a second active row even when service pre-check is bypassed")
    void uniqueIndex_isUltimateSourceOfTruth() {
        // First goal — via service so it gets a real ID + workspace + timestamps.
        goalService.create(req("conv-uq-1", "first"), "alice");

        // Second insertion — bypass the service entirely and write through
        // JdbcTemplate. Must hit DuplicateKeyException at the DB level.
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbc.update(
                    "INSERT INTO mate_agent_goal " +
                            "(id, conversation_id, agent_id, workspace_id, created_by, " +
                            " title, description, status, turn_budget, turns_used, " +
                            " llm_call_budget, agent_llm_calls_used, eval_llm_calls_used, " +
                            " auto_followup_enabled, followup_cooldown_seconds, " +
                            " version, deleted, create_time, update_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    99999L, "conv-uq-1", 1L, 1L, "alice",
                    "second", "desc", "active",
                    20, 0, 200, 0, 0,
                    false, 0,
                    0, 0, Timestamp.valueOf(now), Timestamp.valueOf(now));
            fail("Expected DuplicateKeyException from uk_agent_goal_active_conv");
        } catch (DuplicateKeyException expected) {
            // good
        }
    }

    @Test
    @DisplayName("A new active goal is allowed after the previous one entered a terminal state")
    void terminalGoal_releasesUniquenessSlot() {
        GoalEntity first = goalService.create(req("conv-recycle-1", "first"), "alice");
        goalService.abandon(first.getId(), "alice");

        // After abandon, the conversation should be free to host a new active goal.
        GoalEntity second = goalService.create(req("conv-recycle-1", "second"), "alice");
        assertNotNull(second);
        assertEquals(GoalStatus.ACTIVE, second.getStatus());
    }

    @Test
    void resumeCommitsGoalAndContinuationTogether() {
        GoalEntity goal = persistentGoal("conv-resume-transaction", "paused");
        goalService.pause(goal.getId(), "alice");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            goalService.resume(goal.getId(), "alice");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    inIndependentTransaction(() -> assertEquals("queued", continuations.get(goal.getId()).state(),
                            "Resume must commit continuation state before after-commit consumers observe it"));
                }
            });
        });

        assertEquals(GoalStatus.ACTIVE, goalService.getById(goal.getId()).getStatus());
        assertEquals("queued", continuations.get(goal.getId()).state(),
                "The continuation update must commit with the proxied resume transaction");
    }

    @Test
    void approvalDenialAfterCommitDurablyPausesGoalAndContinuation() {
        assertAfterCommitApprovalPauses("conv-denial-transaction", "USER_MANUAL", "denied");
    }

    @Test
    void approvalTimeoutAfterCommitDurablyPausesGoalAndContinuation() {
        assertAfterCommitApprovalPauses("conv-timeout-transaction", "TIMEOUT", null);
    }

    private void assertAfterCommitApprovalPauses(String conversationId, String decisionSource, String note) {
        GoalEntity goal = persistentGoal(conversationId, "waiting_approval");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // Bind the JDBC resource just as ApprovalWorkflowService does while resolving approval.
            jdbc.queryForObject("SELECT status FROM mate_agent_goal WHERE id=?", String.class, goal.getId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    events.publishEvent(new ApprovalResolutionEvent("pending-" + goal.getId(), conversationId,
                            "1", "alice", "shell", "{}", null, null, decisionSource, note));
                    // A different connection must see the pause before the event returns;
                    // do not rely on original-connection cleanup incidentally committing JDBC writes.
                    inIndependentTransaction(() -> {
                        assertEquals(GoalStatus.PAUSED, goalService.getById(goal.getId()).getStatus());
                        assertEquals("paused", continuations.get(goal.getId()).state());
                    });
                }
            });
        });

        assertEquals(GoalStatus.PAUSED, goalService.getById(goal.getId()).getStatus(),
                "An approval callback must commit its own transaction after the approval transaction committed");
        assertEquals("paused", continuations.get(goal.getId()).state());
    }

    private void inIndependentTransaction(Runnable assertion) {
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(status -> assertion.run());
    }

    private GoalEntity persistentGoal(String conversationId, String continuationState) {
        GoalCreateRequest request = req(conversationId, "transaction boundary");
        request.setPersistentExecution(true);
        request.setAutoFollowupEnabled(true);
        GoalEntity goal = goalService.create(request, "alice");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_goal_continuation(goal_id,state,next_run_at,updated_at) VALUES(?,?,?,?)",
                goal.getId(), continuationState, now, now);
        return goal;
    }
}
