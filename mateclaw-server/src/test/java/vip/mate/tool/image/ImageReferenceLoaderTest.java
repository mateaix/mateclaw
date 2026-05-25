package vip.mate.tool.image;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.conversation.ConversationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies the five accepted reference forms in {@link ImageReferenceLoader}:
 * local path, {@code file://}, {@code data:} URL, {@code http(s)://} (with the
 * SSRF guard), and {@code msg:<id>:<idx>} for an attachment from an earlier
 * conversation message. The conversation form is exercised in a separate test
 * with a real ConversationService stub; the others need no collaborators.
 */
@Tag("media-gen")
class ImageReferenceLoaderTest {

    private ImageReferenceLoader loader;
    private Path tmpDir;

    @BeforeEach
    void setUp() throws IOException {
        loader = new ImageReferenceLoader(mock(ConversationService.class));
        tmpDir = Files.createTempDirectory("img-ref-loader-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var stream = Files.walk(tmpDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                });
            }
        }
    }

    // ==================== form: local path ====================

    @Test
    @DisplayName("local absolute path: reads bytes and infers mime from extension")
    void localPath_absolute_loadsBytes() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        Path file = tmpDir.resolve("kitten.jpg");
        Files.write(file, bytes);

        ImageReference ref = loader.load(file.toAbsolutePath().toString(), "conv-x");

        assertArrayEquals(bytes, ref.data());
        assertEquals("image/jpeg", ref.mimeType());
        assertEquals("kitten.jpg", ref.fileName());
        assertTrue(ref.origin().startsWith("path:"));
    }

    @Test
    @DisplayName("file:// URL: prefix is stripped before resolving the path")
    void fileUrl_resolvesAsLocal() throws Exception {
        Path file = tmpDir.resolve("note.png");
        Files.write(file, new byte[]{9});

        ImageReference ref = loader.load("file://" + file.toAbsolutePath(), "conv-x");

        assertEquals("image/png", ref.mimeType());
        assertEquals(1, ref.data().length);
    }

    @Test
    @DisplayName("missing local file fails clearly without leaking the entire path elsewhere")
    void localPath_missing_throws() {
        IOException err = assertThrows(IOException.class,
                () -> loader.load("/tmp/definitely-not-here-" + System.nanoTime() + ".png", "conv-x"));
        assertTrue(err.getMessage().contains("not found"), err.getMessage());
    }

    // ==================== form: data: URL ====================

    @Test
    @DisplayName("data: URL with base64 body: decodes bytes and keeps declared mime")
    void dataUrl_base64_decodes() throws Exception {
        // "hi" in base64
        String dataUrl = "data:image/png;base64,aGk=";
        ImageReference ref = loader.load(dataUrl, "conv-x");
        assertArrayEquals(new byte[]{'h', 'i'}, ref.data());
        assertEquals("image/png", ref.mimeType());
        assertEquals("data-url", ref.origin());
    }

    @Test
    @DisplayName("data: URL with URL-encoded body: also decodes")
    void dataUrl_urlEncoded_decodes() throws Exception {
        String dataUrl = "data:image/svg+xml,%3Csvg%2F%3E";
        ImageReference ref = loader.load(dataUrl, "conv-x");
        assertEquals("image/svg+xml", ref.mimeType());
        assertTrue(new String(ref.data()).contains("<svg/>"));
    }

    @Test
    @DisplayName("malformed data: URL (missing comma) fails")
    void dataUrl_malformed_throws() {
        assertThrows(IOException.class, () -> loader.load("data:image/png;base64", "conv-x"));
    }

    // ==================== form: http(s):// SSRF guard ====================

    @Test
    @DisplayName("SSRF guard rejects localhost / 127.0.0.1 / private subnets without making any HTTP call")
    void httpUrl_ssrfGuard_rejectsInternalHosts() {
        for (String url : new String[]{
                "http://localhost/foo.png",
                "http://127.0.0.1/foo.png",
                "http://10.1.2.3/foo.png",
                "http://192.168.1.1/foo.png",
                "http://169.254.169.254/foo.png" // AWS instance metadata
        }) {
            IOException err = assertThrows(IOException.class, () -> loader.load(url, "conv-x"),
                    "expected SSRF guard to reject " + url);
            assertTrue(err.getMessage().toLowerCase().contains("internal"), url);
        }
    }

    // ==================== form: msg:<id>:<idx> parse errors ====================

    @Test
    @DisplayName("msg: ref with non-numeric message id fails fast")
    void msgRef_invalidMessageId_throws() {
        IOException err = assertThrows(IOException.class, () -> loader.load("msg:abc:0", "conv-x"));
        assertTrue(err.getMessage().toLowerCase().contains("invalid"), err.getMessage());
    }

    @Test
    @DisplayName("msg: ref without an active conversation id fails fast")
    void msgRef_noConversation_throws() {
        IOException err = assertThrows(IOException.class, () -> loader.load("msg:123:0", null));
        assertTrue(err.getMessage().toLowerCase().contains("conversation"), err.getMessage());
    }

    @Test
    @DisplayName("msg: ref with bad part index format fails fast")
    void msgRef_invalidPartIndex_throws() {
        IOException err = assertThrows(IOException.class, () -> loader.load("msg:123:nope", "conv-x"));
        assertTrue(err.getMessage().toLowerCase().contains("invalid"), err.getMessage());
    }

    // ==================== loadAll ====================

    @Test
    @DisplayName("loadAll: skips null/blank entries, preserves order otherwise")
    void loadAll_skipsBlanksAndPreservesOrder() throws Exception {
        Path a = tmpDir.resolve("a.png");
        Path b = tmpDir.resolve("b.png");
        Files.write(a, new byte[]{1});
        Files.write(b, new byte[]{2});

        var refs = loader.loadAll(java.util.Arrays.asList(
                a.toAbsolutePath().toString(),
                null,
                "",
                b.toAbsolutePath().toString()
        ), "conv-x");

        assertEquals(2, refs.size());
        assertArrayEquals(new byte[]{1}, refs.get(0).data());
        assertArrayEquals(new byte[]{2}, refs.get(1).data());
    }

    @Test
    @DisplayName("loadAll: null / empty input returns an empty list (no NPE)")
    void loadAll_nullOrEmpty_returnsEmpty() throws Exception {
        assertTrue(loader.loadAll(null, "conv-x").isEmpty());
        assertTrue(loader.loadAll(java.util.List.of(), "conv-x").isEmpty());
    }
}
