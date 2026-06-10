package vip.mate.channel.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboundMediaDownloaderTest {

    /** PNG header followed by filler. */
    private static byte[] pngBytes() {
        byte[] data = new byte[32];
        data[0] = (byte) 0x89;
        data[1] = 0x50;
        data[2] = 0x4E;
        data[3] = 0x47;
        return data;
    }

    @Test
    @DisplayName("Null hint + PNG bytes → synthesized name and accurate MIME")
    void synthesizesNameFromSniff(@TempDir Path dir) {
        Optional<InboundMediaDownloader.DownloadedMedia> result = InboundMediaDownloader.download(
                InboundMediaDownloaderTest::pngBytes, null, dir, "weixin", "seed-1");

        assertTrue(result.isPresent());
        InboundMediaDownloader.DownloadedMedia m = result.get();
        assertEquals("image/png", m.contentType());
        assertTrue(m.isImage());
        assertTrue(m.fileName().endsWith(".png"), "synthesized name should carry sniffed ext");
        assertTrue(m.storedName().startsWith("weixin_"), "stored name should carry channel prefix");
        assertTrue(m.localPath().toFile().exists());
        assertEquals(32, m.fileSize());
    }

    @Test
    @DisplayName("Wrong .jpg extension on PNG bytes is NOT corrected (real names respected)")
    void keepsAuthoritativeName(@TempDir Path dir) {
        // A meaningful, user-supplied extension is kept verbatim — only the
        // MIME comes from sniffing. This is the documented contract: pass null
        // for placeholders, a real name only when it is real.
        Optional<InboundMediaDownloader.DownloadedMedia> result = InboundMediaDownloader.download(
                InboundMediaDownloaderTest::pngBytes, "report.jpg", dir, "weixin", "seed-2");

        assertTrue(result.isPresent());
        InboundMediaDownloader.DownloadedMedia m = result.get();
        assertEquals("report.jpg", m.fileName());
        assertEquals("image/png", m.contentType(), "MIME still comes from magic bytes");
    }

    @Test
    @DisplayName(".bin hint is treated as generic and replaced with sniffed ext")
    void binHintIsGeneric(@TempDir Path dir) {
        Optional<InboundMediaDownloader.DownloadedMedia> result = InboundMediaDownloader.download(
                InboundMediaDownloaderTest::pngBytes, "file.bin", dir, "wecom", "seed-3");

        assertTrue(result.isPresent());
        assertTrue(result.get().fileName().endsWith(".png"));
    }

    @Test
    @DisplayName("Transient failures are retried, then succeed")
    void retriesThenSucceeds(@TempDir Path dir) {
        AtomicInteger calls = new AtomicInteger();
        InboundMediaDownloader.ByteSource flaky = () -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("transient");
            }
            return pngBytes();
        };

        Optional<InboundMediaDownloader.DownloadedMedia> result = InboundMediaDownloader.download(
                flaky, null, dir, "weixin", "seed-4", 3);

        assertTrue(result.isPresent());
        assertEquals(3, calls.get(), "should have retried up to the 3rd attempt");
    }

    @Test
    @DisplayName("fileUrlBuilder maps storedName to a servable URL")
    void buildsFileUrl(@TempDir Path dir) {
        Optional<InboundMediaDownloader.DownloadedMedia> result = InboundMediaDownloader.download(
                InboundMediaDownloaderTest::pngBytes, null, dir, "weixin", "seed-url",
                storedName -> "/api/v1/chat/files/weixin:bob/" + storedName);

        assertTrue(result.isPresent());
        InboundMediaDownloader.DownloadedMedia m = result.get();
        assertEquals("/api/v1/chat/files/weixin:bob/" + m.storedName(), m.fileUrl());
    }

    @Test
    @DisplayName("A throwing fileUrlBuilder degrades to null fileUrl, file still saved")
    void fileUrlBuilderFailureDegrades(@TempDir Path dir) {
        Optional<InboundMediaDownloader.DownloadedMedia> result = InboundMediaDownloader.download(
                InboundMediaDownloaderTest::pngBytes, null, dir, "weixin", "seed-url-fail",
                storedName -> {
                    throw new RuntimeException("url build boom");
                });

        assertTrue(result.isPresent(), "a broken URL builder must not fail the download");
        InboundMediaDownloader.DownloadedMedia m = result.get();
        assertNull(m.fileUrl());
        assertTrue(m.localPath().toFile().exists(), "file should still be persisted");
    }

    @Test
    @DisplayName("No builder → null fileUrl")
    void noBuilderLeavesNullUrl(@TempDir Path dir) {
        Optional<InboundMediaDownloader.DownloadedMedia> result = InboundMediaDownloader.download(
                InboundMediaDownloaderTest::pngBytes, null, dir, "weixin", "seed-no-url");

        assertTrue(result.isPresent());
        assertNull(result.get().fileUrl());
    }

    @Test
    @DisplayName("Exhausted retries return empty, no file written")
    void exhaustedReturnsEmpty(@TempDir Path dir) {
        InboundMediaDownloader.ByteSource always = () -> {
            throw new RuntimeException("down");
        };
        Optional<InboundMediaDownloader.DownloadedMedia> result = InboundMediaDownloader.download(
                always, null, dir, "weixin", "seed-5", 2);

        assertTrue(result.isEmpty());
        assertEquals(0, dir.toFile().listFiles().length, "no partial file should remain");
    }
}
