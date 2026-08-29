package vip.mate.workspace.conversation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.common.result.R;
import vip.mate.team.service.TeamWorkerConversationGovernanceService;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTeamWorkerTranscriptTest {

    @Mock private ConversationService conversationService;
    @Mock private ChatStreamTracker streamTracker;
    @Mock private TeamWorkerConversationGovernanceService teamWorkerGovernanceService;
    @Mock private Authentication authentication;

    private ConversationController controller;

    @BeforeEach
    void setUp() {
        controller = new ConversationController(conversationService, streamTracker, teamWorkerGovernanceService);
        when(authentication.getName()).thenReturn("workspace-admin");
    }

    @Test
    void listMessagesAllowsVerifiedTeamWorkerTranscriptForNonOwner() {
        when(conversationService.isConversationOwner("worker-conversation", "workspace-admin"))
                .thenReturn(false);
        when(teamWorkerGovernanceService.canReadTranscript("worker-conversation", 77L, 501L,
                "workspace-admin")).thenReturn(true);
        when(conversationService.listMessageViews("worker-conversation")).thenReturn(List.of());

        R<?> result = controller.listMessages("worker-conversation", null, null, 77L, 501L,
                authentication);

        assertEquals(200, result.getCode());
        assertEquals(List.of(), result.getData());
    }

    @Test
    void listMessagesRejectsNonOwnerWhenWorkerTranscriptIsNotVerified() {
        when(conversationService.isConversationOwner("ordinary-conversation", "workspace-admin"))
                .thenReturn(false);
        when(teamWorkerGovernanceService.canReadTranscript("ordinary-conversation", 77L, 501L,
                "workspace-admin")).thenReturn(false);

        R<?> result = controller.listMessages("ordinary-conversation", null, null, 77L, 501L,
                authentication);

        assertEquals(403, result.getCode());
        verify(conversationService, never()).listMessageViews("ordinary-conversation");
    }
}
