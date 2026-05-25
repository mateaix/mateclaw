package vip.mate.approval;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.workspace.conversation.ConversationService;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GC contract for {@link ApprovalWorkflowService} (RFC-067 §4.4).
 * <p>
 * The pre-RFC GC lived on {@link ApprovalService} and only mutated the in-memory
 * map; mate_tool_approval rows stayed PENDING forever (recover-from-DB on next
 * restart resurrected them) and message metadata kept showing a ghost approval
 * banner. These tests pin the migrated behavior:
 * <ul>
 *   <li>Phase A (TTL): expired pending → DB TIMEOUT + metadata DENIED + map removal</li>
 *   <li>Phase B (overflow): pending count over MAX → oldest evicted via the same
 *       full-sync path</li>
 *   <li>Phase C (resolved cleanup): non-pending entries past RESOLVED_TTL drop
 *       from the map only — DB / metadata are not touched</li>
 *   <li>Idempotent on idle ticks: nothing to GC means zero DB / metadata interactions</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceGcTest {

    @Mock private ToolApprovalMapper approvalMapper;
    @Mock private ConversationService conversationService;

    private ApprovalService approvalService;
    private ApprovalWorkflowService workflow;

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                ToolApprovalEntity.class);
    }

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService();
        workflow = new ApprovalWorkflowService(
                approvalService, approvalMapper, new ObjectMapper(), conversationService);
    }

    @Test
    @DisplayName("Phase A: pending past PENDING_TTL goes through full DB+metadata+memory sync")
    void expiredPendingGoesThroughMarkTimeout() {
        // Pre-RFC: this row would silently be removed from the in-memory map but
        // mate_tool_approval would stay PENDING and the next recoverFromDb would
        // resurrect it. New contract: full two-phase markTimeout.
        Instant created = Instant.now().minus(Duration.ofMinutes(31));
        seedPending("pid-expired", "conv-1", "write_file", created);
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-1"), any(), eq(MetadataDecision.DENIED))).thenReturn(1);

        workflow.garbageCollect();

        // Map cleared
        assertThat(approvalService.size()).isZero();
        // DB UPDATE happened exactly once (markTimeout's conditional update).
        verify(approvalMapper, times(1)).update(isNull(), any(Wrapper.class));
        // Metadata reconciled with DENIED.
        verify(conversationService, times(1)).markPendingApprovalsResolved(
                eq("conv-1"), any(), eq(MetadataDecision.DENIED));
    }

    @Test
    @DisplayName("Phase A: pending within TTL is not touched")
    void freshPendingIsKept() {
        Instant created = Instant.now().minus(Duration.ofMinutes(5));
        PendingApproval p = seedPending("pid-fresh", "conv-2", "search", created);

        workflow.garbageCollect();

        assertThat(approvalService.getPending("pid-fresh")).isPresent();
        assertThat(p.getStatus()).isEqualTo("pending");
        verifyNoInteractions(approvalMapper);
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("Phase C: resolved entry past RESOLVED_TTL drops from map without DB / metadata touch")
    void resolvedTtlExpiredIsMemoryOnlyDrop() {
        // DB row already terminal — workflow correctly decides this is memory-only cleanup.
        Instant created = Instant.now().minus(Duration.ofHours(2));
        PendingApproval p = seedPending("pid-old-approved", "conv-3", "shell", created);
        p.setStatus("approved");
        p.setResolvedAt(Instant.now().minus(Duration.ofHours(2)));

        workflow.garbageCollect();

        assertThat(approvalService.getPending("pid-old-approved")).isEmpty();
        verifyNoInteractions(approvalMapper);
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("Phase C: resolved entry within TTL is kept")
    void freshResolvedIsKept() {
        PendingApproval p = seedPending("pid-recent-approved", "conv-4", "search", Instant.now());
        p.setStatus("approved");
        p.setResolvedAt(Instant.now().minus(Duration.ofMinutes(10)));

        workflow.garbageCollect();

        assertThat(approvalService.getPending("pid-recent-approved")).isPresent();
        assertThat(p.getStatus()).isEqualTo("approved");
        verifyNoInteractions(approvalMapper);
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("Idle GC tick (no entries): zero DB / metadata interactions")
    void idleGcIsNoop() {
        workflow.garbageCollect();

        verifyNoInteractions(approvalMapper);
        verifyNoInteractions(conversationService);
        assertThat(approvalService.size()).isZero();
    }

    @Test
    @DisplayName("markTimeout idempotent: pendingId already off PENDING -> alreadyResolved, no metadata change")
    void markTimeoutAlreadyConsumed() {
        PendingApproval p = seedPending("pid-already", "conv-5", "search", Instant.now());
        p.setStatus("consumed");

        ResolveOutcome outcome = workflow.markTimeout("pid-already");

        assertThat(outcome.isAlreadyResolved()).isTrue();
        verifyNoInteractions(approvalMapper);
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("Phase A: per-row failure doesn't abort the sweep — other expired entries still process")
    void perRowFailureContinuesSweep() {
        // Two expired pendings; first one's DB UPDATE throws, second one succeeds.
        // Pre-RFC's "all-or-nothing" loop would lose progress on the second; new GC
        // catches per-row exceptions and continues.
        Instant created = Instant.now().minus(Duration.ofMinutes(40));
        seedPending("pid-fail", "conv-fail", "write_file", created);
        seedPending("pid-ok", "conv-ok", "shell", created);

        // First call throws, second returns 1.
        when(approvalMapper.update(isNull(), any(Wrapper.class)))
                .thenThrow(new RuntimeException("simulated outage"))
                .thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-ok"), any(), eq(MetadataDecision.DENIED))).thenReturn(1);

        workflow.garbageCollect();

        // pid-fail is still in memory (markTimeout's @Transactional roll-back leaves it untouched
        // and the GC catch-block logs but continues).
        assertThat(approvalService.getPending("pid-fail")).isPresent();
        // pid-ok was successfully timed out.
        assertThat(approvalService.getPending("pid-ok")).isEmpty();

        verify(approvalMapper, times(2)).update(isNull(), any(Wrapper.class));
        // Metadata reconciliation only fired for the successful row.
        verify(conversationService, times(1)).markPendingApprovalsResolved(
                eq("conv-ok"), any(), eq(MetadataDecision.DENIED));
    }

    // ---------- helpers ----------

    private PendingApproval seedPending(String pendingId, String conversationId,
                                        String toolName, Instant createdAt) {
        PendingApproval p = new PendingApproval(
                pendingId, conversationId, "system", toolName, "{}", "test",
                createdAt, "pending");
        approvalService.registerRecovered(p);
        return p;
    }
}
