package vip.mate.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.content.service.ContentItemService;
import vip.mate.tool.document.GeneratedFileCache;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reproduce and pin the fix for the broken-cover bug: when the model references
 * the cover by its logical filename ({@code cover_xyz.png}) instead of the
 * issued id, the URL id-pattern can't parse it, so the old tool embedded the raw
 * (non-serving) reference and the preview showed a broken image. The packager
 * must now (a) self-heal a name-based reference to the real generated image, and
 * (b) when a cover genuinely can't be resolved, drop it and warn rather than ship
 * a broken {@code <img>}.
 */
class GzhPackageCoverHealingTest {

    private GeneratedFileCache cache;
    private ContentItemService contentSvc;
    private GzhPackageTool tool;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        cache = new GeneratedFileCache(tempDir);
        contentSvc = mock(ContentItemService.class);
        tool = new GzhPackageTool(cache, contentSvc);
    }

    private static final String BODY = "## 小节一\n\n正文一段。\n\n## 小节二\n\n又一段。";

    @Test
    @DisplayName("name-based cover reference self-heals to the real generated image")
    void nameBasedReferenceHeals() {
        String id = cache.put("PNGBYTES".getBytes(), "cover_cat_7pits.png", "image/png");
        // The model echoed the cover by filename — the id pattern stops at the '_'.
        String out = tool.gzh_package(
                "养猫第一年烧掉3万块",
                BODY,
                "/api/v1/files/generated/cover_cat_7pits.png",
                "内容工作室",
                null,
                null);

        assertTrue(out.contains("/api/v1/files/generated/" + id),
                "cover should be healed to the real generated id; got:\n" + out);
        assertFalse(out.contains("generated/cover_cat_7pits.png"),
                "the broken name-based reference must not be embedded");
        assertTrue(out.contains("<img "), "a cover image must be present");
        assertFalse(out.contains("⚠️"), "a healed cover must not warn");
    }

    @Test
    @DisplayName("unresolvable cover → placeholder cover + warning, never a broken <img>")
    void unresolvableCoverUsesPlaceholder() {
        String out = tool.gzh_package(
                "标题",
                BODY,
                "/api/v1/files/generated/deadbeef-0000-0000-0000-000000000000",
                "内容工作室",
                null,
                null);

        assertTrue(out.contains("⚠️"), "an unresolved cover must be flagged; got:\n" + out);
        assertTrue(out.contains("占位封面"), "a placeholder cover must be substituted");
        assertTrue(out.contains("<img "), "the placeholder cover is embedded (never broken/absent)");
        assertFalse(out.contains("generated/deadbeef"), "the broken ref must not be embedded");
    }

    @Test
    @DisplayName("a correct generated-id image reference is embedded as-is, no warning")
    void correctIdReferenceEmbedded() {
        String id = cache.put("PNGBYTES".getBytes(), "gzh-cover.png", "image/png");
        String out = tool.gzh_package(
                "标题",
                BODY,
                "/api/v1/files/generated/" + id,
                "内容工作室",
                null,
                null);

        assertTrue(out.contains("/api/v1/files/generated/" + id), "the cover id must be embedded");
        assertTrue(out.contains("<img "), "a cover image must be present");
        assertFalse(out.contains("⚠️"), "a resolved cover must not warn");
    }

    @Test
    @DisplayName("delivery auto-scans compliance and auto-records to the ledger")
    void autoScanAndRecordOnDelivery() {
        String id = cache.put("PNGBYTES".getBytes(), "cover.png", "image/png");
        String out = tool.gzh_package(
                "夏日凉拌菜",
                "## 小节\n本店全国第一、集赞20个送好礼。",  // 极限词 + 诱导 → 高危命中
                "/api/v1/files/generated/" + id,
                "内容工作室",
                "夏天凉拌菜",  // topic distinct from title
                null);

        // Compliance report is baked into the delivery output (not left to the model).
        assertTrue(out.contains("合规扫描命中"), "auto-scan report must be in the output:\n" + out);
        assertTrue(out.contains("广告法极限词") || out.contains("微信诱导"), "should flag the violation");
        // Auto-record uses the topic (not the title) for the ledger fingerprint.
        assertTrue(out.contains("已记入内容日历"), "auto-record note must appear");
        verify(contentSvc, times(1)).record(any(), eq("gzh"), eq("夏天凉拌菜"),
                eq("夏日凉拌菜"), eq("packaged"), any(), isNull());
    }

    @Test
    @DisplayName("a non-image generated file referenced as cover → placeholder, not the non-image")
    void nonImageReferenceFallsBackToPlaceholder() {
        String id = cache.put("%PDF".getBytes(), "handout.pdf", "application/pdf");
        String out = tool.gzh_package(
                "标题",
                BODY,
                "/api/v1/files/generated/" + id,
                "内容工作室",
                null,
                null);

        assertTrue(out.contains("⚠️"), "a non-image cover must be flagged");
        assertTrue(out.contains("占位封面"), "a placeholder cover must be substituted");
        assertFalse(out.contains("generated/" + id), "the non-image ref must not be embedded as the cover");
    }
}
