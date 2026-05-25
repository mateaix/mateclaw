package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for issue #66: when a user uploads an image and asks the
 * agent to extract text from it, the agent must see at least a textual marker
 * for the attachment. The previous {@code renderMessageContent} fell into the
 * {@code default} branch for {@code image}/{@code video}/{@code audio} parts
 * and returned an empty {@code part.getText()}, silently dropping the
 * attachment from the rendered text. The agent then received only the user's
 * accompanying prose and asked "which image?" — even though the file was on
 * disk and visible in the UI.
 *
 * <p>The marker also enables file-reading tools ({@code read_file},
 * {@code extract_document_text}, {@code detect_file_type}) as a fallback when
 * the multimodal {@code Media} injection is silently stripped upstream.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceRenderMessageContentTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private AgentMapper agentMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private ConversationService service;

    @Test
    @DisplayName("Image attachment is rendered with [图片] marker and path — issue #66")
    void imagePart_rendersMarkerWithPath() throws Exception {
        MessageEntity msg = userMessageWithParts("提取图片中的文字", List.of(
                MessageContentPart.text("提取图片中的文字"),
                imagePart("image.png", "data/chat-uploads/conv1/123_image.png")
        ));

        String rendered = service.renderMessageContent(msg);

        assertThat(rendered).contains("提取图片中的文字");
        assertThat(rendered)
                .as("image attachment must surface in the rendered text — agent has nothing else to anchor on when upstream strips Media")
                .contains("[图片]")
                .contains("image.png")
                .contains("data/chat-uploads/conv1/123_image.png");
    }

    @Test
    @DisplayName("Video / audio / 3D-model parts also render with their markers")
    void otherMediaParts_rendersOwnMarkers() throws Exception {
        MessageEntity msg = userMessageWithParts("看看这些", List.of(
                MessageContentPart.text("看看这些"),
                videoPart("clip.mp4", "data/chat-uploads/conv1/clip.mp4"),
                audioPart("voice.mp3", "data/chat-uploads/conv1/voice.mp3"),
                model3dPart("scene.glb", "data/chat-uploads/conv1/scene.glb")
        ));

        String rendered = service.renderMessageContent(msg);

        assertThat(rendered).contains("[视频]").contains("clip.mp4");
        assertThat(rendered).contains("[音频]").contains("voice.mp3");
        assertThat(rendered).contains("[3D 模型]").contains("scene.glb");
    }

    @Test
    @DisplayName("Image part without path falls back to label + filename only")
    void imagePart_withoutPath_rendersLabelAndName() throws Exception {
        MessageContentPart image = MessageContentPart.image("media-1", "https://example.com/x.png");
        image.setFileName("x.png");
        MessageEntity msg = userMessageWithParts("hi", List.of(
                MessageContentPart.text("hi"),
                image
        ));

        String rendered = service.renderMessageContent(msg);

        assertThat(rendered).contains("[图片] x.png");
        assertThat(rendered).doesNotContain("（路径:");
    }

    @Test
    @DisplayName("Text-only message renders unchanged — no attachment marker noise")
    void textOnly_unaffected() throws Exception {
        MessageEntity msg = userMessageWithParts("hello", List.of(
                MessageContentPart.text("hello")
        ));

        String rendered = service.renderMessageContent(msg);

        assertThat(rendered).isEqualTo("hello");
    }

    private MessageEntity userMessageWithParts(String content, List<MessageContentPart> parts) throws Exception {
        MessageEntity msg = new MessageEntity();
        msg.setRole("user");
        msg.setContent(content);
        msg.setContentParts(objectMapper.writeValueAsString(parts));
        return msg;
    }

    private static MessageContentPart imagePart(String fileName, String path) {
        MessageContentPart part = new MessageContentPart();
        part.setType("image");
        part.setFileName(fileName);
        part.setPath(path);
        part.setContentType("image/png");
        return part;
    }

    private static MessageContentPart videoPart(String fileName, String path) {
        MessageContentPart part = new MessageContentPart();
        part.setType("video");
        part.setFileName(fileName);
        part.setPath(path);
        part.setContentType("video/mp4");
        return part;
    }

    private static MessageContentPart audioPart(String fileName, String path) {
        MessageContentPart part = new MessageContentPart();
        part.setType("audio");
        part.setFileName(fileName);
        part.setPath(path);
        part.setContentType("audio/mpeg");
        return part;
    }

    private static MessageContentPart model3dPart(String fileName, String path) {
        MessageContentPart part = new MessageContentPart();
        part.setType("model3d");
        part.setFileName(fileName);
        part.setPath(path);
        part.setContentType("model/gltf-binary");
        return part;
    }
}
