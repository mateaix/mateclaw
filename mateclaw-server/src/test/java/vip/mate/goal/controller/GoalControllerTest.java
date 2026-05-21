package vip.mate.goal.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.GoalUpdateRequest;
import vip.mate.goal.service.GoalService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorization + happy-path coverage for {@link GoalController}.
 *
 * <p>Every write must:
 * 1. Resolve the goal's conversation via service.getById (where applicable).
 * 2. Reject non-owners with 403 before delegating to the service.
 * 3. Delegate to the service when authorized.
 */
@ExtendWith(MockitoExtension.class)
class GoalControllerTest {

    @Mock private GoalService goalService;
    @Mock private ConversationService conversationService;
    @Mock private Authentication auth;

    private GoalController controller;

    @BeforeEach
    void setUp() {
        controller = new GoalController(goalService, conversationService);
        when(auth.getName()).thenReturn("alice");
    }

    private GoalEntity goal(Long id, String convId, GoalStatus status) {
        GoalEntity g = new GoalEntity();
        g.setId(id);
        g.setConversationId(convId);
        g.setAgentId(10L);
        g.setWorkspaceId(1L);
        g.setCreatedBy("alice");
        g.setTitle("ship");
        g.setStatus(status);
        return g;
    }

    private GoalCreateRequest req(String convId) {
        GoalCreateRequest r = new GoalCreateRequest();
        r.setConversationId(convId);
        r.setAgentId(10L);
        r.setWorkspaceId(1L);
        r.setTitle("ship");
        return r;
    }

    private ConversationEntity conv(String convId, Long agentId, Long workspaceId) {
        ConversationEntity c = new ConversationEntity();
        c.setConversationId(convId);
        c.setUsername("alice");
        c.setAgentId(agentId);
        c.setWorkspaceId(workspaceId);
        return c;
    }

    // ==================== create ====================

    @Test
    void create_succeeds_whenOwner() {
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(true);
        when(conversationService.findByConversationId("conv-1")).thenReturn(conv("conv-1", 10L, 1L));
        when(goalService.create(any(), eq("alice")))
                .thenReturn(goal(1L, "conv-1", GoalStatus.ACTIVE));
        R<GoalEntity> result = controller.create(req("conv-1"), auth);
        assertNotNull(result.getData());
        assertEquals(1L, result.getData().getId());
    }

    @Test
    void create_overridesAgentAndWorkspace_fromConversation() {
        // Request claims agentId=99 / workspaceId=77 — the controller must
        // ignore those and use the conversation's own bindings instead.
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(true);
        when(conversationService.findByConversationId("conv-1")).thenReturn(conv("conv-1", 42L, 7L));
        when(goalService.create(any(), eq("alice")))
                .thenReturn(goal(1L, "conv-1", GoalStatus.ACTIVE));
        GoalCreateRequest r = req("conv-1");
        r.setAgentId(99L);
        r.setWorkspaceId(77L);
        controller.create(r, auth);
        org.mockito.ArgumentCaptor<GoalCreateRequest> captor =
                org.mockito.ArgumentCaptor.forClass(GoalCreateRequest.class);
        verify(goalService).create(captor.capture(), eq("alice"));
        assertEquals(42L, captor.getValue().getAgentId());
        assertEquals(7L, captor.getValue().getWorkspaceId());
    }

    @Test
    void create_returns404_whenConversationMissing() {
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(true);
        when(conversationService.findByConversationId("conv-1")).thenReturn(null);
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.create(req("conv-1"), auth));
        assertEquals(404, ex.getCode());
        verify(goalService, never()).create(any(), anyString());
    }

    @Test
    void create_returns409_whenConversationHasNoAgent() {
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(true);
        when(conversationService.findByConversationId("conv-1"))
                .thenReturn(conv("conv-1", null, 1L));
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.create(req("conv-1"), auth));
        assertEquals(409, ex.getCode());
        verify(goalService, never()).create(any(), anyString());
    }

    @Test
    void create_returns403_whenNotOwner() {
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(false);
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.create(req("conv-1"), auth));
        assertEquals(403, ex.getCode());
        verify(goalService, never()).create(any(), anyString());
    }

    @Test
    void create_returns400_whenConversationIdBlank() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.create(req(""), auth));
        assertEquals(400, ex.getCode());
    }

    // ==================== find / get ====================

    @Test
    void findActive_returnsNull_whenNoActiveGoal() {
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(true);
        when(goalService.findActiveByConversation("conv-1")).thenReturn(null);
        assertNull(controller.findActive("conv-1", auth).getData());
    }

    @Test
    void get_returns403_whenCallerIsNotOwner() {
        when(goalService.getById(1L)).thenReturn(goal(1L, "conv-1", GoalStatus.ACTIVE));
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(false);
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.get(1L, auth));
        assertEquals(403, ex.getCode());
    }

    // ==================== state machine ====================

    @Test
    void pause_delegatesToService_whenOwner() {
        GoalEntity g = goal(1L, "conv-1", GoalStatus.ACTIVE);
        when(goalService.getById(1L)).thenReturn(g);
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(true);
        when(goalService.pause(1L, "alice")).thenReturn(goal(1L, "conv-1", GoalStatus.PAUSED));

        R<GoalEntity> result = controller.pause(1L, auth);
        assertEquals(GoalStatus.PAUSED, result.getData().getStatus());
    }

    @Test
    void abandon_returns403_whenCallerIsNotOwner() {
        when(goalService.getById(1L)).thenReturn(goal(1L, "conv-1", GoalStatus.ACTIVE));
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(false);
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.abandon(1L, auth));
        assertEquals(403, ex.getCode());
        verify(goalService, never()).abandon(any(), anyString());
    }

    // ==================== update / criteria ====================

    @Test
    void update_delegatesToService_whenOwner() {
        when(goalService.getById(1L)).thenReturn(goal(1L, "conv-1", GoalStatus.ACTIVE));
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(true);
        when(goalService.update(eq(1L), any(), eq("alice")))
                .thenReturn(goal(1L, "conv-1", GoalStatus.ACTIVE));
        GoalUpdateRequest req = new GoalUpdateRequest();
        req.setTitle("new title");
        controller.update(1L, req, auth);
        verify(goalService).update(eq(1L), any(), eq("alice"));
    }

    @Test
    void addCriterion_passesCriterionStringFromBody() {
        when(goalService.getById(1L)).thenReturn(goal(1L, "conv-1", GoalStatus.ACTIVE));
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(true);
        when(goalService.appendCriterion(eq(1L), eq("tests pass"), eq("alice")))
                .thenReturn(goal(1L, "conv-1", GoalStatus.ACTIVE));

        controller.addCriterion(1L, Map.of("criterion", "tests pass"), auth);
        verify(goalService).appendCriterion(1L, "tests pass", "alice");
    }
}
