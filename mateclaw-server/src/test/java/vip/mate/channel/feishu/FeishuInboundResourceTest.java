package vip.mate.channel.feishu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.conversation.model.MessageContentPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pin the inbound-resource download contract for Feishu:
 *
 * <ol>
 *   <li>{@link FeishuChannelAdapter#extensionFor} maps every supported
 *       inbound content-type to a sensible on-disk extension — important
 *       because vision providers and file-reading tools often key on the
 *       extension, not the MIME header.</li>
 *   <li>{@link FeishuChannelAdapter#inferMimeFromName} round-trips the
 *       same set so the {@link vip.mate.tool.document.GeneratedFileCache}
 *       entry (which only stores MIME, no headers) renders correctly in
 *       the admin UI.</li>
 *   <li>{@link FeishuChannelAdapter#applyDownload} copies every populated
 *       field from a {@link FeishuChannelAdapter.DownloadedResource} onto
 *       the outbound {@link MessageContentPart} — and tolerates {@code
 *       null} input so the legacy "download disabled, keep the bare
 *       file_key" path still produces a valid part.</li>
 * </ol>
 *
 * <p>These are the contracts that broke production before this change:
 * when a user uploaded a PDF to the bot, the adapter only emitted a
 * {@code file_key} placeholder and the agent never saw the bytes.
 */
class FeishuInboundResourceTest {

    // ------------------------------------------------------------------
    // extensionFor — Content-Type → on-disk extension
    // ------------------------------------------------------------------

    @Test
    @DisplayName("extensionFor: image content-types map to standard image extensions")
    void extensionForImages() {
        assertEquals("jpg", FeishuChannelAdapter.extensionFor("image/jpeg", null));
        assertEquals("jpg", FeishuChannelAdapter.extensionFor("image/jpg", null));
        assertEquals("png", FeishuChannelAdapter.extensionFor("image/png", null));
        assertEquals("gif", FeishuChannelAdapter.extensionFor("image/gif", null));
        assertEquals("webp", FeishuChannelAdapter.extensionFor("image/webp", null));
    }

    @Test
    @DisplayName("extensionFor: office and document MIMEs map correctly")
    void extensionForOfficeDocs() {
        assertEquals("pdf", FeishuChannelAdapter.extensionFor("application/pdf", null));
        assertEquals("docx", FeishuChannelAdapter.extensionFor(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null));
        assertEquals("xlsx", FeishuChannelAdapter.extensionFor(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null));
        assertEquals("pptx", FeishuChannelAdapter.extensionFor(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", null));
    }

    @Test
    @DisplayName("extensionFor: audio + video MIMEs map correctly")
    void extensionForMedia() {
        assertEquals("opus", FeishuChannelAdapter.extensionFor("audio/opus", null));
        assertEquals("mp3", FeishuChannelAdapter.extensionFor("audio/mpeg", null));
        assertEquals("mp4", FeishuChannelAdapter.extensionFor("video/mp4", null));
    }

    @Test
    @DisplayName("extensionFor: unknown content-type falls back to filename hint, then 'bin'")
    void extensionForFallback() {
        // Filename hint when content-type is unhelpful
        assertEquals("zip", FeishuChannelAdapter.extensionFor("application/octet-stream", "report.zip"));
        assertEquals("csv", FeishuChannelAdapter.extensionFor(null, "data.csv"));
        // No hint and no recognisable content-type → bin sentinel
        assertEquals("bin", FeishuChannelAdapter.extensionFor(null, null));
        assertEquals("bin", FeishuChannelAdapter.extensionFor("application/x-weird", null));
        // Filename hint with no dot is not a usable extension
        assertEquals("bin", FeishuChannelAdapter.extensionFor(null, "noextension"));
    }

    // ------------------------------------------------------------------
    // sniffExtension — recover real type from magic bytes when the
    // content-type / filename gave us nothing (Feishu image downloads)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sniffExtension: image / document magic bytes map to the real extension")
    void sniffExtensionKnown() {
        assertEquals("png", FeishuChannelAdapter.sniffExtension(
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}));
        assertEquals("jpg", FeishuChannelAdapter.sniffExtension(
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        assertEquals("gif", FeishuChannelAdapter.sniffExtension(
                new byte[]{'G', 'I', 'F', '8', '9', 'a'}));
        assertEquals("pdf", FeishuChannelAdapter.sniffExtension(
                new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4'}));
        assertEquals("bmp", FeishuChannelAdapter.sniffExtension(
                new byte[]{'B', 'M', 0x00, 0x00}));
        assertEquals("webp", FeishuChannelAdapter.sniffExtension(
                new byte[]{'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'}));
    }

    @Test
    @DisplayName("sniffExtension: too-short or unrecognised bytes return null (keep 'bin')")
    void sniffExtensionUnknown() {
        assertNull(FeishuChannelAdapter.sniffExtension(null));
        assertNull(FeishuChannelAdapter.sniffExtension(new byte[]{0x01, 0x02}));
        assertNull(FeishuChannelAdapter.sniffExtension(new byte[]{0x00, 0x11, 0x22, 0x33, 0x44}));
        // RIFF container that is not WEBP (e.g. WAV) must not be mistaken for an image
        assertNull(FeishuChannelAdapter.sniffExtension(
                new byte[]{'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'W', 'A', 'V', 'E'}));
    }

    // ------------------------------------------------------------------
    // inferMimeFromName — round-trip from filename to MIME
    // ------------------------------------------------------------------

    @Test
    @DisplayName("inferMimeFromName: common extensions resolve to the right MIME")
    void inferMimeImages() {
        assertEquals("image/jpeg", FeishuChannelAdapter.inferMimeFromName("photo.jpg"));
        assertEquals("image/jpeg", FeishuChannelAdapter.inferMimeFromName("PHOTO.JPEG"));
        assertEquals("image/png", FeishuChannelAdapter.inferMimeFromName("screenshot.png"));
        assertEquals("image/gif", FeishuChannelAdapter.inferMimeFromName("anim.gif"));
        assertEquals("image/webp", FeishuChannelAdapter.inferMimeFromName("avatar.webp"));
    }

    @Test
    @DisplayName("inferMimeFromName: office docs + media")
    void inferMimeDocsAndMedia() {
        assertEquals("application/pdf", FeishuChannelAdapter.inferMimeFromName("contract.pdf"));
        assertEquals("audio/opus", FeishuChannelAdapter.inferMimeFromName("voice.opus"));
        assertEquals("audio/mpeg", FeishuChannelAdapter.inferMimeFromName("track.mp3"));
        assertEquals("video/mp4", FeishuChannelAdapter.inferMimeFromName("clip.mp4"));
        assertEquals("text/plain", FeishuChannelAdapter.inferMimeFromName("README.md"));
    }

    @Test
    @DisplayName("inferMimeFromName: null or unknown returns generic octet-stream")
    void inferMimeUnknown() {
        assertEquals("application/octet-stream", FeishuChannelAdapter.inferMimeFromName(null));
        assertEquals("application/octet-stream", FeishuChannelAdapter.inferMimeFromName("mystery.xyz"));
        assertEquals("application/octet-stream", FeishuChannelAdapter.inferMimeFromName("noext"));
    }

    // ------------------------------------------------------------------
    // applyDownload — copy DownloadedResource onto MessageContentPart
    // ------------------------------------------------------------------

    @Test
    @DisplayName("applyDownload: copies path / fileUrl / fileName / contentType")
    void applyDownloadFullCopy() {
        MessageContentPart part = MessageContentPart.file("file_abc", null, null);
        FeishuChannelAdapter.DownloadedResource dl = new FeishuChannelAdapter.DownloadedResource(
                "/tmp/mateclaw/feishu/om_x_file_abc.pdf",
                "/api/v1/files/generated/uuid-1",
                "contract.pdf",
                "application/pdf");
        FeishuChannelAdapter.applyDownload(part, dl);
        assertEquals("/tmp/mateclaw/feishu/om_x_file_abc.pdf", part.getPath());
        assertEquals("/api/v1/files/generated/uuid-1", part.getFileUrl());
        assertEquals("contract.pdf", part.getFileName());
        assertEquals("application/pdf", part.getContentType());
    }

    @Test
    @DisplayName("applyDownload: null download leaves the bare key part untouched (legacy fallback)")
    void applyDownloadNullIsNoop() {
        MessageContentPart part = MessageContentPart.file("file_abc", "original.pdf", "application/pdf");
        FeishuChannelAdapter.applyDownload(part, null);
        assertNull(part.getPath());
        assertNull(part.getFileUrl());
        // Pre-existing fields preserved — the "download disabled / failed" path
        // still produces a valid part the outbound side can handle as an opaque key.
        assertEquals("file_abc", part.getMediaId());
        assertEquals("original.pdf", part.getFileName());
        assertEquals("application/pdf", part.getContentType());
    }

    @Test
    @DisplayName("applyDownload: preserves pre-existing fileName when caller already had one")
    void applyDownloadPreservesExistingName() {
        MessageContentPart part = MessageContentPart.file("file_abc", "user-named.pdf", null);
        FeishuChannelAdapter.DownloadedResource dl = new FeishuChannelAdapter.DownloadedResource(
                "/tmp/path.pdf", "/url", "server-derived.pdf", "application/pdf");
        FeishuChannelAdapter.applyDownload(part, dl);
        // User-provided file_name wins over server-derived name so the
        // bubble matches what the sender typed.
        assertEquals("user-named.pdf", part.getFileName());
        assertEquals("/tmp/path.pdf", part.getPath());
        assertEquals("/url", part.getFileUrl());
    }

    @Test
    @DisplayName("applyDownload: tolerates null part defensively (router never sees NPE)")
    void applyDownloadNullPart() {
        // Just assert no exception. This is the contract that lets the
        // inbound parser keep going when an upstream event is malformed.
        FeishuChannelAdapter.applyDownload(null, new FeishuChannelAdapter.DownloadedResource(
                "/x", "/y", "z", "t"));
    }
}
