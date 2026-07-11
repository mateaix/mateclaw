package vip.mate.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.tool.document.GeneratedFileCache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin {@link XhsPackageTool}: 小红书 is image-first, so packaging must (a) refuse
 * a note with fewer than 3 resolvable images, (b) render an image-first preview
 * (the images come before the copy), and (c) self-heal an image referenced by
 * filename instead of its issued id.
 */
class XhsPackageTest {

    private GeneratedFileCache cache;
    private XhsPackageTool tool;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        cache = new GeneratedFileCache(tempDir);
        tool = new XhsPackageTool(cache);
    }

    private String putImg(String name) {
        String id = cache.put("PNGDATA".getBytes(), name, "image/png");
        return "/api/v1/files/generated/" + id;
    }

    /** Read back the online-preview HTML that the tool stored, given its result text. */
    private String previewHtml(String out) {
        Matcher m = GeneratedFileCache.GENERATED_URL_PATTERN.matcher(out);
        assertTrue(m.find(), "result should contain a preview URL");
        return new String(cache.get(m.group(1)).orElseThrow().bytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("fewer than 3 images → refused, no preview minted")
    void refusesUnderThreeImages() {
        String imgs = putImg("cover.png") + "," + putImg("c1.png");
        String out = tool.xhs_package("夏日穿搭", "正文", "穿搭,夏天", imgs, null);
        assertTrue(out.contains("至少需要 3 张"), "should demand >=3 images; got:\n" + out);
        assertFalse(out.contains("在线预览"), "must not produce a preview when refused");
    }

    @Test
    @DisplayName("3 images → packaged; preview is image-first (images before the copy)")
    void packagesThreeImagesImageFirst() {
        String imgs = putImg("cover.png") + "," + putImg("c1.png") + "," + putImg("c2.png");
        String out = tool.xhs_package("3天2夜厦门citywalk", "第一天去了鼓浪屿\n人不多", "厦门,citywalk,旅行", imgs, null);

        assertTrue(out.contains("在线预览"), "should return a preview link");
        assertTrue(out.contains("素材下载"), "should return a material zip");
        assertTrue(out.contains("3 张图"), "should report the image count");

        String html = previewHtml(out);
        int imgCount = html.split("<img", -1).length - 1;
        assertEquals(3, imgCount, "all 3 images must be embedded");
        assertTrue(html.indexOf("<img") < html.indexOf("<h1"),
                "image-first: images must come before the title");
        assertTrue(html.contains("3天2夜厦门citywalk"), "title present");
        assertTrue(html.contains("#厦门"), "tags rendered as chips");
    }

    @Test
    @DisplayName("an image referenced by filename self-heals and still counts")
    void selfHealsNameBasedReference() {
        cache.put("PNGDATA".getBytes(), "cover_xhs.png", "image/png"); // stored under a uuid
        // First ref uses the filename (id pattern can't parse it); two more are valid.
        String imgs = "/api/v1/files/generated/cover_xhs.png,"
                + putImg("c1.png") + "," + putImg("c2.png");
        String out = tool.xhs_package("标题", "正文", "标签", imgs, null);

        assertTrue(out.contains("在线预览"), "name-based ref should self-heal to reach >=3; got:\n" + out);
        assertTrue(out.contains("3 张图"), "healed image should be counted");
    }
}
