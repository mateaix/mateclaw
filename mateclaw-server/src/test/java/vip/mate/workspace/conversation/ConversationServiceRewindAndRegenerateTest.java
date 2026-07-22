package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rewind + regenerate data contract (issue #547):
 * <ul>
 *   <li>{@code rewindToMessage} deletes the target row and everything after
 *       it, then recomputes the conversation aggregates (messageCount /
 *       lastMessage) from the surviving rows.</li>
 *   <li>{@code prepareRegenerate} drops the trailing reply block (assistant +
 *       trailing system rows) and returns the most recent user message as the
 *       seed, WITHOUT deleting that user row — the caller reuses it instead of
 *       inserting a duplicate.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceRewindAndRegenerateTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private ConversationService service;

    private static MessageEntity msg(long id, String role, String content) {
        MessageEntity m = new MessageEntity();
        m.setId(id);
        m.setConversationId("conv-1");
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private ConversationEntity stubConversation() {
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId("conv-1");
        conv.setMessageCount(99);
        when(conversationMapper.selectOne(any())).thenReturn(conv);
        return conv;
    }

    @Test
    @DisplayName("rewindToMessage deletes the target and everything after, recomputing aggregates")
    void rewindMiddleMessage() {
        when(messageMapper.selectList(any())).thenReturn(List.of(
                msg(1, "user", "Q1"),
                msg(2, "assistant", "A1"),
                msg(3, "user", "Q2"),
                msg(4, "assistant", "A2")));
        ConversationEntity conv = stubConversation();

        ConversationService.RewindResult result = service.rewindToMessage("conv-1", 3L);

        assertThat(result).isNotNull();
        assertThat(result.deletedCount()).isEqualTo(2);
        assertThat(result.messageCount()).isEqualTo(2);
        assertThat(result.lastMessage()).isEqualTo("A1");
        verify(messageMapper).delete(any());
        ArgumentCaptor<ConversationEntity> updated = ArgumentCaptor.forClass(ConversationEntity.class);
        verify(conversationMapper).updateById(updated.capture());
        assertThat(updated.getValue().getMessageCount()).isEqualTo(2);
        assertThat(updated.getValue().getLastMessage()).isEqualTo("A1");
        assertThat(conv.getLastActiveTime()).isNotNull();
    }

    @Test
    @DisplayName("rewinding to the first message clears lastMessage entirely")
    void rewindToFirstMessageClearsPreview() {
        when(messageMapper.selectList(any())).thenReturn(List.of(
                msg(1, "user", "Q1"),
                msg(2, "assistant", "A1")));
        stubConversation();

        ConversationService.RewindResult result = service.rewindToMessage("conv-1", 1L);

        assertThat(result).isNotNull();
        assertThat(result.deletedCount()).isEqualTo(2);
        assertThat(result.messageCount()).isZero();
        assertThat(result.lastMessage()).isNull();
    }

    @Test
    @DisplayName("rewindToMessage returns null for a message not in the conversation")
    void rewindUnknownMessageReturnsNull() {
        when(messageMapper.selectList(any())).thenReturn(List.of(msg(1, "user", "Q1")));

        ConversationService.RewindResult result = service.rewindToMessage("conv-1", 42L);

        assertThat(result).isNull();
        verify(messageMapper, never()).delete(any());
        verify(conversationMapper, never()).updateById(any(ConversationEntity.class));
    }

    @Test
    @DisplayName("prepareRegenerate drops the trailing assistant block and returns the seed user row")
    void prepareRegenerateDeletesTrailingReply() {
        when(messageMapper.selectList(any())).thenReturn(List.of(
                msg(1, "user", "Q1"),
                msg(2, "assistant", "A1"),
                msg(3, "user", "Q2"),
                msg(4, "assistant", "A2"),
                msg(5, "system", "boundary")));
        stubConversation();

        ConversationService.RegenerateSeed seed = service.prepareRegenerate("conv-1");

        assertThat(seed).isNotNull();
        assertThat(seed.seedMessageId()).isEqualTo(3L);
        assertThat(seed.content()).isEqualTo("Q2");
        // The reply block (assistant + trailing system rows) is gone; the seed
        // user row survives, so the aggregates reflect rows 1-3.
        verify(messageMapper).delete(any());
        ArgumentCaptor<ConversationEntity> updated = ArgumentCaptor.forClass(ConversationEntity.class);
        verify(conversationMapper).updateById(updated.capture());
        assertThat(updated.getValue().getMessageCount()).isEqualTo(3);
        assertThat(updated.getValue().getLastMessage()).isEqualTo("A1");
    }

    @Test
    @DisplayName("prepareRegenerate with the user message already at the tail deletes nothing (recovery path)")
    void prepareRegenerateUserAtTailDeletesNothing() {
        when(messageMapper.selectList(any())).thenReturn(List.of(
                msg(1, "user", "Q1"),
                msg(2, "assistant", "A1"),
                msg(3, "user", "Q2")));

        ConversationService.RegenerateSeed seed = service.prepareRegenerate("conv-1");

        assertThat(seed).isNotNull();
        assertThat(seed.seedMessageId()).isEqualTo(3L);
        assertThat(seed.content()).isEqualTo("Q2");
        verify(messageMapper, never()).delete(any());
    }

    @Test
    @DisplayName("prepareRegenerate returns null when the conversation has no user message")
    void prepareRegenerateWithoutUserMessageReturnsNull() {
        when(messageMapper.selectList(any())).thenReturn(List.of(
                msg(1, "system", "boundary"),
                msg(2, "assistant", "greeting")));

        assertThat(service.prepareRegenerate("conv-1")).isNull();
        verify(messageMapper, never()).delete(any());
    }

    @Test
    @DisplayName("prepareRegenerate on an empty conversation returns null")
    void prepareRegenerateEmptyConversationReturnsNull() {
        when(messageMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.prepareRegenerate("conv-1")).isNull();
    }
}
