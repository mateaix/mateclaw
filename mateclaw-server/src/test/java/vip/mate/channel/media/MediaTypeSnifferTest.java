package vip.mate.channel.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Magic-byte detection tests. The cases that matter most for IM image
 * reception are PNG / WEBP / HEIC: phone photos and screenshots are routinely
 * not JPEG, and a wrong Content-Type makes multimodal gateways reject them.
 */
class MediaTypeSnifferTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** Build an ISO-BMFF header: [size][ftyp][brand]. */
    private static byte[] ftyp(String brand) {
        byte[] brandBytes = brand.getBytes(StandardCharsets.US_ASCII);
        byte[] head = new byte[12];
        head[0] = 0x00;
        head[1] = 0x00;
        head[2] = 0x00;
        head[3] = 0x18;
        head[4] = 'f';
        head[5] = 't';
        head[6] = 'y';
        head[7] = 'p';
        System.arraycopy(brandBytes, 0, head, 8, 4);
        return head;
    }

    @Test
    @DisplayName("PNG signature → image/png")
    void detectsPng() {
        MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A));
        assertEquals("image/png", s.contentType());
        assertEquals(".png", s.extension());
        assertTrue(s.isImage());
    }

    @Test
    @DisplayName("JPEG signature → image/jpeg")
    void detectsJpeg() {
        MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0));
        assertEquals("image/jpeg", s.contentType());
        assertEquals(".jpg", s.extension());
    }

    @Test
    @DisplayName("RIFF…WEBP → image/webp (common for screenshots)")
    void detectsWebp() {
        MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(
                bytes(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50));
        assertEquals("image/webp", s.contentType());
        assertEquals(".webp", s.extension());
        assertTrue(s.isImage());
    }

    @Test
    @DisplayName("HEIC ftyp brand → image/heic, NOT video/mp4 (iPhone photos)")
    void detectsHeicNotMp4() {
        for (String brand : new String[]{"heic", "heix", "mif1", "heim"}) {
            MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(ftyp(brand));
            assertEquals("image/heic", s.contentType(), "brand=" + brand);
            assertTrue(s.isImage(), "brand=" + brand + " should be an image");
            assertFalse(s.isVideo(), "brand=" + brand + " must not be classified as video");
        }
    }

    @Test
    @DisplayName("Plain MP4 ftyp brand → video/mp4")
    void detectsMp4() {
        for (String brand : new String[]{"isom", "mp41", "mp42", "avc1"}) {
            MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(ftyp(brand));
            assertEquals("video/mp4", s.contentType(), "brand=" + brand);
            assertTrue(s.isVideo(), "brand=" + brand);
        }
    }

    @Test
    @DisplayName("QuickTime ftyp brand → video/quicktime")
    void detectsQuickTime() {
        MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(ftyp("qt  "));
        assertEquals("video/quicktime", s.contentType());
        assertEquals(".mov", s.extension());
    }

    @Test
    @DisplayName("PDF signature → application/pdf")
    void detectsPdf() {
        MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(bytes(0x25, 0x50, 0x44, 0x46, 0x2D));
        assertEquals("application/pdf", s.contentType());
        assertEquals(".pdf", s.extension());
    }

    @Test
    @DisplayName("GIF signature → image/gif")
    void detectsGif() {
        MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61));
        assertEquals("image/gif", s.contentType());
    }

    @Test
    @DisplayName("AMR voice signature → audio/amr")
    void detectsAmr() {
        MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(bytes(0x23, 0x21, 0x41, 0x4D, 0x52, 0x0A));
        assertEquals("audio/amr", s.contentType());
        assertTrue(s.isAudio());
    }

    @Test
    @DisplayName("DOCX (zip container) refined from plain zip")
    void refinesDocx() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("<types/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write("<doc/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(baos.toByteArray());
        assertEquals(".docx", s.extension());
        assertTrue(s.contentType().contains("wordprocessingml"));
    }

    @Test
    @DisplayName("Unknown / too-short bytes → octet-stream, not crash")
    void handlesUnknownAndShort() {
        assertEquals(MediaTypeSniffer.Sniffed.UNKNOWN, MediaTypeSniffer.sniff(null));
        assertEquals(MediaTypeSniffer.Sniffed.UNKNOWN, MediaTypeSniffer.sniff(bytes(0x01)));
        MediaTypeSniffer.Sniffed s = MediaTypeSniffer.sniff(bytes(0x01, 0x02, 0x03, 0x04, 0x05));
        assertFalse(s.isKnown());
        assertEquals("application/octet-stream", s.contentType());
    }
}
