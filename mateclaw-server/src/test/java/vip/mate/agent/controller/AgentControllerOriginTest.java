package vip.mate.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.service.AgentGenerationService;
import vip.mate.audit.service.AuditEventService;
import vip.mate.auth.service.AuthService;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.system.service.SystemSettingService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerOriginTest {

    private static final Long AGENT_ID = 10L;
    private static final Long WORKSPACE_ID = 30L;
    private static final Long MESSAGE_ID = 99L;
    private static final String CONVERSATION_ID = "agent-entry";
    private static final String MESSAGE = "do work";

    private AgentService agentService;
    private ConversationService conversations;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        conversations = mock(ConversationService.class);
        controller = new AgentController(agentService, conversations,
                mock(AuditEventService.class), mock(AuthService.class), mock(WorkspaceService.class),
                mock(ModelConfigService.class), mock(ModelCapabilityService.class),
                mock(SystemSettingService.class), mock(AgentGenerationService.class),
                new ObjectMapper());
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setWorkspaceId(WORKSPACE_ID);
        agent.setEnabled(true);
        when(agentService.getAgent(AGENT_ID)).thenReturn(agent);
        MessageEntity saved = new MessageEntity();
        saved.setId(MESSAGE_ID);
        when(conversations.saveMessage(CONVERSATION_ID, "user", MESSAGE)).thenReturn(saved);
    }

    @Test
    void sseEntryPersistsOnceAndUsesExplicitOrigin() {
        when(agentService.chatStream(eq(AGENT_ID), eq(MESSAGE), eq(CONVERSATION_ID), any()))
                .thenReturn(Flux.empty());

        controller.chatStream(AGENT_ID, MESSAGE, CONVERSATION_ID, WORKSPACE_ID);

        ArgumentCaptor<ChatOrigin> origin = ArgumentCaptor.forClass(ChatOrigin.class);
        verify(agentService, org.mockito.Mockito.timeout(1000))
                .chatStream(eq(AGENT_ID), eq(MESSAGE), eq(CONVERSATION_ID), origin.capture());
        assertEquals(MESSAGE_ID, origin.getValue().originMessageId());
        verifySingleUserSave();
        verify(agentService, never()).chatStream(AGENT_ID, MESSAGE, CONVERSATION_ID);
    }

    @Test
    void syncChatEntryPersistsOnceAndUsesExplicitOrigin() {
        AgentController.ChatRequest request = request();
        when(agentService.chat(eq(AGENT_ID), eq(MESSAGE), eq(CONVERSATION_ID), any()))
                .thenReturn("done");

        controller.chat(AGENT_ID, request, WORKSPACE_ID);

        ArgumentCaptor<ChatOrigin> origin = ArgumentCaptor.forClass(ChatOrigin.class);
        verify(agentService).chat(eq(AGENT_ID), eq(MESSAGE), eq(CONVERSATION_ID), origin.capture());
        assertEquals(MESSAGE_ID, origin.getValue().originMessageId());
        verifySingleUserSave();
        verify(agentService, never()).chat(AGENT_ID, MESSAGE, CONVERSATION_ID);
    }

    @Test
    void executeEntryPersistsOnceAndUsesExplicitOrigin() {
        AgentController.ChatRequest request = request();
        when(agentService.execute(eq(AGENT_ID), eq(MESSAGE), eq(CONVERSATION_ID), any()))
                .thenReturn("done");

        controller.execute(AGENT_ID, request, WORKSPACE_ID);

        ArgumentCaptor<ChatOrigin> origin = ArgumentCaptor.forClass(ChatOrigin.class);
        verify(agentService).execute(eq(AGENT_ID), eq(MESSAGE), eq(CONVERSATION_ID), origin.capture());
        assertEquals(MESSAGE_ID, origin.getValue().originMessageId());
        verifySingleUserSave();
        verify(agentService, never()).execute(AGENT_ID, MESSAGE, CONVERSATION_ID);
    }

    private void verifySingleUserSave() {
        verify(conversations, times(1)).saveMessage(CONVERSATION_ID, "user", MESSAGE);
    }

    private static AgentController.ChatRequest request() {
        AgentController.ChatRequest request = new AgentController.ChatRequest();
        request.setMessage(MESSAGE);
        request.setConversationId(CONVERSATION_ID);
        return request;
    }
}
