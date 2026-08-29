package vip.mate.goal.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalEventEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.GoalUpdateRequest;
import vip.mate.goal.repository.GoalEventMapper;
import vip.mate.goal.repository.GoalMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoalServiceImpl} — covers CRUD, state machine,
 * evaluation bookkeeping, budget exhaustion, optimistic-lock retry, and
 * the DB unique-index 409 mapping.
 */
@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock private GoalMapper goalMapper;
    @Mock private GoalEventMapper eventMapper;
    @Mock private AuditEventService auditEventService;

    private GoalServiceImpl service;
    private GoalProperties properties;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                GoalEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                GoalEventEntity.class);
    }

    @BeforeEach
    void setUp() {
        properties = new GoalProperties();
        service = new GoalServiceImpl(goalMapper, eventMapper, properties,
                auditEventService, new ObjectMapper());
    }

    // ==================== Helpers ====================

    private GoalCreateRequest validReq() {
        GoalCreateRequest r = new GoalCreateRequest();
        r.setConversationId("conv-1");
        r.setAgentId(10L);
        r.setWorkspaceId(1L);
        r.setTitle("ship the blog");
        r.setDescription("deploy and verify");
        r.setExitCriteria("hello world accessible");
        return r;
    }

    private GoalEntity persisted(Long id, GoalStatus status) {
        GoalEntity g = new GoalEntity();
        g.setId(id);
        g.setConversationId("conv-1");
        g.setAgentId(10L);
        g.setWorkspaceId(1L);
        g.setCreatedBy("alice");
        g.setTitle("ship the blog");
        g.setDescription("desc");
        g.setStatus(status);
        g.setTurnBudget(20);
        g.setTurnsUsed(0);
        g.setLlmCallBudget(200);
        g.setAgentLlmCallsUsed(0);
        g.setEvalLlmCallsUsed(0);
        g.setAutoFollowupEnabled(false);
        g.setFollowupCooldownSeconds(0);
        g.setVersion(0);
        g.setDeleted(0);
        g.setCreateTime(LocalDateTime.now());
        g.setUpdateTime(LocalDateTime.now());
        return g;
    }

    // ==================== create ====================

    @Test
    void create_succeeds_whenNoActiveGoalExists() {
        when(goalMapper.selectOne(any())).thenReturn(null);
        when(goalMapper.insert(any(GoalEntity.class))).thenReturn(1);

        GoalEntity created = service.create(validReq(), "alice");

        assertNotNull(created);
        assertEquals("alice", created.getCreatedBy());
        assertEquals(GoalStatus.ACTIVE, created.getStatus());
        assertTrue(created.getPersistentExecution());
        assertEquals(0, created.getTurnBudget());
        assertEquals(0, created.getLlmCallBudget());
        verify(eventMapper, times(1)).insert(any(GoalEventEntity.class));
        verify(auditEventService).record(eq("goal.created"), eq("goal"),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void create_returns409_whenActiveGoalAlreadyExists() {
        when(goalMapper.selectOne(any())).thenReturn(persisted(99L, GoalStatus.ACTIVE));
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.create(validReq(), "alice"));
        assertEquals(409, ex.getCode());
        verify(goalMapper, never()).insert(any(GoalEntity.class));
    }

    @Test
    void create_returns409_whenDbUniqueIndexHits() {
        // Concurrent race: pre-check sees nothing, but the DB does.
        when(goalMapper.selectOne(any())).thenReturn(null);
        when(goalMapper.insert(any(GoalEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_agent_goal_active_conv"));

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.create(validReq(), "alice"));
        assertEquals(409, ex.getCode());
    }

    @Test
    void create_rejectsBlankTitle() {
        GoalCreateRequest r = validReq();
        r.setTitle("");
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.create(r, "alice"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void create_rejectsNonPositiveBudget() {
        GoalCreateRequest r = validReq();
        r.setPersistentExecution(false);
        r.setTurnBudget(0);
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.create(r, "alice"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void create_persistenceDefaultCanBeDisabled_andExplicitOptInWins() {
        properties.setDefaultPersistentExecution(false);
        GoalEntity legacy = service.create(validReq(), "alice");
        assertFalse(legacy.getPersistentExecution());
        assertEquals(20, legacy.getTurnBudget());
        assertEquals(200, legacy.getLlmCallBudget());
        GoalCreateRequest req = validReq();
        req.setPersistentExecution(true);
        GoalEntity persistent = service.create(req, "alice");
        assertTrue(persistent.getPersistentExecution());
        assertEquals(0, persistent.getTurnBudget());
        assertEquals(0, persistent.getLlmCallBudget());
        assertTrue(service.toResponse(persistent).getPersistentExecution());
    }

    @Test
    void create_explicitLegacyRetainsDefaults() {
        GoalCreateRequest req = validReq();
        req.setPersistentExecution(false);
        GoalEntity goal = service.create(req, "alice");
        assertFalse(goal.getPersistentExecution());
        assertEquals(20, goal.getTurnBudget());
        assertEquals(200, goal.getLlmCallBudget());
    }

    @Test
    void create_persistentAcceptsZero_andHonorsPositiveBudgets() {
        GoalCreateRequest req = validReq();
        req.setTurnBudget(0);
        req.setLlmCallBudget(7);
        GoalEntity goal = service.create(req, "alice");
        assertEquals(0, goal.getTurnBudget());
        assertEquals(7, goal.getLlmCallBudget());
        req.setTurnBudget(-1);
        assertEquals(400, assertThrows(MateClawException.class,
                () -> service.create(req, "alice")).getCode());
        req.setTurnBudget(1);
        req.setLlmCallBudget(-1);
        assertEquals(400, assertThrows(MateClawException.class,
                () -> service.create(req, "alice")).getCode());
    }

    @Test
    void persistentZeroBudgetsAreUnlimited_butLegacyZeroIsExhausted() {
        GoalEntity goal = persisted(1L, GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);
        goal.setTurnBudget(0);
        goal.setLlmCallBudget(0);
        goal.setTurnsUsed(999);
        goal.setAgentLlmCallsUsed(999);
        assertFalse(service.isBudgetExhausted(goal));
        goal.setPersistentExecution(false);
        assertTrue(service.isBudgetExhausted(goal));
    }

    @Test
    void persistentPositiveBudgetsRemainBinding() {
        GoalEntity goal = persisted(1L, GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);
        goal.setTurnBudget(0);
        goal.setLlmCallBudget(10);
        goal.setAgentLlmCallsUsed(9);
        assertFalse(service.isBudgetExhausted(goal));
        goal.setEvalLlmCallsUsed(1);
        assertTrue(service.isBudgetExhausted(goal));
        assertEquals("llm_call_budget", service.exhaustionReason(goal));
        goal.setTurnBudget(3);
        goal.setTurnsUsed(3);
        assertEquals("turn_budget", service.exhaustionReason(goal));
    }

    @Test
    void persistentBudgetExhaustionPauses_withResumableReason() {
        GoalEntity goal = persisted(1L, GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);
        goal.setTurnsUsed(20);
        when(goalMapper.selectById(1L)).thenReturn(goal, statusFlipped(goal, GoalStatus.PAUSED));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        GoalEntity result = service.markExhausted(1L, "turn_budget");
        assertEquals(GoalStatus.PAUSED, result.getStatus());
        ArgumentCaptor<GoalEventEntity> event = ArgumentCaptor.forClass(GoalEventEntity.class);
        verify(eventMapper).insert(event.capture());
        assertEquals("paused", event.getValue().getEventType());
        assertTrue(event.getValue().getDetailJson().contains("turn_budget"));
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(goalMapper).update(any(), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("progress_summary"));
        assertTrue(update.getValue().getParamNameValuePairs().values().stream()
                .anyMatch(value -> String.valueOf(value).contains("turn_budget")));
    }

    @Test
    void resumePersistentRequiresBudgetHeadroom_andAllowsRaisedBudget() {
        GoalEntity goal = persisted(1L, GoalStatus.PAUSED);
        goal.setPersistentExecution(true);
        goal.setTurnsUsed(20);
        when(goalMapper.selectById(1L)).thenReturn(goal);
        assertEquals(409, assertThrows(MateClawException.class,
                () -> service.resume(1L, "alice")).getCode());
        verify(goalMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        goal.setTurnBudget(21);
        when(goalMapper.selectById(1L)).thenReturn(goal, statusFlipped(goal, GoalStatus.ACTIVE));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertEquals(GoalStatus.ACTIVE, service.resume(1L, "alice").getStatus());
    }

    @Test
    void updateUsesFreshMode_forZeroBudget_andDoesNotReplaceOmittedBudgets() {
        GoalEntity goal = persisted(1L, GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);
        when(goalMapper.selectById(1L)).thenReturn(goal);
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        GoalUpdateRequest req = new GoalUpdateRequest();
        req.setTurnBudget(0);
        service.update(1L, req, "alice");
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(goalMapper).update(any(), update.capture());
        assertTrue(setsProperty(update.getValue(), "turnBudget"), update.getValue().getSqlSet());
        assertFalse(setsProperty(update.getValue(), "llmCallBudget"));
        assertFalse(setsProperty(update.getValue(), "persistentExecution"));
    }

    @Test
    void updateModeValidatesCombinedState_beforeWriting() {
        GoalEntity goal = persisted(1L, GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);
        goal.setTurnBudget(0);
        goal.setLlmCallBudget(0);
        when(goalMapper.selectById(1L)).thenReturn(goal);
        GoalUpdateRequest req = new GoalUpdateRequest();
        req.setPersistentExecution(false);
        assertEquals(400, assertThrows(MateClawException.class,
                () -> service.update(1L, req, "alice")).getCode());
        verify(goalMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        req.setTurnBudget(10);
        req.setLlmCallBudget(100);
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        service.update(1L, req, "alice");
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(goalMapper).update(any(), update.capture());
        assertTrue(setsProperty(update.getValue(), "persistentExecution"), update.getValue().getSqlSet());
    }

    @Test
    void persistentCompletionRequiresFreshPassedCriteriaWithEvidence() {
        GoalEntity goal = persisted(1L, GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);
        when(goalMapper.selectById(1L)).thenReturn(goal);
        for (String criteria : new String[]{null, "[]",
                "[{\"id\":\"C1\",\"text\":\"deploy\",\"passed\":false,\"evidence\":\"attempted\"}]",
                "[{\"id\":\"C1\",\"text\":\"deploy\",\"passed\":true,\"evidence\":\"  \"}]"}) {
            goal.setCriteria(criteria);
            MateClawException error = assertThrows(MateClawException.class,
                    () -> service.markCompleted(1L, null));
            assertEquals(409, error.getCode());
        }
        verify(goalMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void persistentCompletionCannotOverridePause() {
        GoalEntity goal = verifiedPersistentGoal(GoalStatus.PAUSED);
        when(goalMapper.selectById(1L)).thenReturn(goal);
        assertEquals(409, assertThrows(MateClawException.class,
                () -> service.markCompleted(1L, null)).getCode());
        verify(goalMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void persistentCompletionPreservesVerifiedChecklist() {
        GoalEntity goal = verifiedPersistentGoal(GoalStatus.ACTIVE);
        when(goalMapper.selectById(1L)).thenReturn(goal, statusFlipped(goal, GoalStatus.COMPLETED));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertEquals(GoalStatus.COMPLETED, service.markCompleted(1L, null).getStatus());
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(goalMapper).update(any(), update.capture());
        assertFalse(update.getValue().getSqlSet().contains("criteria="));
    }

    @Test
    void persistentCompletionRechecksEvidenceAfterCasMiss() {
        GoalEntity old = verifiedPersistentGoal(GoalStatus.ACTIVE);
        GoalEntity fresh = verifiedPersistentGoal(GoalStatus.ACTIVE);
        fresh.setVersion(1);
        fresh.setCriteria("[{\"id\":\"C1\",\"text\":\"new requirement\",\"passed\":false,\"evidence\":\"\"}]");
        when(goalMapper.selectById(1L)).thenReturn(old, fresh);
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        assertEquals(409, assertThrows(MateClawException.class,
                () -> service.markCompleted(1L, null)).getCode());
        verify(goalMapper, times(1)).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void persistentResumePublishesSignalOnlyAfterSuccessfulTransition() {
        var publisher = org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        service.setApplicationEventPublisher(publisher);
        GoalEntity goal = verifiedPersistentGoal(GoalStatus.PAUSED);
        goal.setTurnsUsed(20);
        when(goalMapper.selectById(1L)).thenReturn(goal);
        assertThrows(MateClawException.class, () -> service.resume(1L, "alice"));
        verify(publisher, never()).publishEvent(any(Object.class));
        goal.setTurnBudget(21);
        when(goalMapper.selectById(1L)).thenReturn(goal, statusFlipped(goal, GoalStatus.ACTIVE));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        service.resume(1L, "alice");
        verify(publisher).publishEvent(new GoalExecutionSignal.Resume(1L));
    }

    private boolean setsProperty(LambdaUpdateWrapper<?> update, String property) {
        String column = TableInfoHelper.getTableInfo(GoalEntity.class).getFieldList().stream()
                .filter(field -> property.equals(field.getProperty())).findFirst().orElseThrow().getColumn();
        return update.getSqlSet().contains(column + "=");
    }

    private GoalEntity verifiedPersistentGoal(GoalStatus status) {
        GoalEntity goal = persisted(1L, status);
        goal.setPersistentExecution(true);
        goal.setCriteria("[{\"id\":\"C1\",\"text\":\"deploy\",\"passed\":true,\"evidence\":\"HTTP 200 verified\"}]");
        return goal;
    }

    // ==================== state transitions ====================

    @Test
    void pause_flipsActiveToPaused_andWritesEvent() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        when(goalMapper.selectById(1L)).thenReturn(g, statusFlipped(g, GoalStatus.PAUSED));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        GoalEntity result = service.pause(1L, "alice");

        assertEquals(GoalStatus.PAUSED, result.getStatus());
        ArgumentCaptor<GoalEventEntity> evCaptor = ArgumentCaptor.forClass(GoalEventEntity.class);
        verify(eventMapper).insert(evCaptor.capture());
        assertEquals("paused", evCaptor.getValue().getEventType());
    }

    @Test
    void pause_failsWhenGoalIsTerminal() {
        when(goalMapper.selectById(1L)).thenReturn(persisted(1L, GoalStatus.COMPLETED));
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.pause(1L, "alice"));
        assertEquals(409, ex.getCode());
    }

    @Test
    void resume_flipsPausedToActive() {
        GoalEntity g = persisted(1L, GoalStatus.PAUSED);
        when(goalMapper.selectById(1L)).thenReturn(g, statusFlipped(g, GoalStatus.ACTIVE));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertEquals(GoalStatus.ACTIVE, service.resume(1L, "alice").getStatus());
    }

    @Test
    void abandon_flipsAnyNonTerminalToAbandoned() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        when(goalMapper.selectById(1L)).thenReturn(g, statusFlipped(g, GoalStatus.ABANDONED));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertEquals(GoalStatus.ABANDONED, service.abandon(1L, "alice").getStatus());
    }

    @Test
    void markCompleted_isIdempotent_onTerminal() {
        GoalEntity g = persisted(1L, GoalStatus.COMPLETED);
        when(goalMapper.selectById(1L)).thenReturn(g);
        GoalEntity result = service.markCompleted(1L, null);
        assertEquals(GoalStatus.COMPLETED, result.getStatus());
        verify(goalMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void markExhausted_carriesReasonInDetail() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        when(goalMapper.selectById(1L)).thenReturn(g, statusFlipped(g, GoalStatus.EXHAUSTED));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.markExhausted(1L, "turn_budget");

        ArgumentCaptor<GoalEventEntity> evCaptor = ArgumentCaptor.forClass(GoalEventEntity.class);
        verify(eventMapper).insert(evCaptor.capture());
        assertEquals("exhausted", evCaptor.getValue().getEventType());
        assertTrue(evCaptor.getValue().getDetailJson().contains("turn_budget"));
    }

    // ==================== evaluation bookkeeping ====================

    @Test
    void recordEvaluation_bumpsCountersAndWritesEvent() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        when(goalMapper.selectById(1L)).thenReturn(g);
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        GoalEvaluationResult r = new GoalEvaluationResult(
                0.62, "DNS still missing", "continue", false,
                "qwen-turbo", 1, 800L,
                java.util.List.of(), null);
        service.recordEvaluation(1L, r, 3, 1);

        ArgumentCaptor<GoalEventEntity> evCaptor = ArgumentCaptor.forClass(GoalEventEntity.class);
        verify(eventMapper).insert(evCaptor.capture());
        assertEquals("evaluated", evCaptor.getValue().getEventType());
        String detail = evCaptor.getValue().getDetailJson();
        assertTrue(detail.contains("agentLlmCallsDelta"));
        assertTrue(detail.contains("evalLlmCallsDelta"));
        assertTrue(detail.contains("qwen-turbo"));
    }

    @Test
    void recordEvaluation_isNoop_onTerminalGoal() {
        when(goalMapper.selectById(1L)).thenReturn(persisted(1L, GoalStatus.COMPLETED));
        service.recordEvaluation(1L, null, 5, 1);
        verify(goalMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        verify(eventMapper, never()).insert(any(GoalEventEntity.class));
    }

    @Test
    void isBudgetExhausted_detectsTurnBudgetHit() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        g.setTurnsUsed(20);
        g.setTurnBudget(20);
        assertTrue(service.isBudgetExhausted(g));
        assertEquals("turn_budget", service.exhaustionReason(g));
    }

    @Test
    void isBudgetExhausted_detectsLlmBudgetHit() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        g.setAgentLlmCallsUsed(180);
        g.setEvalLlmCallsUsed(25);
        g.setLlmCallBudget(200);
        assertTrue(service.isBudgetExhausted(g));
        assertEquals("llm_call_budget", service.exhaustionReason(g));
    }

    @Test
    void isBudgetExhausted_returnsFalse_whenHeadroomRemains() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        g.setTurnsUsed(5);
        g.setAgentLlmCallsUsed(30);
        g.setEvalLlmCallsUsed(4);
        assertFalse(service.isBudgetExhausted(g));
    }

    @Test
    void appendCriterion_concatenatesWithMarker() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        g.setExitCriteria("DNS works");
        when(goalMapper.selectById(1L)).thenReturn(g, g);
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.appendCriterion(1L, "tests pass", "alice");

        ArgumentCaptor<GoalEventEntity> evCaptor = ArgumentCaptor.forClass(GoalEventEntity.class);
        verify(eventMapper).insert(evCaptor.capture());
        assertEquals("criterion_added", evCaptor.getValue().getEventType());
        assertTrue(evCaptor.getValue().getDetailJson().contains("tests pass"));
    }

    @Test
    void appendCriterion_rejectsBlankInput() {
        // Validation happens before selectById, so we do NOT stub the mapper.
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.appendCriterion(1L, "   ", "alice"));
        assertEquals(400, ex.getCode());
        verify(goalMapper, never()).selectById(any());
    }

    // ==================== criteria checklist ====================

    @Test
    void create_normalizesInitialCriteria_assignsIdsForcesUnpassed() {
        when(goalMapper.selectOne(any())).thenReturn(null);
        when(goalMapper.insert(any(GoalEntity.class))).thenReturn(1);

        GoalCreateRequest r = validReq();
        r.setCriteria(java.util.List.of(
                new vip.mate.goal.model.GoalCriterion("ignored", "tests pass", true, "x"),
                new vip.mate.goal.model.GoalCriterion("", "  ", false, ""), // blank dropped
                new vip.mate.goal.model.GoalCriterion("", "deployed", false, "")));

        ArgumentCaptor<GoalEntity> captor = ArgumentCaptor.forClass(GoalEntity.class);
        service.create(r, "alice");
        verify(goalMapper).insert(captor.capture());

        java.util.List<vip.mate.goal.model.GoalCriterion> parsed =
                vip.mate.goal.model.GoalCriteriaCodec.parse(captor.getValue().getCriteria(), new ObjectMapper());
        assertEquals(2, parsed.size());
        assertEquals("C1", parsed.get(0).id());
        assertEquals("tests pass", parsed.get(0).text());
        assertFalse(parsed.get(0).passed());        // forced false even though caller said true
        assertEquals("C2", parsed.get(1).id());
        assertEquals("deployed", parsed.get(1).text());
    }

    @Test
    void create_emptyCriteria_leavesColumnNull_forBootstrap() {
        when(goalMapper.selectOne(any())).thenReturn(null);
        when(goalMapper.insert(any(GoalEntity.class))).thenReturn(1);
        ArgumentCaptor<GoalEntity> captor = ArgumentCaptor.forClass(GoalEntity.class);
        service.create(validReq(), "alice");
        verify(goalMapper).insert(captor.capture());
        assertNull(captor.getValue().getCriteria());
    }

    @Test
    void create_autoFollowup_threeState() {
        when(goalMapper.selectOne(any())).thenReturn(null);
        when(goalMapper.insert(any(GoalEntity.class))).thenReturn(1);

        // null -> config default (true by default)
        assertTrue(service.create(validReq(), "alice").getAutoFollowupEnabled());

        // explicit false is honored
        GoalCreateRequest off = validReq();
        off.setAutoFollowupEnabled(false);
        assertFalse(service.create(off, "alice").getAutoFollowupEnabled());
    }

    @Test
    void toResponse_parsesCriteriaArray_nullBecomesEmpty() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        g.setCriteria("[{\"id\":\"C1\",\"text\":\"a\",\"passed\":true,\"evidence\":\"ok\"}]");
        var resp = service.toResponse(g);
        assertEquals(1, resp.getCriteria().size());
        assertTrue(resp.getCriteria().get(0).passed());

        GoalEntity bare = persisted(2L, GoalStatus.ACTIVE); // criteria == null
        assertNotNull(service.toResponse(bare).getCriteria());
        assertTrue(service.toResponse(bare).getCriteria().isEmpty());
    }

    // ==================== optimistic lock retry ====================

    @Test
    void update_failsAfterRetriesExhausted_whenVersionAlwaysStale() {
        GoalEntity g = persisted(1L, GoalStatus.ACTIVE);
        when(goalMapper.selectById(1L)).thenReturn(g);
        // Always return 0 rows affected — simulates persistent version conflict.
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        GoalUpdateRequest upd = new GoalUpdateRequest();
        upd.setTitle("new title");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.update(1L, upd, "alice"));
        assertEquals(409, ex.getCode());
        verify(goalMapper, times(3)).update(any(), any(LambdaUpdateWrapper.class));
    }

    /**
     * Regression: after the first CAS miss the retry loop must refetch the
     * entity so the rebuilt wrapper carries the current version. Previously
     * the wrapper was captured once with version=oldVersion, so once stale
     * it could never succeed even when contention cleared.
     */
    @Test
    void update_succeedsOnSecondAttempt_afterRefetchPicksUpFreshVersion() {
        GoalEntity v0 = persisted(1L, GoalStatus.ACTIVE);
        v0.setVersion(0);
        GoalEntity v1 = persisted(1L, GoalStatus.ACTIVE);
        v1.setVersion(1);
        GoalEntity v2 = persisted(1L, GoalStatus.ACTIVE);
        v2.setVersion(2);
        // First refetch returns v0 (stale — CAS will miss). Second refetch
        // returns v1 (fresh — CAS will succeed). Third call (post-update
        // selectById) returns the final v2 state for the return value.
        when(goalMapper.selectById(1L)).thenReturn(v0, v1, v2);
        // First update misses (rows=0), second update succeeds (rows=1).
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class)))
                .thenReturn(0).thenReturn(1);

        GoalUpdateRequest upd = new GoalUpdateRequest();
        upd.setTitle("retry-survives");

        GoalEntity out = service.update(1L, upd, "alice");
        assertNotNull(out);
        // Two update attempts (one miss + one hit) plus three selectById
        // calls (two for the loop refetch, one for the post-update return).
        verify(goalMapper, times(2)).update(any(), any(LambdaUpdateWrapper.class));
        verify(goalMapper, times(3)).selectById(1L);
    }

    @Test
    void findActiveByConversation_returnsNull_forBlankInput() {
        assertNull(service.findActiveByConversation(""));
        assertNull(service.findActiveByConversation(null));
        verify(goalMapper, never()).selectOne(any());
    }

    @Test
    void getById_throws404_whenMissing() {
        when(goalMapper.selectById(1L)).thenReturn(null);
        MateClawException ex = assertThrows(MateClawException.class, () -> service.getById(1L));
        assertEquals(404, ex.getCode());
    }

    /** Helper: mutate a copy of {@code g} with a new status, simulating
     *  what the post-update selectById would return. */
    private GoalEntity statusFlipped(GoalEntity g, GoalStatus newStatus) {
        GoalEntity copy = new GoalEntity();
        copy.setId(g.getId());
        copy.setConversationId(g.getConversationId());
        copy.setAgentId(g.getAgentId());
        copy.setWorkspaceId(g.getWorkspaceId());
        copy.setCreatedBy(g.getCreatedBy());
        copy.setTitle(g.getTitle());
        copy.setStatus(newStatus);
        copy.setPersistentExecution(g.getPersistentExecution());
        copy.setTurnBudget(g.getTurnBudget());
        copy.setTurnsUsed(g.getTurnsUsed());
        copy.setLlmCallBudget(g.getLlmCallBudget());
        copy.setAgentLlmCallsUsed(g.getAgentLlmCallsUsed());
        copy.setEvalLlmCallsUsed(g.getEvalLlmCallsUsed());
        copy.setVersion(g.getVersion() + 1);
        copy.setDeleted(0);
        copy.setCreateTime(g.getCreateTime());
        copy.setUpdateTime(LocalDateTime.now());
        return copy;
    }
    @Test
    void waitForInputPausesActivePersistentGoal_andRecordsReason() {
        GoalEntity goal = verifiedPersistentGoal(GoalStatus.ACTIVE);
        when(goalMapper.selectById(1L)).thenReturn(goal, statusFlipped(goal, GoalStatus.PAUSED));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        GoalEntity paused = service.waitForInput(1L, "  Need production hostname from the owner  ", "alice");
        assertEquals(GoalStatus.PAUSED, paused.getStatus());
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(goalMapper).update(any(), update.capture());
        assertTrue(setsProperty(update.getValue(), "progressSummary"));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(
                "Waiting for input: Need production hostname from the owner"));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(GoalStatus.PAUSED));
        ArgumentCaptor<GoalEventEntity> event = ArgumentCaptor.forClass(GoalEventEntity.class);
        verify(eventMapper).insert(event.capture());
        assertEquals("paused", event.getValue().getEventType());
        assertTrue(event.getValue().getDetailJson().contains("Need production hostname from the owner"));
        verify(auditEventService).record(eq("goal.waiting_input"), eq("goal"), eq("1"),
                anyString(), anyString(), any());
    }

    @Test
    void waitForInputRejectsBlankReasonBeforeAccessingGoal() {
        for (String reason : new String[]{null, "", "  "}) {
            assertEquals(400, assertThrows(MateClawException.class,
                    () -> service.waitForInput(1L, reason, "alice")).getCode());
        }
        verify(goalMapper, never()).selectById(any());
    }

    @Test
    void waitForInputRechecksActiveStateAfterCasConflict() {
        GoalEntity active = verifiedPersistentGoal(GoalStatus.ACTIVE);
        GoalEntity paused = statusFlipped(active, GoalStatus.PAUSED);
        when(goalMapper.selectById(1L)).thenReturn(active, paused);
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        assertEquals(409, assertThrows(MateClawException.class,
                () -> service.waitForInput(1L, "Need deployment approval", "alice")).getCode());
        verify(goalMapper, times(1)).update(any(), any(LambdaUpdateWrapper.class));
        verify(eventMapper, never()).insert(any(GoalEventEntity.class));
    }

    @Test
    void waitForInputRejectsLegacyAndTerminalGoals() {
        GoalEntity goal = persisted(1L, GoalStatus.ACTIVE);
        when(goalMapper.selectById(1L)).thenReturn(goal);
        assertEquals(409, assertThrows(MateClawException.class,
                () -> service.waitForInput(1L, "Need deployment approval", "alice")).getCode());
        goal.setPersistentExecution(true);
        goal.setStatus(GoalStatus.COMPLETED);
        assertEquals(409, assertThrows(MateClawException.class,
                () -> service.waitForInput(1L, "Need deployment approval", "alice")).getCode());
        verify(goalMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void waitForInputBoundsPersistedReason() {
        GoalEntity goal = verifiedPersistentGoal(GoalStatus.ACTIVE);
        when(goalMapper.selectById(1L)).thenReturn(goal, statusFlipped(goal, GoalStatus.PAUSED));
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        service.waitForInput(1L, "Missing permission: " + "x".repeat(10000), "alice");
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(goalMapper).update(any(), update.capture());
        update.getValue().getSqlSet();
        String summary = ((java.util.Map<?, ?>) update.getValue().getParamNameValuePairs()).values().stream()
                .filter(value -> value instanceof String && ((String) value).startsWith("Waiting for input: "))
                .map(String::valueOf).findFirst().orElseThrow();
        assertTrue(summary.length() <= 2048);
    }

    @Test
    void lateEvaluationAccountsUsageWithoutOverwritingPersistentPauseReason() {
        GoalEntity active = verifiedPersistentGoal(GoalStatus.ACTIVE);
        GoalEntity paused = statusFlipped(active, GoalStatus.PAUSED);
        paused.setProgressSummary("Waiting for input: Need deployment approval");
        when(goalMapper.selectById(1L)).thenReturn(active, paused, paused);
        when(goalMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        GoalEvaluationResult evaluation = new GoalEvaluationResult(0.4, "More work needed",
                GoalEvaluationResult.DECISION_CONTINUE, false, "stub", 1, 0, java.util.List.of(), null);
        service.recordEvaluation(1L, evaluation, 3, 1);
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(goalMapper).update(any(), update.capture());
        assertFalse(setsProperty(update.getValue(), "progressSummary"));
        assertTrue(update.getValue().getSqlSet().contains("agent_llm_calls_used = agent_llm_calls_used + 3"));
        assertTrue(update.getValue().getSqlSet().contains("eval_llm_calls_used = eval_llm_calls_used + 1"));
    }
}
