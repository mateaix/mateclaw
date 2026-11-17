package vip.mate.agent.graph;

import org.springframework.ai.content.Media;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Locale;

/** Bounded image preparation for model requests, leaving original assets intact. */
final class RequestImageResizer {
    private static final int MAX_SIDE = 8192;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PIXELS = 8_000_000;
    private RequestImageResizer() {}

    static Media resize(Media media) {
        String type = media.getMimeType().toString().toLowerCase(Locale.ROOT);
        if (!(type.equals("image/png") || type.equals("image/jpeg"))) return media;
        if (media.getData() instanceof String locator) {
            String prefix = "data:" + type + ";base64,";
            if (!locator.regionMatches(true, 0, prefix, 0, prefix.length()) || locator.length() > 64 * 1024 * 1024) return media;
            try {
                var decoded = Media.builder().mimeType(media.getMimeType()).id(media.getId()).name(media.getName())
                        .data(java.util.Base64.getDecoder().decode(locator.substring(prefix.length()))).build();
                var resized = resize(decoded);
                return resized == decoded ? media : resized;
            } catch (IllegalArgumentException ignored) { return media; }
        }
        if (!(media.getData() instanceof byte[] bytes) || bytes.length == 0) return media;
        if (type.equals("image/png") ? hasAnimation(bytes) : hasExif(bytes)) return media;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return media;
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(type.equals("image/png") && format.equals("png")
                        || type.equals("image/jpeg") && (format.equals("jpeg") || format.equals("jpg")))) return media;
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= MAX_SIDE && height <= MAX_SIDE && bytes.length <= MAX_BYTES) return media;
                if (reader.getNumImages(true) != 1) return media;
                double scale = Math.min(1d, Math.min((double)MAX_SIDE / Math.max(width, height),
                        Math.sqrt((double)MAX_PIXELS / width / height)));
                int targetWidth = Math.max(1, (int)(width * scale));
                int targetHeight = Math.max(1, (int)(height * scale));
                var params = reader.getDefaultReadParam();
                int subsample = Math.max(1, (int)Math.floor(1 / scale));
                params.setSourceSubsampling(subsample, subsample, 0, 0);
                BufferedImage source = reader.read(0, params);
                for (int attempt = 0; attempt < 3; attempt++) {
                    boolean alpha = type.equals("image/png") && source.getColorModel().hasAlpha();
                    var target = new BufferedImage(targetWidth, targetHeight,
                            alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                    var graphics = target.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
                    } finally { graphics.dispose(); }
                    for (float quality : type.equals("image/png") ? new float[]{1f} : new float[]{.85f,.70f,.55f}) {
                        byte[] encoded = encode(target, format, quality);
                        if (encoded.length > 0 && encoded.length <= MAX_BYTES) {
                            return Media.builder().mimeType(media.getMimeType()).data(encoded)
                                    .id(media.getId()).name(media.getName()).build();
                        }
                    }
                    targetWidth = Math.max(1, targetWidth / 2);
                    targetHeight = Math.max(1, targetHeight / 2);
                }
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException ignored) {
            // Unknown codecs or corrupt images retain their existing provider behavior.
        }
        return media;
    }

    private static byte[] encode(BufferedImage image, String format, float quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) return new byte[0];
        var writer = writers.next();
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed() && !format.equals("png")) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); params.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            output.flush(); return bytes.toByteArray();
        } finally { writer.dispose(); }
    }

    /** Standard ImageIO cannot preserve APNG frames, so never rewrite animated PNG. */
    private static boolean hasAnimation(byte[] bytes) {
        for (int pos = 8; pos + 12 <= bytes.length;) {
            long length = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes, pos, 4).getInt());
            if (length > bytes.length - pos - 12) return true;
            if (bytes[pos+4]=='a' && bytes[pos+5]=='c' && bytes[pos+6]=='T' && bytes[pos+7]=='L') return true;
            pos += (int)length + 12;
        }
        return false;
    }

    /** Re-encoding EXIF JPEG without orientation support could silently rotate a photo. */
    private static boolean hasExif(byte[] bytes) {
        int pos = 2;
        while (pos < bytes.length) {
            if ((bytes[pos] & 255) != 255) return true;
            while (pos < bytes.length && (bytes[pos] & 255) == 255) pos++;
            if (pos >= bytes.length) return true;
            int marker = bytes[pos++] & 255;
            if (marker == 0xda || marker == 0xd9) return false;
            if (marker == 0xe1) return true;
            if (marker == 0xd8 || marker == 1 || marker >= 0xd0 && marker <= 0xd7) continue;
            if (pos + 2 > bytes.length) return true;
            int length = (bytes[pos] & 255) * 256 + (bytes[pos+1] & 255);
            if (length < 2 || length > bytes.length - pos) return true;
            pos += length;
        }
        return false;
    }
}
