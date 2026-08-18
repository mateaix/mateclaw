package vip.mate.workspace.conversation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceUserWriteGuardTest {

    @Mock private ConversationMapper conversationMapper;
    @InjectMocks private ConversationService service;

    @Test
    void rejectsUserWritesToPersistedTeamWorkersButAllowsSystemPersistence() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("team_worker"));

        assertThat(service.isUserMessageAllowed("worker")).isFalse();
        // The guard is deliberately separate from saveMessage: internal team execution
        // continues to persist user/assistant evidence through the existing API.
    }

    @Test
    void allowsPrimaryAndNotYetPersistedConversations() {
        when(conversationMapper.selectOne(any()))
                .thenReturn(conversation("primary"))
                .thenReturn(null);

        assertThat(service.isUserMessageAllowed("primary")).isTrue();
        assertThat(service.isUserMessageAllowed("new-conversation")).isTrue();
    }

    @Test
    void rejectsLegacyWorkerEvenWhenMigrationDefaultedItsKindToPrimary() {
        ConversationEntity legacy = conversation("primary");
        legacy.setConversationId("team-task-legacy");
        when(conversationMapper.selectOne(any())).thenReturn(legacy);

        assertThat(service.isUserMessageAllowed("team-task-legacy")).isFalse();
    }

    private static ConversationEntity conversation(String kind) {
        ConversationEntity entity = new ConversationEntity();
        entity.setConversationId("worker");
        entity.setConversationKind(kind);
        return entity;
    }
}
