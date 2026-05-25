package vip.mate.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.wecom.cards.WeComCardDispatcher;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@code WeComChannelAdapter.extractQuoteContext} — the
 * inbound quote-message parser that converts WeCom's {@code body.quote}
 * field into a prefix string + attached media parts the agent can read.
 *
 * <p>Quoted-message context is the most common reason agent replies "go
 * off-topic" on IM: the user long-presses a previous bubble, types a
 * follow-up like "解释一下", and assumes the agent sees both. Without
 * this parser the agent only saw the new text and silently lost the
 * referenced content.
 *
 * <p>These tests pin (1) the prefix string shape so prompts stay stable
 * across releases, (2) flattening rules for {@code mixed} quotes, and
 * (3) the empty-result contract (null when nothing useful to extract)
 * so the caller can treat null as "no quote context".
 */
class QuoteContextTest {

    private WeComChannelAdapter adapter;
    private Method extract;

    @BeforeEach
    void setUp() throws Exception {
        ChannelEntity entity = new ChannelEntity();
        entity.setId(1L);
        entity.setChannelType("wecom");
        entity.setConfigJson("{\"media_download_enabled\": false}");  // skip real downloads
        adapter = new WeComChannelAdapter(
                entity,
                Mockito.mock(ChannelMessageRouter.class),
                new ObjectMapper(),
                Mockito.mock(ApprovalNotificationService.class),
                Mockito.mock(WeComCardDispatcher.class),
                Mockito.mock(WeComKeepaliveScheduler.class));
        extract = WeComChannelAdapter.class.getDeclaredMethod(
                "extractQuoteContext",
                Map.class, String.class, String.class, String.class, String.class);
        extract.setAccessible(true);
    }

    private Object invoke(Map<String, Object> body) throws Exception {
        return extract.invoke(adapter, body, "msg-1", "alice", "alice", "single");
    }

    @Test
    @DisplayName("missing quote field returns null")
    void noQuote() throws Exception {
        assertNull(invoke(Map.of()));
        assertNull(invoke(Map.of("text", Map.of("content", "hi"))));
    }

    @Test
    @DisplayName("blank msgtype returns null (defensive)")
    void blankQuoteType() throws Exception {
        assertNull(invoke(Map.of("quote", Map.of("msgtype", ""))));
    }

    @Test
    @DisplayName("text quote produces a [引用消息: ...] prefix and no attached parts")
    void textQuote() throws Exception {
        Object ctx = invoke(Map.of("quote", Map.of(
                "msgtype", "text",
                "text", Map.of("content", "你好图片是什么意思"))));
        assertNotNull(ctx);
        // QuoteContext is a private record — exercise via reflection on accessor methods.
        String prefix = (String) ctx.getClass().getMethod("prefix").invoke(ctx);
        @SuppressWarnings("unchecked")
        List<MessageContentPart> parts = (List<MessageContentPart>)
                ctx.getClass().getMethod("attachedParts").invoke(ctx);
        assertEquals("[引用消息: 你好图片是什么意思]\n", prefix);
        assertTrue(parts.isEmpty(), "text-only quote attaches no media");
    }

    @Test
    @DisplayName("image quote attaches a part and notes [图片] in prefix")
    void imageQuote() throws Exception {
        Object ctx = invoke(Map.of("quote", Map.of(
                "msgtype", "image",
                "image", Map.of(
                        "url", "https://example.com/x.jpg",
                        "aeskey", "k"))));
        assertNotNull(ctx);
        String prefix = (String) ctx.getClass().getMethod("prefix").invoke(ctx);
        @SuppressWarnings("unchecked")
        List<MessageContentPart> parts = (List<MessageContentPart>)
                ctx.getClass().getMethod("attachedParts").invoke(ctx);
        assertEquals("[引用消息: [图片]]\n", prefix);
        assertEquals(1, parts.size());
        assertEquals("image", parts.get(0).getType());
    }

    @Test
    @DisplayName("file quote uses the original filename in the prefix")
    void fileQuote() throws Exception {
        Object ctx = invoke(Map.of("quote", Map.of(
                "msgtype", "file",
                "file", Map.of(
                        "url", "https://example.com/x.pdf",
                        "filename", "report.pdf"))));
        String prefix = (String) ctx.getClass().getMethod("prefix").invoke(ctx);
        @SuppressWarnings("unchecked")
        List<MessageContentPart> parts = (List<MessageContentPart>)
                ctx.getClass().getMethod("attachedParts").invoke(ctx);
        assertEquals("[引用消息: [文件: report.pdf]]\n", prefix);
        assertEquals(1, parts.size());
        assertEquals("file", parts.get(0).getType());
    }

    @Test
    @DisplayName("voice quote with ASR text gets surfaced; without ASR shows [语音消息]")
    void voiceQuote() throws Exception {
        Object withAsr = invoke(Map.of("quote", Map.of(
                "msgtype", "voice",
                "voice", Map.of("content", "明天开会"))));
        assertEquals("[引用消息: [语音] 明天开会]\n",
                withAsr.getClass().getMethod("prefix").invoke(withAsr));

        Object empty = invoke(Map.of("quote", Map.of(
                "msgtype", "voice",
                "voice", Map.of("content", ""))));
        assertEquals("[引用消息: [语音消息]]\n",
                empty.getClass().getMethod("prefix").invoke(empty));
    }

    @Test
    @DisplayName("mixed quote flattens to a space-joined summary and merges attached parts")
    void mixedQuote() throws Exception {
        Object ctx = invoke(Map.of("quote", Map.of(
                "msgtype", "mixed",
                "mixed", Map.of("msg_item", List.of(
                        Map.of("msgtype", "text", "text", Map.of("content", "看这张图")),
                        Map.of("msgtype", "image", "image", Map.of(
                                "url", "https://example.com/y.jpg",
                                "aeskey", "k")))))));
        String prefix = (String) ctx.getClass().getMethod("prefix").invoke(ctx);
        @SuppressWarnings("unchecked")
        List<MessageContentPart> parts = (List<MessageContentPart>)
                ctx.getClass().getMethod("attachedParts").invoke(ctx);
        assertEquals("[引用消息: 看这张图 [图片]]\n", prefix);
        assertEquals(1, parts.size(), "mixed image gets attached as a media part");
    }

    @Test
    @DisplayName("unknown quote sub-type still produces a [<type>] tag (informative, not silent)")
    void unknownQuoteType() throws Exception {
        Object ctx = invoke(Map.of("quote", Map.of(
                "msgtype", "appmsg",
                "appmsg", Map.of("title", "some link"))));
        String prefix = (String) ctx.getClass().getMethod("prefix").invoke(ctx);
        assertEquals("[引用消息: [appmsg]]\n", prefix);
    }
}
