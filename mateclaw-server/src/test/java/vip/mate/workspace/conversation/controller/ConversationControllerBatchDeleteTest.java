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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationControllerBatchDeleteTest {

    @Mock private ConversationService conversationService;
    @Mock private ChatStreamTracker streamTracker;
    @Mock private TeamWorkerConversationGovernanceService teamWorkerGovernanceService;
    @Mock private Authentication authentication;

    private ConversationController controller;

    @BeforeEach
    void setUp() {
        controller = new ConversationController(conversationService, streamTracker, teamWorkerGovernanceService);
        when(authentication.getName()).thenReturn("alice");
    }

    @Test
    void batchDelete_deduplicatesAndTrimsIds_beforeOwnershipCheck() {
        when(conversationService.isConversationOwner("conv-1", "alice")).thenReturn(true);
        when(conversationService.isConversationOwner("conv-2", "alice")).thenReturn(false);

        R<Integer> result = controller.batchDelete(Map.of(
                "conversationIds", List.of(" conv-1 ", "conv-1", "", "conv-2")), authentication);

        assertEquals(1, result.getData());
        verify(conversationService).isConversationOwner("conv-1", "alice");
        verify(conversationService).isConversationOwner("conv-2", "alice");
        verify(conversationService).deleteConversation("conv-1");
        verify(conversationService, never()).deleteConversation("conv-2");
    }

    @Test
    void batchDelete_rejectsBlankOnlyRequest() {
        R<Integer> result = controller.batchDelete(Map.of(
                "conversationIds", List.of("", "  ")), authentication);

        assertNull(result.getData());
        assertEquals(400, result.getCode());
        verify(conversationService, never()).isConversationOwner(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void batchDelete_rejectsMoreThanMaximumUniqueIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 201; i++) ids.add("conv-" + i);

        R<Integer> result = controller.batchDelete(Map.of("conversationIds", ids), authentication);

        assertNull(result.getData());
        assertEquals(400, result.getCode());
        verify(conversationService, never()).isConversationOwner(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
