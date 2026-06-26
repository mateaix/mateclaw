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
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.approval.event.WorkflowApprovalResolvedEvent;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.workspace.conversation.ConversationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies ISSUE #413 P0-B2: a workflow-scoped approval created via
 * {@code requestWorkflowApproval} is registered into the in-memory map, so a
 * subsequent {@code resolve("wf-...")} walks the full two-phase contract and
 * publishes {@link WorkflowApprovalResolvedEvent} — the event that
 * {@code ApprovalResumeBridge} listens for to resume the paused run.
 *
 * <p>Before the fix, {@code requestWorkflowApproval} only did
 * {@code approvalMapper.insert(entity)} without {@code registerRecovered}, so
 * {@code getPending("wf-...")} returned null, {@code performResolve}
 * short-circuited at the "not pending" guard, the event was never published,
 * and {@code ApprovalResumeBridge} was dead code.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowApprovalResumeBridgeTest {

    @Mock private ToolApprovalMapper approvalMapper;
    @Mock private ConversationService conversationService;

    private ApprovalService approvalService;
    private CapturingEventPublisher publisher;
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
        publisher = new CapturingEventPublisher();
        workflow = new ApprovalWorkflowService(
                approvalService, approvalMapper, new ObjectMapper(), conversationService);
        // events is @Autowired(required = false) with no setter; inject via
        // reflection so this unit test (no Spring context) can capture the
        // WorkflowApprovalResolvedEvent publish.
        try {
            var field = ApprovalWorkflowService.class.getDeclaredField("events");
            field.setAccessible(true);
            field.set(workflow, publisher);
        } catch (Exception e) {
            throw new IllegalStateException("failed to inject event publisher", e);
        }
    }

    @Test
    @DisplayName("requestWorkflowApproval registers the wf- approval into the in-memory map")
    void requestRegistersIntoMemoryMap() {
        // insert returns the row id the adapter writes back as external_approval_id.
        when(approvalMapper.insert(any(ToolApprovalEntity.class))).thenAnswer(inv -> {
            ((ToolApprovalEntity) inv.getArgument(0)).setId(42L);
            return 1;
        });

        Long approvalId = workflow.requestWorkflowApproval(
                1L, 100L, 7L, "manager", "please approve", java.util.List.of("web"), 1800);

        assertThat(approvalId).isEqualTo(42L);

        // The fix: the wf- entry is now in the in-memory map, queryable by the
        // synthetic conversation key. Before the fix, getPending("wf-...")
        // returned null and the resolve path dead-ended at the "not pending"
        // guard, leaving ApprovalResumeBridge as dead code.
        PendingApproval pending = approvalService.findPendingByConversation("workflow:run:100");
        assertThat(pending).as("wf- approval must be in the in-memory map after request").isNotNull();
        assertThat(pending.getPendingId()).startsWith("wf-");
        assertThat(pending.getToolName()).isEqualTo("workflow:manager");
    }

    @Test
    @DisplayName("resolve('wf-...') publishes WorkflowApprovalResolvedEvent after the fix")
    void resolvePublishesWorkflowEvent() {
        // Seed via requestWorkflowApproval so the entry is in the map under a
        // wf- pendingId (the same path the AwaitApprovalStepAdapter takes).
        when(approvalMapper.insert(any(ToolApprovalEntity.class))).thenAnswer(inv -> {
            ((ToolApprovalEntity) inv.getArgument(0)).setId(42L);
            return 1;
        });
        workflow.requestWorkflowApproval(
                1L, 100L, 7L, "manager", "please approve", java.util.List.of("web"), 1800);

        // Recover the generated wf- pendingId via the conversation key.
        PendingApproval pending = approvalService.findPendingByConversation("workflow:run:100");
        assertThat(pending).as("wf- approval must be in the in-memory map after request").isNotNull();
        assertThat(pending.getPendingId()).startsWith("wf-");
        String pendingId = pending.getPendingId();

        // Two-phase resolve: DB UPDATE conditional on PENDING succeeds.
        when(approvalMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(conversationService.markPendingApprovalsResolved(
                eq("workflow:run:100"), eq(Set.of(pendingId)), any())).thenReturn(0);
        // selectOne lookup for the workflow-bridge row id (Phase 4).
        ToolApprovalEntity row = new ToolApprovalEntity();
        row.setId(42L);
        row.setPendingId(pendingId);
        when(approvalMapper.selectOne(any())).thenReturn(row);

        ResolveOutcome outcome = workflow.resolve(pendingId, "operator", "approved");

        assertThat(outcome.dbSynced()).isTrue();
        assertThat(outcome.decision()).isEqualTo("approved");

        // The critical assertion: the workflow-resolved event was published.
        // ApprovalResumeBridge listens for this to call WorkflowResumer.resume.
        assertThat(publisher.workflowEvents).hasSize(1);
        WorkflowApprovalResolvedEvent ev = publisher.workflowEvents.get(0);
        assertThat(ev.approvalRowId()).isEqualTo(42L);
        assertThat(ev.pendingId()).isEqualTo(pendingId);
        assertThat(ev.decision()).isEqualTo("approved");
    }

    @Test
    @DisplayName("resolve of a wf- approval that was NOT registered is still a safe no-op")
    void resolveUnregisteredWorkflowApprovalIsNoop() {
        // Simulate the pre-fix state: a wf- pendingId that never entered the map.
        ResolveOutcome outcome = workflow.resolve("wf-ghostthatdoesnotexist", "operator", "approved");

        assertThat(outcome.isAlreadyResolved()).isTrue();
        assertThat(publisher.workflowEvents).isEmpty();
    }

    /** Captures published events so tests can assert on the workflow-bridge event. */
    static class CapturingEventPublisher implements ApplicationEventPublisher {
        final List<WorkflowApprovalResolvedEvent> workflowEvents = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            if (event instanceof WorkflowApprovalResolvedEvent wfe) {
                workflowEvents.add(wfe);
            }
        }
    }
}
