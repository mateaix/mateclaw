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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Two-phase resolve contract for {@link ApprovalWorkflowService} (RFC-067 §4.2 / §4.3).
 * <p>
 * The pre-RFC implementation removed the in-memory entry FIRST then attempted DB
 * UPDATE on a best-effort try / catch — payload could be lost while DB stayed
 * PENDING. These tests pin the new ordering:
 * <ol>
 *   <li>snapshot (no map mutation)</li>
 *   <li>DB UPDATE conditional on {@code status='PENDING'} (idempotent against concurrent resolve)</li>
 *   <li>metadata reconciliation (same tx)</li>
 *   <li>memory mutation only on commit (afterCommit hook; immediate when no tx active)</li>
 * </ol>
 * <p>
 * Tests run outside Spring's tx manager, so the {@code afterCommit} hook executes
 * immediately — that exercises the same observable end-state as a committed tx.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceResolveTest {

    @Mock private ToolApprovalMapper approvalMapper;
    @Mock private ConversationService conversationService;

    private ApprovalService approvalService;
    private ApprovalWorkflowService workflow;

    @BeforeAll
    static void initMyBatisPlusCache() {
        // LambdaUpdateWrapper.set / .eq need the entity's TableInfo to be registered in
        // MyBatis-Plus's static cache. In a Spring context this happens during mapper
        // scan; in a plain MockitoExtension test we trigger it manually.
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
    @DisplayName("resolve(approved) updates DB, metadata, and snapshot status; entry stays in map")
    void resolveApprovedHappyPath() {
        PendingApproval pending = seedPending("pid-1", "conv-1", "write_file");
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-1"), eq(Set.of("pid-1")), eq(MetadataDecision.APPROVED))).thenReturn(1);

        ResolveOutcome outcome = workflow.resolve("pid-1", "alice", "approved");

        assertThat(outcome.decision()).isEqualTo("approved");
        assertThat(outcome.dbSynced()).isTrue();
        assertThat(outcome.messagesRewritten()).isEqualTo(1);
        assertThat(outcome.consumedSnapshot()).isNull();

        // Memory: status flipped to "approved", entry stays in map (resolve does NOT remove)
        assertThat(pending.getStatus()).isEqualTo("approved");
        assertThat(pending.getResolvedBy()).isEqualTo("alice");
        assertThat(approvalService.getPending("pid-1")).isPresent();

        verify(approvalMapper, times(1)).update(isNull(), any(Wrapper.class));
        verify(conversationService).markPendingApprovalsResolved(
                "conv-1", Set.of("pid-1"), MetadataDecision.APPROVED);
    }

    @Test
    @DisplayName("resolve(denied) flips metadata + snapshot to denied")
    void resolveDeniedHappyPath() {
        PendingApproval pending = seedPending("pid-2", "conv-2", "shell");
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-2"), eq(Set.of("pid-2")), eq(MetadataDecision.DENIED))).thenReturn(1);

        ResolveOutcome outcome = workflow.resolve("pid-2", "bob", "denied");

        assertThat(outcome.decision()).isEqualTo("denied");
        assertThat(outcome.dbSynced()).isTrue();
        assertThat(pending.getStatus()).isEqualTo("denied");
        verify(conversationService).markPendingApprovalsResolved(
                "conv-2", Set.of("pid-2"), MetadataDecision.DENIED);
    }

    @Test
    @DisplayName("resolve no-op when pendingId not in map: no DB / metadata interaction")
    void resolveUnknownPendingId() {
        ResolveOutcome outcome = workflow.resolve("ghost-id", "alice", "approved");

        assertThat(outcome.isAlreadyResolved()).isTrue();
        assertThat(outcome.dbSynced()).isFalse();
        verifyNoInteractions(approvalMapper);
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("resolve idempotent against concurrent resolve: DB rows=0 -> no metadata, no memory mutation")
    void resolveIdempotentOnConcurrentResolve() {
        PendingApproval pending = seedPending("pid-race", "conv-race", "write_file");
        // Another path already moved the row off PENDING between snapshot and DB UPDATE.
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        ResolveOutcome outcome = workflow.resolve("pid-race", "alice", "approved");

        assertThat(outcome.isAlreadyResolved()).isTrue();
        assertThat(outcome.dbSynced()).isFalse();
        assertThat(outcome.messagesRewritten()).isZero();
        // Snapshot status was NOT flipped to approved — memory stays consistent with DB.
        assertThat(pending.getStatus()).isEqualTo("pending");
        assertThat(approvalService.getPending("pid-race")).isPresent();
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("resolveAndConsume happy path: DB CONSUMED, metadata APPROVED, snapshot removed from map")
    void resolveAndConsumeHappyPath() {
        PendingApproval pending = seedPending("pid-c-1", "conv-c", "write_file");
        pending.setToolCallPayload("{\"name\":\"write_file\"}");
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-c"), eq(Set.of("pid-c-1")), eq(MetadataDecision.APPROVED))).thenReturn(2);

        ResolveOutcome outcome = workflow.resolveAndConsume("pid-c-1", "carol");

        assertThat(outcome.isConsumed()).isTrue();
        assertThat(outcome.dbSynced()).isTrue();
        assertThat(outcome.messagesRewritten()).isEqualTo(2);
        assertThat(outcome.consumedSnapshot()).isNotNull();
        assertThat(outcome.consumedSnapshot().getToolCallPayload()).isEqualTo("{\"name\":\"write_file\"}");

        // Memory: status flipped to consumed, entry REMOVED (single-shot consume).
        assertThat(pending.getStatus()).isEqualTo("consumed");
        assertThat(approvalService.getPending("pid-c-1")).isEmpty();

        // Second consume returns idempotent already_resolved (entry is gone).
        ResolveOutcome second = workflow.resolveAndConsume("pid-c-1", "carol");
        assertThat(second.isAlreadyResolved()).isTrue();
    }

    @Test
    @DisplayName("resolveAndConsume DB rows=0: no metadata, snapshot stays in map")
    void resolveAndConsumeRaceLeavesMapAlone() {
        PendingApproval pending = seedPending("pid-c-race", "conv-cr", "write_file");
        pending.setToolCallPayload("{}");
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        ResolveOutcome outcome = workflow.resolveAndConsume("pid-c-race", "alice");

        assertThat(outcome.isAlreadyResolved()).isTrue();
        // Critical: payload is NOT lost. Replay can still find the entry next loop.
        assertThat(approvalService.getPending("pid-c-race")).isPresent();
        assertThat(pending.getStatus()).isEqualTo("pending");
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("consumeApproved redeems the earliest approved record; missing match -> alreadyResolved")
    void consumeApprovedHappyAndMiss() {
        PendingApproval pending = seedPending("pid-app-1", "conv-app", "search");
        // Caller previously approved but did not consume — common in /approve text flow.
        pending.setStatus("approved");
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-app"), eq(Set.of("pid-app-1")), eq(MetadataDecision.APPROVED))).thenReturn(1);

        ResolveOutcome consumed = workflow.consumeApproved("conv-app", "search");

        assertThat(consumed.isConsumed()).isTrue();
        assertThat(consumed.consumedSnapshot()).isNotNull();
        assertThat(approvalService.getPending("pid-app-1")).isEmpty();

        // Second call: nothing approved left → no additional DB / metadata interaction.
        org.mockito.Mockito.clearInvocations(approvalMapper, conversationService);
        ResolveOutcome miss = workflow.consumeApproved("conv-app", "search");
        assertThat(miss.isAlreadyResolved()).isTrue();
        verifyNoInteractions(approvalMapper);
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("cancelStalePending issues a SUPERSEDED outcome per pending in the conversation")
    void cancelStalePendingMultipleEntries() {
        PendingApproval a = seedPending("pid-stale-A", "conv-stale", "write_file");
        PendingApproval b = seedPending("pid-stale-B", "conv-stale", "shell");
        PendingApproval keep = seedPending("pid-keep", "conv-stale", "memory_recall");
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-stale"), any(), eq(MetadataDecision.DENIED))).thenReturn(1);

        List<ResolveOutcome> outcomes = workflow.cancelStalePending("conv-stale", "pid-keep");

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).extracting(ResolveOutcome::pendingId)
                .containsExactlyInAnyOrder("pid-stale-A", "pid-stale-B");
        assertThat(outcomes).allMatch(o -> "superseded".equals(o.decision()));
        // Excluded entry untouched.
        assertThat(approvalService.getPending("pid-keep")).isPresent();
        assertThat(keep.getStatus()).isEqualTo("pending");
        // Cancelled entries removed from map.
        assertThat(approvalService.getPending("pid-stale-A")).isEmpty();
        assertThat(approvalService.getPending("pid-stale-B")).isEmpty();
        assertThat(a.getStatus()).isEqualTo("superseded");
        assertThat(b.getStatus()).isEqualTo("superseded");

        // Two DB updates fired (one per cancellation).
        verify(approvalMapper, times(2)).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("denyAllByConversation: every pending becomes denied; metadata reconciled per row")
    void denyAllConversationSweep() {
        // Stop endpoint scenario: user halts a turn while two pendings sit in the map.
        PendingApproval a = seedPending("pid-stop-A", "conv-stop", "write_file");
        PendingApproval b = seedPending("pid-stop-B", "conv-stop", "shell");
        seedPending("pid-other-conv", "conv-other", "search");  // not in target conversation
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-stop"), any(), eq(MetadataDecision.DENIED))).thenReturn(1);

        List<ResolveOutcome> outcomes = workflow.denyAllByConversation("conv-stop", "alice");

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).extracting(ResolveOutcome::pendingId)
                .containsExactlyInAnyOrder("pid-stop-A", "pid-stop-B");
        assertThat(outcomes).allMatch(o -> "denied".equals(o.decision()));
        assertThat(a.getStatus()).isEqualTo("denied");
        assertThat(b.getStatus()).isEqualTo("denied");
        // Targets removed from map.
        assertThat(approvalService.getPending("pid-stop-A")).isEmpty();
        assertThat(approvalService.getPending("pid-stop-B")).isEmpty();
        // Other conversation untouched.
        assertThat(approvalService.getPending("pid-other-conv")).isPresent();
        // Two metadata reconciliations fired (one per pending).
        verify(conversationService, times(2)).markPendingApprovalsResolved(
                eq("conv-stop"), any(), eq(MetadataDecision.DENIED));
    }

    @Test
    @DisplayName("denyAllByConversation: empty conversation -> empty outcomes, no DB / metadata interaction")
    void denyAllNoPendingsIsNoop() {
        seedPending("pid-other", "conv-other", "search");

        List<ResolveOutcome> outcomes = workflow.denyAllByConversation("conv-empty", "alice");

        assertThat(outcomes).isEmpty();
        verifyNoInteractions(approvalMapper);
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("denyAllByConversation: per-row failure doesn't abort the sweep")
    void denyAllPerRowFailureContinues() {
        seedPending("pid-fail", "conv-mix", "write_file");
        seedPending("pid-ok", "conv-mix", "shell");
        // First UPDATE throws, second succeeds.
        when(approvalMapper.update(isNull(), any(Wrapper.class)))
                .thenThrow(new RuntimeException("simulated outage"))
                .thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-mix"), any(), eq(MetadataDecision.DENIED))).thenReturn(1);

        List<ResolveOutcome> outcomes = workflow.denyAllByConversation("conv-mix", "alice");

        // Only the successful row makes it into the outcomes list.
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).pendingId()).isEqualTo("pid-ok");
        // Failed row is still in memory (transactional rollback would leave it untouched).
        assertThat(approvalService.getPending("pid-fail")).isPresent();
        assertThat(approvalService.getPending("pid-ok")).isEmpty();
    }

    @Test
    @DisplayName("DB UPDATE throwing propagates so @Transactional can roll back; memory untouched")
    void dbThrowsPropagatesForRollback() {
        PendingApproval pending = seedPending("pid-throw", "conv-throw", "write_file");
        when(approvalMapper.update(isNull(), any(Wrapper.class)))
                .thenThrow(new RuntimeException("simulated outage"));

        try {
            workflow.resolve("pid-throw", "alice", "approved");
            org.junit.jupiter.api.Assertions.fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage()).contains("simulated outage");
        }
        // Memory snapshot must not have flipped.
        assertThat(pending.getStatus()).isEqualTo("pending");
        verifyNoInteractions(conversationService);
        // approvalService is a real instance in these tests, not a Mockito mock —
        // its untouched state is asserted via the snapshot status above.
    }

    @Test
    @DisplayName("ResolveOutcome carries conversationId + toolName for SSE broadcast use")
    void outcomeShape() {
        PendingApproval pending = seedPending("pid-shape", "conv-shape", "search_web");
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("conv-shape"), eq(Set.of("pid-shape")), eq(MetadataDecision.DENIED))).thenReturn(0);

        ResolveOutcome outcome = workflow.resolve("pid-shape", "alice", "denied");

        assertThat(outcome.pendingId()).isEqualTo("pid-shape");
        assertThat(outcome.conversationId()).isEqualTo("conv-shape");
        assertThat(outcome.toolName()).isEqualTo("search_web");
        assertThat(outcome.messagesRewritten()).isZero();
    }

    // ---------- helpers ----------

    private PendingApproval seedPending(String pendingId, String conversationId, String toolName) {
        // Use the public createPending overload, then re-key the map under the
        // requested pendingId so the test asserts work against a stable id.
        // The recovery constructor is package-visible from this same package.
        PendingApproval p = new PendingApproval(
                pendingId, conversationId, "system", toolName, "{}", "test",
                java.time.Instant.now(), "pending");
        approvalService.registerRecovered(p);
        return p;
    }
}
