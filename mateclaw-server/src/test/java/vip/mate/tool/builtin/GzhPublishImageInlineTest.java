package vip.mate.tool.builtin;

import me.chanjar.weixin.mp.api.WxMpMaterialService;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.material.WxMediaImgUploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.tool.document.GeneratedFileCache;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pin the fix for the biggest real-publishing gap: an article body whose images
 * point at our generated-file URLs (or any non-WeChat host) renders broken once
 * published, because WeChat only shows images it hosts. {@code gzh_publish} must
 * upload each body image and rewrite its src to the {@code mp.weixin.qq.com} URL,
 * leave already-WeChat images alone, and never let one failed image block the draft.
 */
class GzhPublishImageInlineTest {

    private GeneratedFileCache cache;
    private GzhPublishTool tool;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        cache = new GeneratedFileCache(tempDir);
        // Only the generated-file cache is exercised by inlineContentImages.
        tool = new GzhPublishTool(null, null, cache);
    }

    private WxMpService wxReturning(String mpUrl) throws Exception {
        WxMediaImgUploadResult result = mock(WxMediaImgUploadResult.class);
        when(result.getUrl()).thenReturn(mpUrl);
        WxMpMaterialService material = mock(WxMpMaterialService.class);
        when(material.mediaImgUpload(any())).thenReturn(result);
        WxMpService wx = mock(WxMpService.class);
        when(wx.getMaterialService()).thenReturn(material);
        return wx;
    }

    @Test
    @DisplayName("a generated-file body image is uploaded and its src rewritten to the WeChat URL")
    void rewritesGeneratedImage() throws Exception {
        String id = cache.put("PNGDATA".getBytes(), "pic.png", "image/png");
        String html = "<p>看图</p><img src=\"/api/v1/files/generated/" + id + "\"/>";
        WxMpService wx = wxReturning("http://mmbiz.qpic.cn/mmbiz_png/abc/0");

        GzhPublishTool.ImageInlineResult r = tool.inlineContentImages(wx, html);

        assertEquals(1, r.uploaded());
        assertTrue(r.failed().isEmpty());
        assertTrue(r.html().contains("http://mmbiz.qpic.cn/mmbiz_png/abc/0"), "src must be rewritten");
        assertFalse(r.html().contains("/api/v1/files/generated/"), "the external ref must be gone");
    }

    @Test
    @DisplayName("an image already on mp.weixin.qq.com is left untouched and not re-uploaded")
    void leavesWeChatImageAlone() throws Exception {
        String html = "<img src=\"https://mp.weixin.qq.com/existing.png\"/>";
        WxMpService wx = wxReturning("http://mmbiz.qpic.cn/should-not-be-used");

        GzhPublishTool.ImageInlineResult r = tool.inlineContentImages(wx, html);

        assertEquals(0, r.uploaded());
        assertTrue(r.failed().isEmpty());
        assertTrue(r.html().contains("mp.weixin.qq.com/existing.png"));
        verify(wx, never()).getMaterialService();
    }

    @Test
    @DisplayName("an unresolvable body image is reported as failed but does not block the rest")
    void unresolvableImageReportedNotBlocking() throws Exception {
        String good = cache.put("PNGDATA".getBytes(), "ok.png", "image/png");
        String html = "<img src=\"/api/v1/files/generated/deadbeef-0000-0000-0000-000000000000\"/>"
                + "<img src=\"/api/v1/files/generated/" + good + "\"/>";
        WxMpService wx = wxReturning("http://mmbiz.qpic.cn/mmbiz_png/ok/0");

        GzhPublishTool.ImageInlineResult r = tool.inlineContentImages(wx, html);

        assertEquals(1, r.uploaded(), "the good image still uploads");
        assertEquals(1, r.failed().size(), "the missing image is reported");
        assertTrue(r.html().contains("http://mmbiz.qpic.cn/mmbiz_png/ok/0"));
    }
}
