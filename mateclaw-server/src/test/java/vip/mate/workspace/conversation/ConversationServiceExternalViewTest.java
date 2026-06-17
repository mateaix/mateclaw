package vip.mate.workspace.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.conversation.vo.MessageVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pin that the external (webchat-facing) message view never leaks the
 * server-side absolute file path — neither in the structured {@code path} field
 * nor in the rendered text — while the internal view still carries it for the
 * agent's file tools.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceExternalViewTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private AgentMapper agentMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private ConversationService service;

    private static final String SECRET_PATH = "/srv/mateclaw/data/chat-uploads/secret/doc.pdf";

    private MessageEntity fileMessage() {
        MessageEntity m = new MessageEntity();
        m.setId(1L);
        m.setConversationId("c1");
        m.setRole("assistant");
        m.setContent("here is your file");
        m.setContentParts("[{\"type\":\"file\",\"fileName\":\"doc.pdf\",\"path\":\""
                + SECRET_PATH + "\"}]");
        m.setStatus("completed");
        m.setCreateTime(LocalDateTime.now());
        return m;
    }

    @Test
    @DisplayName("external view nulls part.path and omits path from rendered text")
    void externalViewStripsPath() {
        when(messageMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(fileMessage()));

        List<MessageVO> views = service.listMessageViewsExternal("c1");

        assertThat(views).hasSize(1);
        MessageVO vo = views.get(0);
        assertThat(vo.getContentParts().get(0).getPath()).isNull();
        assertThat(vo.getContent()).doesNotContain(SECRET_PATH);
        assertThat(vo.getContent()).doesNotContain("路径");
        // filename still surfaced so the visitor can recognize the attachment
        assertThat(vo.getContent()).contains("doc.pdf");
    }

    @Test
    @DisplayName("internal view keeps the path for the agent's file tools")
    void internalViewKeepsPath() {
        when(messageMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(fileMessage()));

        List<MessageVO> views = service.listMessageViews("c1");

        assertThat(views.get(0).getContentParts().get(0).getPath()).isEqualTo(SECRET_PATH);
        assertThat(views.get(0).getContent()).contains(SECRET_PATH);
    }

    @Test
    @DisplayName("toExternalMessageViews strips path on a pre-loaded list (paginated path)")
    void toExternalMessageViewsStripsPath() {
        // Used by the paginated webchat endpoint, which loads entities itself.
        List<MessageVO> views = service.toExternalMessageViews(List.of(fileMessage()));

        assertThat(views).hasSize(1);
        assertThat(views.get(0).getContentParts().get(0).getPath()).isNull();
        assertThat(views.get(0).getContent()).doesNotContain(SECRET_PATH);
    }
}
