package vip.mate.channel.media;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * Channel-agnostic image compressor — shrinks an image to fit a
 * platform's per-image byte ceiling.
 *
 * <p>Strategy (in order):
 * <ol>
 *   <li>If already under {@code maxBytes}, return as-is.</li>
 *   <li>Decode → convert to RGB (drops alpha; flattens against white).</li>
 *   <li>Re-encode JPEG at progressively lower quality
 *       ({@code qualitySteps}); accept the first encoding that fits.</li>
 *   <li>If still too big, downscale by progressively smaller factors
 *       ({@code scaleSteps}) at quality 0.5; accept first that fits.</li>
 *   <li>If nothing fits, return the smallest variant we produced —
 *       the caller's size policy will then reject or further downgrade.</li>
 * </ol>
 *
 * <p>Pure static utility — no Spring, no platform knowledge. Callers
 * pass in their platform's limit (Feishu image 10 MB, WeCom image
 * 1.9 MB safe-margin under the 2 MB hard cap, etc.).
 */
@Slf4j
public final class ImageCompressor {

    /** Default JPEG quality steps used by {@link #compressIfNeeded(byte[], String, long)}. */
    public static final float[] DEFAULT_QUALITY_STEPS = {0.85f, 0.70f, 0.50f, 0.30f};

    /** Default downscale factors used by {@link #compressIfNeeded(byte[], String, long)}. */
    public static final double[] DEFAULT_SCALE_STEPS = {0.75, 0.50, 0.25};

    private ImageCompressor() {}

    /**
     * Compress with the default quality and scale ladders.
     *
     * @param imageBytes original encoded image (PNG / JPEG / GIF / …)
     * @param fileName   original file name — kept for log clarity only
     * @param maxBytes   target ceiling; bytes returned will be at most
     *                   this size unless every step still exceeds it
     */
    public static byte[] compressIfNeeded(byte[] imageBytes, String fileName, long maxBytes) {
        return compressIfNeeded(imageBytes, fileName, maxBytes, DEFAULT_QUALITY_STEPS, DEFAULT_SCALE_STEPS);
    }

    /**
     * Compress with caller-supplied quality and scale ladders. Useful
     * when a platform's limit is tight enough that the defaults
     * leave little headroom and a custom ladder converges faster.
     */
    public static byte[] compressIfNeeded(byte[] imageBytes, String fileName, long maxBytes,
                                          float[] qualitySteps, double[] scaleSteps) {
        if (imageBytes == null || imageBytes.length == 0) {
            return imageBytes;
        }
        if (imageBytes.length <= maxBytes) {
            return imageBytes;
        }

        log.info("[image-compress] {}: original {}KB > limit {}KB",
                fileName, imageBytes.length / 1024, maxBytes / 1024);

        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) {
                log.warn("[image-compress] {}: ImageIO could not decode; returning original", fileName);
                return imageBytes;
            }

            BufferedImage rgbImg = toRgb(img);

            for (float quality : qualitySteps) {
                byte[] compressed = writeJpeg(rgbImg, quality);
                if (compressed.length <= maxBytes) {
                    log.info("[image-compress] {}: compressed to {}KB (quality={})",
                            fileName, compressed.length / 1024, quality);
                    return compressed;
                }
            }

            int w = rgbImg.getWidth();
            int h = rgbImg.getHeight();
            byte[] smallest = null;
            for (double scale : scaleSteps) {
                BufferedImage resized = resize(rgbImg, (int) (w * scale), (int) (h * scale));
                byte[] compressed = writeJpeg(resized, 0.50f);
                smallest = compressed;
                if (compressed.length <= maxBytes) {
                    log.info("[image-compress] {}: resized to {}x{}, {}KB",
                            fileName, (int) (w * scale), (int) (h * scale), compressed.length / 1024);
                    return compressed;
                }
            }

            log.warn("[image-compress] {}: could not shrink below {}KB; returning smallest ({}KB)",
                    fileName, maxBytes / 1024, smallest != null ? smallest.length / 1024 : 0);
            return smallest != null ? smallest : imageBytes;

        } catch (Exception e) {
            log.error("[image-compress] {}: failed ({}); returning original", fileName, e.getMessage());
            return imageBytes;
        }
    }

    private static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static byte[] writeJpeg(BufferedImage img, float quality) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG ImageWriter available");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            }
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private static BufferedImage resize(BufferedImage img, int newWidth, int newHeight) {
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newWidth, newHeight);
        g.drawImage(img, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return resized;
    }
}
