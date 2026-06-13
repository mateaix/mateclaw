package vip.mate.tool.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.routing.MediaCaptionService;
import vip.mate.llm.routing.MultimodalRouter;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #303: the on-demand {@code image_analyze} tool lets a text-only model
 * re-examine a previously uploaded image against a fresh question. These tests
 * pin image resolution (most-recent vs by-name) and the guard-rail messages.
 */
class ImageAnalyzeToolTest {

    private ConversationService conv;
    private MultimodalRouter router;
    private MediaCaptionService caption;
    private ImageAnalyzeTool tool;

    private static final String CONV = "conv-1";

    @BeforeEach
    void setUp() {
        conv = mock(ConversationService.class);
        router = mock(MultimodalRouter.class);
        caption = mock(MediaCaptionService.class);
        tool = new ImageAnalyzeTool(conv, router, caption);
        ToolExecutionContext.set(CONV, "tester");
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("Blank question → guidance, no vision call")
    void blankQuestion() {
        String out = tool.image_analyze("  ", null, null);
        assertTrue(out.contains("具体问题"));
    }

    @Test
    @DisplayName("No conversation context → cannot locate image")
    void noConversation() {
        ToolExecutionContext.clear();
        String out = tool.image_analyze("报错是什么", null, null);
        assertTrue(out.contains("无法确定当前会话"));
    }

    @Test
    @DisplayName("No image in conversation → tells the user none was found")
    void noImage() {
        MessageEntity m = msg();
        when(conv.listMessages(CONV)).thenReturn(List.of(m));
        when(conv.parseMessageParts(m)).thenReturn(List.of(MessageContentPart.text("你好")));

        String out = tool.image_analyze("报错是什么", null, null);
        assertTrue(out.contains("没有找到"));
    }

    @Test
    @DisplayName("No default vision model configured → asks user to configure one")
    void noVisionModel() {
        MessageEntity m = msg();
        when(conv.listMessages(CONV)).thenReturn(List.of(m));
        when(conv.parseMessageParts(m)).thenReturn(List.of(img("a.png", "media-a")));
        when(router.resolveVisionSidecar()).thenReturn(null);

        String out = tool.image_analyze("报错是什么", null, null);
        assertTrue(out.contains("尚未配置视觉模型"));
    }

    @Test
    @DisplayName("Most-recent image is analyzed with the question when no reference is given")
    void mostRecentImage_analyzed() {
        MessageEntity older = msg();
        MessageEntity newer = msg();
        when(conv.listMessages(CONV)).thenReturn(List.of(older, newer));
        when(conv.parseMessageParts(older)).thenReturn(List.of(img("old.png", "media-old")));
        when(conv.parseMessageParts(newer)).thenReturn(List.of(img("new.png", "media-new")));
        when(router.resolveVisionSidecar()).thenReturn(mock(ModelConfigEntity.class));
        when(caption.caption(any(), any(), any(), any()))
                .thenReturn(MediaCaptionService.CaptionResult.success("空指针异常", 5L, false));

        String out = tool.image_analyze("报错是什么", null, null);

        assertEquals("空指针异常", out);
        ArgumentCaptor<MessageContentPart> part = ArgumentCaptor.forClass(MessageContentPart.class);
        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(caption).caption(any(), part.capture(), any(), q.capture());
        assertEquals("new.png", part.getValue().getFileName(), "must pick the most recent image");
        assertEquals("报错是什么", q.getValue(), "the question must reach the vision model");
    }

    @Test
    @DisplayName("Explicit filename reference selects the matching image, not the most recent")
    void referenceByFilename_selectsMatch() {
        MessageEntity m = msg();
        when(conv.listMessages(CONV)).thenReturn(List.of(m));
        when(conv.parseMessageParts(m)).thenReturn(List.of(
                img("receipt.png", "media-r"), img("screenshot.png", "media-s")));
        when(router.resolveVisionSidecar()).thenReturn(mock(ModelConfigEntity.class));
        when(caption.caption(any(), any(), any(), any()))
                .thenReturn(MediaCaptionService.CaptionResult.success("金额 99 元", 5L, false));

        tool.image_analyze("总金额是多少", "receipt.png", null);

        ArgumentCaptor<MessageContentPart> part = ArgumentCaptor.forClass(MessageContentPart.class);
        verify(caption).caption(any(), part.capture(), any(), eq("总金额是多少"));
        assertEquals("receipt.png", part.getValue().getFileName());
    }

    @Test
    @DisplayName("Unknown filename reference → reports it was not found")
    void referenceNotFound() {
        MessageEntity m = msg();
        when(conv.listMessages(CONV)).thenReturn(List.of(m));
        when(conv.parseMessageParts(m)).thenReturn(List.of(img("a.png", "media-a")));

        String out = tool.image_analyze("看看", "does-not-exist.png", null);
        assertTrue(out.contains("does-not-exist.png"));
        assertTrue(out.contains("未找到"));
    }

    @Test
    @DisplayName("Vision call failure → friendly error naming the file")
    void captionFailure() {
        MessageEntity m = msg();
        when(conv.listMessages(CONV)).thenReturn(List.of(m));
        when(conv.parseMessageParts(m)).thenReturn(List.of(img("broken.png", "media-b")));
        when(router.resolveVisionSidecar()).thenReturn(mock(ModelConfigEntity.class));
        when(caption.caption(any(), any(), any(), any()))
                .thenReturn(MediaCaptionService.CaptionResult.failure(5L, new RuntimeException("timeout")));

        String out = tool.image_analyze("看看", null, null);
        assertTrue(out.contains("未能解析"));
        assertTrue(out.contains("broken.png"));
    }

    // ---------- helpers ----------

    private static MessageEntity msg() {
        MessageEntity m = new MessageEntity();
        m.setRole("user");
        return m;
    }

    private static MessageContentPart img(String fileName, String mediaId) {
        MessageContentPart p = new MessageContentPart();
        p.setType("image");
        p.setContentType("image/png");
        p.setFileName(fileName);
        p.setMediaId(mediaId);
        return p;
    }
}
