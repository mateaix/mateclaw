package vip.mate.goal.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.service.GoalService;
import vip.mate.goal.service.GoalContinuationStore;
import vip.mate.goal.service.GoalAttemptStore;
import vip.mate.workspace.conversation.ConversationService;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GoalExecutionControllerTest {
    @Test void onlyConversationOwnerMayReadExecutionState() {
        var goals=mock(GoalService.class);
        var store=mock(GoalContinuationStore.class);
        var conversations=mock(ConversationService.class);
        var goal=new GoalEntity();goal.setConversationId("private");
        when(goals.getById(1L)).thenReturn(goal);
        var attempts=mock(GoalAttemptStore.class);
        var controller=new GoalExecutionController(goals,store,conversations,attempts);
        var user=new UsernamePasswordAuthenticationToken("alice","ignored");
        assertThrows(vip.mate.exception.MateClawException.class,()->controller.execution(1L,user));
        verifyNoInteractions(store);
        when(conversations.isConversationOwner("private","alice")).thenReturn(true);
        controller.execution(1L,user);
        verify(store).get(1L);
        controller.attempts(1L,user);
        verify(attempts).listRecent(1L,50);
    }
}
