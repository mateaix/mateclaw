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
        GoalProperties properties = new GoalProperties();
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
        assertEquals(20, created.getTurnBudget());
        assertEquals(200, created.getLlmCallBudget());
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
        r.setTurnBudget(0);
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.create(r, "alice"));
        assertEquals(400, ex.getCode());
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
}
