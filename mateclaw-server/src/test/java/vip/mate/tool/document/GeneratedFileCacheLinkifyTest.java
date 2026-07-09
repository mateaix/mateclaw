package vip.mate.tool.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin {@link GeneratedFileCache#linkifyBareReferences}: bare generated-file
 * URLs echoed by the model as plain text must be wrapped into
 * {@code [filename](url)} markdown links so chat surfaces show the file name
 * instead of the raw id, while URLs already inside a markdown link stay
 * untouched.
 */
class GeneratedFileCacheLinkifyTest {

    private GeneratedFileCache cache;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        cache = new GeneratedFileCache(tempDir);
    }

    private String putFile(String filename) {
        return cache.put("dummy".getBytes(), filename, "application/octet-stream");
    }

    @Test
    @DisplayName("bare relative URL with a live id → wrapped into [filename](url)")
    void bareRelativeUrlWrapped() {
        String id = putFile("智能体技术培训_红色版.pptx");
        String url = "/api/v1/files/generated/" + id;
        String out = cache.linkifyBareReferences("下载链接：" + url + "（10 分钟内有效）");
        assertEquals("下载链接：[智能体技术培训_红色版.pptx](" + url + ")（10 分钟内有效）", out);
    }

    @Test
    @DisplayName("bare absolute URL keeps its host inside the link destination")
    void bareAbsoluteUrlWrapped() {
        String id = putFile("report.docx");
        String url = "http://localhost:55793/api/v1/files/generated/" + id;
        String out = cache.linkifyBareReferences("下载：" + url);
        assertEquals("下载：[report.docx](" + url + ")", out);
    }

    @Test
    @DisplayName("URL already used as a markdown link destination is left untouched")
    void markdownLinkLeftUntouched() {
        String id = putFile("slides.pptx");
        String text = "演示文稿已生成：[自定义标题](/api/v1/files/generated/" + id + ")";
        assertEquals(text, cache.linkifyBareReferences(text));
    }

    @Test
    @DisplayName("angle-bracket autolink is left untouched")
    void angleAutolinkLeftUntouched() {
        String id = putFile("a.xlsx");
        String text = "见 </api/v1/files/generated/" + id + "> 处";
        assertEquals(text, cache.linkifyBareReferences(text));
    }

    @Test
    @DisplayName("unknown id is left for the missing-reference scrubber")
    void unknownIdLeftUntouched() {
        String text = "文件：/api/v1/files/generated/a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        assertEquals(text, cache.linkifyBareReferences(text));
    }

    @Test
    @DisplayName("square brackets in the stored filename are stripped from the link text")
    void bracketsInFilenameStripped() {
        String id = putFile("[草稿]方案.docx");
        String out = cache.linkifyBareReferences("/api/v1/files/generated/" + id);
        assertTrue(out.startsWith("[草稿方案.docx]("), "brackets must be stripped; got: " + out);
    }

    @Test
    @DisplayName("mixed text: markdown link kept, bare duplicate of the same URL wrapped")
    void mixedMarkdownAndBare() {
        String id = putFile("数据.csv");
        String url = "/api/v1/files/generated/" + id;
        String out = cache.linkifyBareReferences("[数据.csv](" + url + ") 备用地址 " + url);
        assertEquals("[数据.csv](" + url + ") 备用地址 [数据.csv](" + url + ")", out);
    }

    @Test
    @DisplayName("null / empty / no-URL text passes through")
    void passThrough() {
        assertNull(cache.linkifyBareReferences(null));
        assertEquals("", cache.linkifyBareReferences(""));
        String plain = "没有链接的普通回答";
        assertSame(plain, cache.linkifyBareReferences(plain));
    }
}
