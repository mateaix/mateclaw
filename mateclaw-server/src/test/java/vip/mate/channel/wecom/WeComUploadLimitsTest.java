package vip.mate.channel.wecom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.wecom.WeComChannelAdapter.WeComUploadLimitDecision;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.channel.wecom.WeComChannelAdapter.applyWeComUploadLimits;
import static vip.mate.channel.wecom.WeComChannelAdapter.FILE_MAX_BYTES;
import static vip.mate.channel.wecom.WeComChannelAdapter.IMAGE_MAX_BYTES;
import static vip.mate.channel.wecom.WeComChannelAdapter.VIDEO_MAX_BYTES;
import static vip.mate.channel.wecom.WeComChannelAdapter.VOICE_MAX_BYTES;

/**
 * Pin the WeCom upload-limits decision matrix.
 *
 * <p>The platform server enforces these limits at the chunk-finish step
 * (after we've already uploaded all bytes). Without the client-side
 * pre-check, a 25 MB PDF would chunk-upload for ~minutes, then the
 * server rejects the finish frame, and the user sees nothing arrive.
 * These tests pin the boundary so future tweaks (e.g. WeCom raising
 * limits) are intentional.
 */
class WeComUploadLimitsTest {

    @Test
    @DisplayName("normal-sized file passes through with native media type")
    void normalFilePasses() {
        WeComUploadLimitDecision d = applyWeComUploadLimits(1_000_000, "file", null);
        assertFalse(d.rejected());
        assertFalse(d.downgraded());
        assertEquals("file", d.finalMediaType());
    }

    @Test
    @DisplayName("file at exactly 20MB still passes; over rejects")
    void fileBoundary() {
        WeComUploadLimitDecision pass = applyWeComUploadLimits(FILE_MAX_BYTES, "file", null);
        assertFalse(pass.rejected());

        WeComUploadLimitDecision fail = applyWeComUploadLimits(FILE_MAX_BYTES + 1, "file", null);
        assertTrue(fail.rejected());
        assertNotNull(fail.rejectReason());
        assertTrue(fail.rejectReason().contains("20MB"),
                "reject reason should mention 20MB; got: " + fail.rejectReason());
    }

    @Test
    @DisplayName("image over 10MB downgrades to file with friendly note")
    void oversizedImageDowngrades() {
        WeComUploadLimitDecision d = applyWeComUploadLimits(IMAGE_MAX_BYTES + 1, "image", "image/png");
        assertFalse(d.rejected());
        assertTrue(d.downgraded());
        assertEquals("file", d.finalMediaType());
        assertNotNull(d.downgradeNote());
        assertTrue(d.downgradeNote().contains("图片"));
        assertTrue(d.downgradeNote().contains("10MB"));
    }

    @Test
    @DisplayName("image at exactly 10MB still passes as image")
    void imageAtBoundary() {
        WeComUploadLimitDecision d = applyWeComUploadLimits(IMAGE_MAX_BYTES, "image", "image/jpeg");
        assertFalse(d.rejected());
        assertFalse(d.downgraded());
        assertEquals("image", d.finalMediaType());
    }

    @Test
    @DisplayName("video over 10MB downgrades to file")
    void oversizedVideoDowngrades() {
        WeComUploadLimitDecision d = applyWeComUploadLimits(VIDEO_MAX_BYTES + 1, "video", "video/mp4");
        assertEquals("file", d.finalMediaType());
        assertTrue(d.downgraded());
        assertTrue(d.downgradeNote().contains("视频"));
    }

    @Test
    @DisplayName("voice with non-AMR mime downgrades to file regardless of size")
    void voiceWrongMimeDowngrades() {
        WeComUploadLimitDecision d = applyWeComUploadLimits(500_000, "voice", "audio/mpeg");
        assertEquals("file", d.finalMediaType());
        assertTrue(d.downgraded());
        assertTrue(d.downgradeNote().contains("AMR"));
    }

    @Test
    @DisplayName("voice in AMR but over 2MB downgrades to file")
    void voiceOversizedAmrDowngrades() {
        WeComUploadLimitDecision d = applyWeComUploadLimits(VOICE_MAX_BYTES + 1, "voice", "audio/amr");
        assertEquals("file", d.finalMediaType());
        assertTrue(d.downgraded());
        assertTrue(d.downgradeNote().contains("语音"));
        assertTrue(d.downgradeNote().contains("2MB"));
    }

    @Test
    @DisplayName("voice in AMR within 2MB passes natively")
    void voiceAmrInBoundsPasses() {
        WeComUploadLimitDecision d = applyWeComUploadLimits(VOICE_MAX_BYTES, "voice", "audio/amr");
        assertFalse(d.rejected());
        assertFalse(d.downgraded());
        assertEquals("voice", d.finalMediaType());
    }

    @Test
    @DisplayName("absolute 20MB cap trumps every modality-specific downgrade")
    void absoluteCapTrumpsDowngrade() {
        // An image at 25MB is over both 10MB image limit AND 20MB absolute cap.
        // The absolute cap fires first (reject), not the downgrade path.
        WeComUploadLimitDecision d = applyWeComUploadLimits(25L * 1024 * 1024, "image", "image/png");
        assertTrue(d.rejected());
        assertFalse(d.downgraded());
    }
}
