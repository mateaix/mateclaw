package vip.mate.channel.feishu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.media.MediaSizeDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the Feishu upload-decision matrix.
 *
 * <p>Feishu's server rejects oversize payloads at the end of the upload
 * (after we've serialised all bytes through {@code oapi-sdk}). Without
 * this client-side gate, a 40 MB video would round-trip for nothing
 * and the user would see the bubble fail with a cryptic SDK error.
 * These tests pin the boundary so any future tweak (Feishu raising
 * limits, the SDK adding new file_types) is intentional.
 */
class FeishuSizePolicyTest {

    private final FeishuSizePolicy policy = new FeishuSizePolicy();

    @Test
    @DisplayName("normal-sized file passes through with native media type")
    void normalFilePasses() {
        MediaSizeDecision d = policy.evaluate(1_000_000, "file", null);
        assertFalse(d.rejected());
        assertFalse(d.downgraded());
        assertEquals("file", d.finalMediaType());
        assertNull(d.downgradeNote());
    }

    @Test
    @DisplayName("file at exactly 30MB passes; one byte over rejects with a message")
    void fileBoundary() {
        MediaSizeDecision pass = policy.evaluate(FeishuSizePolicy.FILE_MAX_BYTES, "file", null);
        assertFalse(pass.rejected());

        MediaSizeDecision fail = policy.evaluate(FeishuSizePolicy.FILE_MAX_BYTES + 1, "file", null);
        assertTrue(fail.rejected());
        assertNotNull(fail.rejectReason());
        assertTrue(fail.rejectReason().contains("30MB"),
                "reject reason should mention 30MB; got: " + fail.rejectReason());
    }

    @Test
    @DisplayName("image at exactly 10MB passes as image; one byte over downgrades to file")
    void imageBoundary() {
        MediaSizeDecision pass = policy.evaluate(FeishuSizePolicy.IMAGE_MAX_BYTES, "image", "image/png");
        assertFalse(pass.rejected());
        assertFalse(pass.downgraded());
        assertEquals("image", pass.finalMediaType());

        MediaSizeDecision down = policy.evaluate(FeishuSizePolicy.IMAGE_MAX_BYTES + 1, "image", "image/png");
        assertFalse(down.rejected());
        assertTrue(down.downgraded());
        assertEquals("file", down.finalMediaType());
        assertNotNull(down.downgradeNote());
        assertTrue(down.downgradeNote().contains("10MB"),
                "downgrade note should mention 10MB; got: " + down.downgradeNote());
    }

    @Test
    @DisplayName("image over the 30MB file ceiling rejects (not downgrades)")
    void imageBeyondFileCeilingRejects() {
        MediaSizeDecision d = policy.evaluate(40L * 1024 * 1024, "image", "image/png");
        assertTrue(d.rejected());
        assertNotNull(d.rejectReason());
    }

    @Test
    @DisplayName("audio/opus stays as native voice; audio/mp3 downgrades to file")
    void audioMimeRouting() {
        MediaSizeDecision opus = policy.evaluate(500_000, "audio", "audio/opus");
        assertFalse(opus.rejected());
        assertFalse(opus.downgraded());
        assertEquals("audio", opus.finalMediaType());

        MediaSizeDecision mp3 = policy.evaluate(500_000, "audio", "audio/mp3");
        assertFalse(mp3.rejected());
        assertTrue(mp3.downgraded());
        assertEquals("file", mp3.finalMediaType());
        assertTrue(mp3.downgradeNote().contains("opus"),
                "downgrade note should explain opus-only; got: " + mp3.downgradeNote());
    }

    @Test
    @DisplayName("audio without contentType defaults to native voice (caller knows it's opus)")
    void audioMissingMime() {
        MediaSizeDecision d = policy.evaluate(500_000, "audio", null);
        assertFalse(d.rejected());
        assertFalse(d.downgraded());
        assertEquals("audio", d.finalMediaType());
    }

    @Test
    @DisplayName("video/mp4 stays as native video; video/webm downgrades to file")
    void videoMimeRouting() {
        MediaSizeDecision mp4 = policy.evaluate(2_000_000, "video", "video/mp4");
        assertFalse(mp4.downgraded());
        assertEquals("video", mp4.finalMediaType());

        MediaSizeDecision webm = policy.evaluate(2_000_000, "video", "video/webm");
        assertTrue(webm.downgraded());
        assertEquals("file", webm.finalMediaType());
        assertTrue(webm.downgradeNote().contains("mp4"));
    }

    @Test
    @DisplayName("null mediaType is treated as file (defensive default)")
    void nullMediaType() {
        MediaSizeDecision d = policy.evaluate(1000, null, null);
        assertFalse(d.rejected());
        assertEquals("file", d.finalMediaType());
    }

    @Test
    @DisplayName("channelType identifies this policy as feishu")
    void channelTypeIsFeishu() {
        assertEquals("feishu", policy.channelType());
    }
}
