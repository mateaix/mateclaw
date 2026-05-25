package vip.mate.tool.image;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the data-URL handling added to {@link ImageFileDownloader}.
 *
 * <p>Network-bound HTTP downloads are intentionally not exercised here —
 * the regression we care about is the silent failure that happened when a
 * provider returned a {@code data:image/png;base64,...} URL: callers fed
 * that into {@code HttpUtil.downloadFile}, which mangled it into something
 * like {@code file:/cwd/http:/data:image/...} and threw, so the image
 * never landed on disk and the assistant message rendered empty.
 *
 * <p>The downloader writes under {@code data/chat-uploads/<conv>/...}
 * relative to the JVM's working directory; we sweep that directory after
 * each test so the run leaves no artefacts behind.
 */
@Tag("media-gen")
class ImageFileDownloaderTest {

    private ImageFileDownloader downloader;
    private final String conv = "test-conv-" + System.nanoTime();

    @BeforeEach
    void setUp() {
        downloader = new ImageFileDownloader();
    }

    @AfterEach
    void cleanup() throws IOException {
        Path dir = Paths.get("data", "chat-uploads", conv);
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    @Test
    @DisplayName("download writes the decoded bytes when given a base64 data URL")
    void download_baseDataUrl_writesDecodedBytes() throws Exception {
        // 1x1 transparent PNG — the smallest legal payload we can verify byte-for-byte
        byte[] pngBytes = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
                0, 0, 0, 13, 'I', 'H', 'D', 'R',
                0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
                0x1F, 0x15, (byte) 0xC4, (byte) 0x89
        };
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);

        Path saved = downloader.download(dataUrl, conv, "task1", 0);

        assertTrue(Files.exists(saved), "saved file must exist");
        assertTrue(saved.getFileName().toString().endsWith(".png"));
        byte[] readBack = Files.readAllBytes(saved);
        assertArrayEquals(pngBytes, readBack, "stored bytes must match decoded payload");
    }

    @Test
    @DisplayName("download picks extension from the data-URL media type")
    void download_extensionMatchesMediaType() throws Exception {
        Path png = downloader.download(
                "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                conv, "ext-png", 0);
        assertTrue(png.getFileName().toString().endsWith(".png"));

        Path jpg = downloader.download(
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(new byte[]{4, 5, 6}),
                conv, "ext-jpg", 0);
        assertTrue(jpg.getFileName().toString().endsWith(".jpg"));

        Path webp = downloader.download(
                "data:image/webp;base64," + Base64.getEncoder().encodeToString(new byte[]{7, 8, 9}),
                conv, "ext-webp", 0);
        assertTrue(webp.getFileName().toString().endsWith(".webp"));

        // Unknown / missing media type → default to png
        Path fallback = downloader.download(
                "data:;base64," + Base64.getEncoder().encodeToString(new byte[]{0}),
                conv, "ext-fallback", 0);
        assertTrue(fallback.getFileName().toString().endsWith(".png"));
    }

    @Test
    @DisplayName("download accepts the percent-encoded body form (no ;base64)")
    void download_percentEncodedDataUrl() throws Exception {
        // The ";base64" form is the common one but RFC 2397 also allows a raw
        // (URL-encoded) body. Make sure both round-trip safely.
        String dataUrl = "data:image/png,hello%20world";
        Path saved = downloader.download(dataUrl, conv, "raw", 0);
        assertEquals("hello world", Files.readString(saved));
    }

    @Test
    @DisplayName("download rejects malformed data URLs cleanly")
    void download_malformedDataUrlIsRejected() {
        IOException ex = assertThrows(IOException.class,
                () -> downloader.download("data:image/png;base64", conv, "bad", 0));
        assertTrue(ex.getMessage().contains("Malformed data URL"),
                "expected explanatory error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("download rejects invalid base64 payloads with a wrapped IOException")
    void download_invalidBase64IsWrapped() {
        // !!! is not a legal base64 token
        IOException ex = assertThrows(IOException.class,
                () -> downloader.download("data:image/png;base64,!!!", conv, "badb64", 0));
        assertTrue(ex.getMessage().toLowerCase().contains("base64"));
    }

    @Test
    @DisplayName("download rejects null URLs without leaking NPE")
    void download_nullIsRejected() {
        IOException ex = assertThrows(IOException.class,
                () -> downloader.download(null, conv, "null", 0));
        assertTrue(ex.getMessage().contains("null"));
    }
}
