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
 * Pin {@code msgtype=appmsg} parsing — covers the four sub-variants
 * users actually forward to bots in production: PDF / Word / Excel
 * (file), image cards, miniprograms, and public-account article links.
 *
 * <p>Without this branch, every forwarded PDF / article / miniprogram
 * fell into the inbound switch's default and got silently dropped.
 * These tests pin (1) the text marker shape so prompts stay stable,
 * (2) the attached-media routing for file and image variants, and
 * (3) the link/miniprogram fallbacks so the agent at least knows
 * something was shared.
 */
class AppmsgContentTest {

    private WeComChannelAdapter adapter;
    private Method extract;

    @BeforeEach
    void setUp() throws Exception {
        ChannelEntity entity = new ChannelEntity();
        entity.setId(1L);
        entity.setChannelType("wecom");
        entity.setConfigJson("{\"media_download_enabled\": false}");
        adapter = new WeComChannelAdapter(
                entity,
                Mockito.mock(ChannelMessageRouter.class),
                new ObjectMapper(),
                Mockito.mock(ApprovalNotificationService.class),
                Mockito.mock(WeComCardDispatcher.class),
                Mockito.mock(WeComKeepaliveScheduler.class));
        extract = WeComChannelAdapter.class.getDeclaredMethod(
                "extractAppmsgContent",
                Map.class, String.class, String.class, String.class, String.class);
        extract.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Object invoke(Map<String, Object> body) throws Exception {
        return extract.invoke(adapter, body, "msg-1", "alice", "alice", "single");
    }

    private String text(Object ctx) throws Exception {
        return (String) ctx.getClass().getMethod("text").invoke(ctx);
    }

    @SuppressWarnings("unchecked")
    private List<MessageContentPart> parts(Object ctx) throws Exception {
        return (List<MessageContentPart>) ctx.getClass().getMethod("attachedParts").invoke(ctx);
    }

    @Test
    @DisplayName("appmsg.file → file content part + [文件: filename] marker")
    void fileVariant() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "title", "report.pdf",
                "file", Map.of(
                        "url", "https://example.com/report.pdf",
                        "aeskey", "k",
                        "filename", "report.pdf"))));
        assertEquals("[文件: report.pdf]", text(ctx));
        assertEquals(1, parts(ctx).size());
        assertEquals("file", parts(ctx).get(0).getType());
    }

    @Test
    @DisplayName("appmsg.image → image content part + [图片: title] marker")
    void imageVariant() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "title", "周末聚会",
                "image", Map.of(
                        "url", "https://example.com/photo.jpg",
                        "aeskey", "k"))));
        assertEquals("[图片: 周末聚会]", text(ctx));
        assertEquals(1, parts(ctx).size());
        assertEquals("image", parts(ctx).get(0).getType());
    }

    @Test
    @DisplayName("appmsg.image with no title → bare [图片] marker")
    void imageVariantNoTitle() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "image", Map.of(
                        "url", "https://example.com/p.jpg",
                        "aeskey", "k"))));
        assertEquals("[图片]", text(ctx));
        assertEquals(1, parts(ctx).size());
    }

    @Test
    @DisplayName("appmsg.miniprogram → [小程序: title] marker, no attached media")
    void miniprogramVariant() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "title", "外卖小程序",
                "miniprogram", Map.of("title", "美团外卖"))));
        assertEquals("[小程序: 美团外卖]", text(ctx));
        assertTrue(parts(ctx).isEmpty());
    }

    @Test
    @DisplayName("appmsg.miniprogram with no inner title falls back to top-level title")
    void miniprogramTitleFallback() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "title", "顶层标题",
                "miniprogram", Map.of())));
        assertEquals("[小程序: 顶层标题]", text(ctx));
    }

    @Test
    @DisplayName("appmsg.url (public-account article) → [链接] + title + desc + url multi-line + paste-body hint")
    void linkVariant() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "title", "深度好文：AI 的未来",
                "description", "本文探讨 AI 在企业的落地路径",
                "url", "https://mp.weixin.qq.com/s/abc123")));
        String t = text(ctx);
        assertTrue(t.startsWith("[链接] 深度好文：AI 的未来"),
                "title should follow [链接] tag; got: " + t);
        assertTrue(t.contains("本文探讨 AI 在企业的落地路径"),
                "description must be present; got: " + t);
        assertTrue(t.contains("https://mp.weixin.qq.com/s/abc123"),
                "URL must be in the text so agent can reference it; got: " + t);
        // Public-account body is captcha-gated — agent must be told not to
        // hallucinate content from the title.
        assertTrue(t.contains("公众号文章"),
                "public-account article hint must be appended; got: " + t);
        assertTrue(t.contains("不要凭标题猜测内容"),
                "directive against title-only guessing must be present; got: " + t);
        assertTrue(parts(ctx).isEmpty(), "link variant produces no attached media");
    }

    @Test
    @DisplayName("non-public-account links (regular URLs) do NOT get the paste-body hint")
    void linkVariantNonWeixinUrlNoHint() throws Exception {
        // Generic web links don't have the captcha-gate problem — fetching
        // the body via a tool is straightforward, so adding the hint would
        // be misleading.
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "title", "GitHub README",
                "url", "https://github.com/example/repo")));
        String t = text(ctx);
        assertTrue(t.contains("https://github.com/example/repo"));
        assertFalse(t.contains("公众号文章"),
                "non-mp.weixin.qq.com URLs must not trigger the public-account hint; got: " + t);
    }

    @Test
    @DisplayName("link with title only, no description")
    void linkVariantNoDesc() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "title", "标题",
                "url", "https://example.com")));
        String t = text(ctx);
        assertTrue(t.contains("[链接] 标题"));
        assertTrue(t.contains("https://example.com"));
    }

    @Test
    @DisplayName("unknown appmsg variant with title → [appmsg: title] marker")
    void unknownVariantWithTitle() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "title", "未知卡片",
                "weird_field", Map.of())));
        assertEquals("[appmsg: 未知卡片]", text(ctx));
    }

    @Test
    @DisplayName("totally empty appmsg → bare [appmsg] marker (agent at least knows something arrived)")
    void emptyAppmsg() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of()));
        assertEquals("[appmsg]", text(ctx));
        assertTrue(parts(ctx).isEmpty());
    }

    @Test
    @DisplayName("file variant uses appmsg.title as filename when file.filename missing")
    void fileFilenameFallback() throws Exception {
        Object ctx = invoke(Map.of("appmsg", Map.of(
                "title", "周报.docx",
                "file", Map.of(
                        "url", "https://example.com/x",
                        "aeskey", "k"))));
        // filename comes from title since file.filename is absent
        assertTrue(text(ctx).contains("周报.docx"),
                "marker should carry the title as filename; got: " + text(ctx));
    }
}
