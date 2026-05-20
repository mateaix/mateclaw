package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.media.GeneratedFileScrubber;
import vip.mate.channel.media.MediaSource;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.tool.document.GeneratedFileCache;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pin the scrub-on-finish contract for outbound Feishu replies.
 *
 * <p>The agent often replies with a markdown link like
 * <code>[report.pdf](/api/v1/files/generated/abc-123)</code> after
 * generating a file. The IM client can't open the
 * tenant-token-protected backend endpoint that link points at — so
 * without intervention the user gets a dead link instead of the file.
 *
 * <p>The fix is the {@code scrubAndSendAttachments} hop, called by
 * both {@code processStream} (CardKit streaming path) and
 * {@code processStreamAsText} (fallback). This test pins that hop:
 *
 * <ol>
 *   <li>The URL is replaced with a {@code "📎 filename"} marker so
 *       the card text reads cleanly.</li>
 *   <li>An upload is enqueued for the cache-resolved bytes, with the
 *       right mediaType / fileName / mimeType from the cache entry.</li>
 *   <li>Cache misses degrade to a user-facing retry hint instead of
 *       leaving the dead link in the bubble.</li>
 *   <li>Replies with no generated-file URL pass through unchanged and
 *       trigger zero uploads.</li>
 * </ol>
 */
class FeishuScrubOnFinishTest {

    private GeneratedFileCache cache;
    private GeneratedFileScrubber scrubber;
    private RecordingAdapter adapter;

    @BeforeEach
    void setUp() {
        cache = new GeneratedFileCache();
        scrubber = new GeneratedFileScrubber(cache);
        adapter = new RecordingAdapter(scrubber);
    }

    @Test
    @DisplayName("agent reply with cached PDF URL → text rewritten + upload enqueued")
    void cacheHitTriggersUploadAndRewrite() {
        byte[] bytes = "%PDF-1.4 fake pdf body".getBytes();
        String id = cache.put(bytes, "report.pdf", "application/pdf");
        String agentReply = "✅ PDF 已生成\n[report.pdf](/api/v1/files/generated/" + id + ")\n"
                + "点击上方链接即可下载，链接 10 分钟内有效";

        String rendered = adapter.scrubAndSendAttachments("ou_user_123", agentReply);

        // Text bubble shows the marker, not the dead link.
        assertTrue(rendered.contains("📎 report.pdf"),
                "rewrittenText should embed the filename marker: " + rendered);
        assertFalse(rendered.contains("/api/v1/files/generated/" + id),
                "rewrittenText should NOT still carry the generated URL: " + rendered);
        // The "(/api/...)" part of the markdown link disappears with the
        // URL, leaving the bracketed display name + the marker. Both are
        // user-friendly text — no live link rot.

        // Exactly one upload enqueued, with the right metadata.
        assertEquals(1, adapter.uploads.size(), "expected exactly one attachment send");
        RecordedUpload upload = adapter.uploads.get(0);
        assertEquals("ou_user_123", upload.targetId);
        assertEquals("report.pdf", upload.fileName);
        assertEquals("file", upload.mediaType, "PDF should classify as 'file', not 'image'");
        assertEquals("application/pdf", upload.contentType);
        assertTrue(upload.source instanceof MediaSource.Bytes,
                "upload should be a Bytes source so the uploader skips a remote fetch");
        assertEquals(bytes.length,
                ((MediaSource.Bytes) upload.source).data().length);
    }

    @Test
    @DisplayName("PNG cache hit classifies as 'image' so vision-aware bubble renders inline")
    void imageMimeTriggersImageUpload() {
        byte[] bytes = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        String id = cache.put(bytes, "chart.png", "image/png");
        String agentReply = "这是图表: /api/v1/files/generated/" + id;

        String rendered = adapter.scrubAndSendAttachments("oc_chat_x", agentReply);

        assertTrue(rendered.contains("📎 chart.png"));
        assertEquals(1, adapter.uploads.size());
        assertEquals("image", adapter.uploads.get(0).mediaType,
                "image/png MIME must route to the image endpoint, not file");
        assertEquals("image/png", adapter.uploads.get(0).contentType);
    }

    @Test
    @DisplayName("cache miss → retry hint in bubble, no upload attempted")
    void cacheMissDoesNotUpload() {
        String agentReply = "你的文件: [doc.pdf](/api/v1/files/generated/never-existed-uuid)";

        String rendered = adapter.scrubAndSendAttachments("ou_user_x", agentReply);

        assertFalse(rendered.contains("/api/v1/files/generated/never-existed-uuid"),
                "dead URL should be replaced with the missing-reference notice");
        assertTrue(rendered.contains(GeneratedFileCache.MISSING_REFERENCE_NOTICE),
                "user-visible retry hint expected: " + rendered);
        assertTrue(adapter.uploads.isEmpty(),
                "cache miss must NOT enqueue an upload — bytes don't exist");
    }

    @Test
    @DisplayName("plain reply with no generated URL is forwarded unchanged, zero uploads")
    void plainTextIsForwarded() {
        String agentReply = "Sure, here's a quick summary: ...";

        String rendered = adapter.scrubAndSendAttachments("ou_user_x", agentReply);

        assertEquals(agentReply, rendered, "no scrubbable URL → exact pass-through");
        assertTrue(adapter.uploads.isEmpty(),
                "non-generated reply must not trigger any upload work");
    }

    @Test
    @DisplayName("multiple generated URLs in one reply enqueue one upload per cache hit")
    void multipleUrlsEnqueueMultipleUploads() {
        String aId = cache.put("AAAA".getBytes(), "a.pdf", "application/pdf");
        String bId = cache.put("BBBB".getBytes(), "b.png", "image/png");
        String agentReply = "两个产物：\n- /api/v1/files/generated/" + aId
                + "\n- /api/v1/files/generated/" + bId;

        adapter.scrubAndSendAttachments("ou_user_x", agentReply);

        assertEquals(2, adapter.uploads.size(),
                "one upload per generated URL — got: "
                        + adapter.uploads.stream().map(u -> u.fileName).toList());
        assertEquals("a.pdf", adapter.uploads.get(0).fileName);
        assertEquals("file", adapter.uploads.get(0).mediaType);
        assertEquals("b.png", adapter.uploads.get(1).fileName);
        assertEquals("image", adapter.uploads.get(1).mediaType);
    }

    @Test
    @DisplayName("scrubber missing → no-op pass-through, no NPE (legacy 3-arg ctor case)")
    void scrubberAbsentIsNoop() {
        // Build adapter without the scrubber — simulates the 3-arg ctor
        // path / unit tests that don't wire the media beans.
        RecordingAdapter legacyAdapter = new RecordingAdapter(null);
        String text = "agent reply with /api/v1/files/generated/abc";
        String rendered = legacyAdapter.scrubAndSendAttachments("ou_x", text);
        assertEquals(text, rendered);
        assertTrue(legacyAdapter.uploads.isEmpty());
    }

    // ------------------------------------------------------------------
    // Test fixture
    // ------------------------------------------------------------------

    /** Captured upload arguments — what the test asserts on. */
    private record RecordedUpload(String targetId, MediaSource source, String fileName,
                                  String mediaType, String contentType) {}

    /**
     * Subclass that records every {@link FeishuChannelAdapter#uploadAndSendAttachment}
     * call instead of actually doing the HTTP send. Constructor wires a
     * synthetic {@link ChannelEntity} with id=1 so the channelId check
     * inside {@code scrubAndSendAttachments} passes; mediaUploader is
     * mocked because the recording adapter overrides the call path that
     * would use it anyway.
     */
    private static class RecordingAdapter extends FeishuChannelAdapter {
        final List<RecordedUpload> uploads = new ArrayList<>();

        RecordingAdapter(GeneratedFileScrubber scrubber) {
            super(testEntity(),
                    mock(ChannelMessageRouter.class),
                    new ObjectMapper(),
                    scrubber == null ? null : mock(FeishuMediaUploader.class),
                    scrubber);
        }

        @Override
        void uploadAndSendAttachment(String targetId, Long channelId,
                                     MediaSource source, String fileName,
                                     String mediaType, String contentType,
                                     Integer durationMillis) {
            uploads.add(new RecordedUpload(targetId, source, fileName, mediaType, contentType));
        }

        private static ChannelEntity testEntity() {
            ChannelEntity e = new ChannelEntity();
            e.setId(1L);
            e.setChannelType("feishu");
            e.setConfigJson("{\"app_id\":\"x\",\"app_secret\":\"y\"}");
            return e;
        }
    }
}
