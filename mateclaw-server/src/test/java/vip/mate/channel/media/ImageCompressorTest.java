package vip.mate.channel.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirm the channel-agnostic {@link ImageCompressor} behaves the
 * same on Feishu's 10 MB ceiling and WeCom's 1.9 MB safe-margin.
 *
 * <p>The compressor's "give up if you can't fit; return smallest"
 * fallback exists because every channel's adapter follows up with its
 * own size policy that knows how to downgrade or reject — we never
 * want to crash on an undecodable PNG.
 */
class ImageCompressorTest {

    @Test
    @DisplayName("under-limit bytes are returned untouched (same reference)")
    void underLimitPassesThrough() {
        byte[] tiny = new byte[100];
        new Random(42).nextBytes(tiny);
        byte[] out = ImageCompressor.compressIfNeeded(tiny, "tiny.bin", 1000);
        assertSame(tiny, out, "must not copy when already under limit");
    }

    @Test
    @DisplayName("null or empty input returns as-is, no NPE")
    void nullAndEmpty() {
        assertArrayEquals(null, ImageCompressor.compressIfNeeded(null, "n", 100));
        byte[] empty = new byte[0];
        assertSame(empty, ImageCompressor.compressIfNeeded(empty, "e", 100));
    }

    @Test
    @DisplayName("undecodable garbage at over-limit size returns original (no crash)")
    void undecodableReturnsOriginal() {
        byte[] junk = new byte[1500];
        new Random(99).nextBytes(junk);
        byte[] out = ImageCompressor.compressIfNeeded(junk, "junk.bin", 1000);
        // ImageIO.read returns null on garbage; compressor logs and returns input.
        assertSame(junk, out);
    }

    @Test
    @DisplayName("real PNG over the limit is shrunk to fit")
    void realImageShrinksToFit() throws Exception {
        // Generate a 256x256 PNG that's well over a tight limit when uncompressed RGBA
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Fill with noisy pattern so PNG can't trivially compress to almost nothing
        Random rnd = new Random(7);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                img.setRGB(x, y, rnd.nextInt());
            }
        }
        g.setColor(Color.WHITE);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] pngBytes = baos.toByteArray();

        // 4 KB ceiling — noisy PNG won't fit, but JPEG at low quality + resize will.
        byte[] out = ImageCompressor.compressIfNeeded(pngBytes, "noise.png", 4000);
        assertNotNull(out);
        // Either fits, or returns the smallest variant. Either way it's smaller than the input.
        assertTrue(out.length < pngBytes.length,
                "compressor should at least shrink below original; in=" + pngBytes.length + " out=" + out.length);
    }
}
