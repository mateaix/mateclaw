package vip.mate.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.workspace.conversation.ConversationService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Recovery contract for ApprovalWorkflowService.recoverFromDb (RFC-067 §4.1).
 * <p>
 * The pre-RFC implementation generated a fresh random pendingId on recovery,
 * which silently desynchronized the in-memory map from mate_tool_approval and
 * left every later resolve()/updateDbStatus() call hitting zero rows. These
 * tests pin the new contract:
 * <ul>
 *   <li>Live row → pendingMap entry preserves the DB pendingId AND createdAt
 *       (so PENDING_TTL math still works after restart)</li>
 *   <li>Expired row (expireAt past) → DB → TIMEOUT, metadata reconciled DENIED,
 *       no pendingMap entry</li>
 *   <li>Legacy row with expireAt NULL falls back to createdAt + PENDING_TTL —
 *       this is the regression-prevention case for §4.1's effectiveExpireAt
 *       fallback. A naive "if expireAt != null && now > expireAt" check would
 *       silently revive ancient PENDING rows after every restart.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceRecoveryTest {

    @Mock private ToolApprovalMapper approvalMapper;
    @Mock private ConversationService conversationService;

    private ApprovalService approvalService;       // real, so registerRecovered is exercised
    private ApprovalWorkflowService workflow;

    @BeforeAll
    static void initMyBatisPlusCache() {
        // PR-2 resolveAndConsume builds a LambdaUpdateWrapper.set(...) which needs
        // ToolApprovalEntity's TableInfo to be registered in MyBatis-Plus's static
        // cache (a Spring context normally does this during mapper scan).
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                ToolApprovalEntity.class);
    }

    private void initWorkflow(List<ToolApprovalEntity> dbRows) {
        approvalService = new ApprovalService();
        // Skip the GC scheduler — initGc() spins up a daemon thread we don't need here.
        // Tests interact with the registry via registerRecovered + getPending only.
        workflow = new ApprovalWorkflowService(
                approvalService,
                approvalMapper,
                new ObjectMapper(),
                conversationService);
        when(approvalMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(dbRows);
    }

    @Test
    @DisplayName("Live PENDING row recovers with DB pendingId + createdAt preserved")
    void recoversLiveRowPreservingIdAndCreatedAt() {
        LocalDateTime created = LocalDateTime.now().minusMinutes(5);
        ToolApprovalEntity row = newPendingRow("pid-live-1", "conv-1", created,
                created.plusMinutes(30));
        initWorkflow(List.of(row));

        workflow.recoverFromDb();

        PendingApproval recovered = approvalService.getPending("pid-live-1").orElse(null);
        assertThat(recovered).isNotNull();
        assertThat(recovered.getPendingId()).isEqualTo("pid-live-1");
        assertThat(recovered.getConversationId()).isEqualTo("conv-1");
        assertThat(recovered.getStatus()).isEqualTo("pending");
        // createdAt round-trips with second precision (LocalDateTime → Instant via system zone)
        assertThat(recovered.getCreatedAt().getEpochSecond())
                .isEqualTo(created.atZone(java.time.ZoneId.systemDefault()).toEpochSecond());

        // Did not silently expire the live row.
        verify(approvalMapper, never()).updateById(any(ToolApprovalEntity.class));
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("Expired row with explicit past expireAt: DB -> TIMEOUT, metadata DENIED, not in map")
    void expiredRowWithExplicitExpireAt() {
        LocalDateTime created = LocalDateTime.now().minusMinutes(31);
        ToolApprovalEntity row = newPendingRow("pid-exp-1", "conv-2", created,
                created.plusMinutes(30));
        initWorkflow(List.of(row));
        when(approvalMapper.updateById(any(ToolApprovalEntity.class))).thenReturn(1);

        workflow.recoverFromDb();

        assertThat(approvalService.getPending("pid-exp-1")).isEmpty();
        ArgumentCaptor<ToolApprovalEntity> updated = ArgumentCaptor.forClass(ToolApprovalEntity.class);
        verify(approvalMapper).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo("TIMEOUT");
        assertThat(updated.getValue().getResolvedAt()).isNotNull();
        verify(conversationService).markPendingApprovalsResolved(
                eq("conv-2"), eq(Set.of("pid-exp-1")), eq(MetadataDecision.DENIED));
    }

    @Test
    @DisplayName("Legacy row with expireAt=NULL still expires via createdAt + PENDING_TTL fallback")
    void legacyRowFallsBackToCreatedAtPlusTtl() {
        // Mirrors the §4.1 regression case: pre-RFC rows persisted by an older build
        // never got an expireAt column populated. Without the fallback, recoverFromDb
        // would resurrect them as live PENDING after every restart — a permanent ghost
        // approval source.
        LocalDateTime created = LocalDateTime.now().minusMinutes(31);
        ToolApprovalEntity row = newPendingRow("pid-legacy-1", "conv-3", created, null);
        initWorkflow(List.of(row));
        when(approvalMapper.updateById(any(ToolApprovalEntity.class))).thenReturn(1);

        workflow.recoverFromDb();

        assertThat(approvalService.getPending("pid-legacy-1")).isEmpty();
        verify(approvalMapper).updateById(any(ToolApprovalEntity.class));
        verify(conversationService).markPendingApprovalsResolved(
                eq("conv-3"), eq(Set.of("pid-legacy-1")), eq(MetadataDecision.DENIED));
    }

    @Test
    @DisplayName("DB updateById returning 0 rows: metadata is NOT touched (no drift)")
    void expireSkipsMetadataWhenDbAffectsZeroRows() {
        // Concurrent resolve case: another path already moved the row off PENDING
        // between selectList and updateById. Metadata flip MUST be gated on DB
        // success, otherwise message metadata = denied while DB is e.g. CONSUMED,
        // and the next recoverFromDb would resurrect it — exactly the drift we
        // came here to fix.
        LocalDateTime created = LocalDateTime.now().minusMinutes(31);
        ToolApprovalEntity row = newPendingRow("pid-race-1", "conv-race", created,
                created.plusMinutes(30));
        initWorkflow(List.of(row));
        when(approvalMapper.updateById(any(ToolApprovalEntity.class))).thenReturn(0);

        workflow.recoverFromDb();

        assertThat(approvalService.getPending("pid-race-1")).isEmpty();
        verify(approvalMapper).updateById(any(ToolApprovalEntity.class));
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("DB updateById throwing: metadata is NOT touched")
    void expireSkipsMetadataWhenDbThrows() {
        LocalDateTime created = LocalDateTime.now().minusMinutes(31);
        ToolApprovalEntity row = newPendingRow("pid-throw-1", "conv-throw", created,
                created.plusMinutes(30));
        initWorkflow(List.of(row));
        when(approvalMapper.updateById(any(ToolApprovalEntity.class)))
                .thenThrow(new RuntimeException("simulated DB outage"));

        workflow.recoverFromDb();

        assertThat(approvalService.getPending("pid-throw-1")).isEmpty();
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("Legacy row with expireAt=NULL but createdAt within TTL: still recovers as live")
    void legacyRowWithinTtlStillRecovers() {
        LocalDateTime created = LocalDateTime.now().minusMinutes(5);
        ToolApprovalEntity row = newPendingRow("pid-legacy-live", "conv-4", created, null);
        initWorkflow(List.of(row));

        workflow.recoverFromDb();

        assertThat(approvalService.getPending("pid-legacy-live")).isPresent();
        verify(approvalMapper, never()).updateById(any(ToolApprovalEntity.class));
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("Recovered pending carries its replay payload through resolveAndConsume")
    void resolveAfterRecoveryYieldsRecoveredPayload() {
        // Pre-RFC, recovery generated a fresh random id; the next resolveAndConsume
        // either pulled the wrong record or the in-memory map was empty altogether.
        // This pins that the original DB pendingId AND the replay payload (toolCallPayload)
        // round-trip through recovery and still drive consume successfully through the
        // PR-2 ResolveOutcome contract.
        LocalDateTime created = LocalDateTime.now().minusMinutes(2);
        ToolApprovalEntity row = newPendingRow("pid-resolve-1", "conv-5", created,
                created.plusMinutes(30));
        row.setToolCallPayload("{\"name\":\"write_file\"}");
        initWorkflow(List.of(row));
        // Stub the DB UPDATE that the new two-phase resolve runs; metadata mock is
        // already injected and returns 0 by default which matches "no message rewrites".
        when(approvalMapper.update(any(), any())).thenReturn(1);

        workflow.recoverFromDb();
        PendingApproval recovered = approvalService.getPending("pid-resolve-1").orElseThrow();
        assertThat(recovered.getToolCallPayload()).isEqualTo("{\"name\":\"write_file\"}");

        ResolveOutcome outcome = workflow.resolveAndConsume("pid-resolve-1", "alice");
        assertThat(outcome.isConsumed()).isTrue();
        assertThat(outcome.dbSynced()).isTrue();
        assertThat(outcome.consumedSnapshot()).isNotNull();
        assertThat(outcome.consumedSnapshot().getPendingId()).isEqualTo("pid-resolve-1");
        assertThat(outcome.consumedSnapshot().getToolCallPayload()).isEqualTo("{\"name\":\"write_file\"}");
        assertThat(outcome.consumedSnapshot().getStatus()).isEqualTo("consumed");
        assertThat(outcome.consumedSnapshot().getResolvedBy()).isEqualTo("alice");
        // pendingMap entry has been removed; a second consume is idempotent already_resolved.
        ResolveOutcome second = workflow.resolveAndConsume("pid-resolve-1", "alice");
        assertThat(second.isAlreadyResolved()).isTrue();
    }

    private ToolApprovalEntity newPendingRow(String pendingId, String conversationId,
                                             LocalDateTime createdAt, LocalDateTime expireAt) {
        ToolApprovalEntity e = new ToolApprovalEntity();
        e.setPendingId(pendingId);
        e.setConversationId(conversationId);
        e.setUserId("u");
        e.setToolName("write_file");
        e.setToolArguments("{}");
        e.setSummary("test");
        e.setStatus("PENDING");
        e.setCreatedAt(createdAt);
        e.setExpireAt(expireAt);
        return e;
    }
}
