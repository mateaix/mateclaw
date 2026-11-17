package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.MimeTypeUtils;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RequestImageResizerTest {
    private byte[] image(String format, int width, int height, boolean alpha) throws Exception {
        var image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, alpha ? 0x80112233 : 0xff112233);
        var bytes = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, format, bytes)); return bytes.toByteArray();
    }
    @Test void oversizedPngShrinksAndKeepsTransparencyAndMediaIdentityFields() throws Exception {
        byte[] bytes = image("png", 9000, 2, true);
        var original = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(bytes).id("id").name("透明.png").build();
        var resized = RequestImageResizer.resize(original);
        var decoded = ImageIO.read(new ByteArrayInputStream(resized.getDataAsByteArray()));
        assertTrue(decoded.getWidth() <= 8192); assertTrue(decoded.getColorModel().hasAlpha());
        assertEquals("id", resized.getId()); assertEquals("透明.png", resized.getName());
        assertEquals(MimeTypeUtils.IMAGE_PNG, resized.getMimeType());
        assertArrayEquals(bytes, original.getDataAsByteArray());
    }
    @Test void oversizedJpegRetainsItsMimeType() throws Exception {
        var original = Media.builder().mimeType(MimeTypeUtils.IMAGE_JPEG).data(image("jpg", 9000, 2, false)).build();
        var result = RequestImageResizer.resize(original);
        assertTrue(ImageIO.read(new ByteArrayInputStream(result.getDataAsByteArray())).getWidth() <= 8192);
        assertEquals(MimeTypeUtils.IMAGE_JPEG, result.getMimeType());
    }
    @Test void requestNormalizerUsesResizedCopyAndPreservesPromptOptions() throws Exception {
        var media = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(image("png", 9000, 2, false)).build();
        var user = UserMessage.builder().text("inspect").media(List.of(media)).build();
        var prompt = new Prompt(List.of(user)); var result = MediaRequestNormalizer.normalize(prompt);
        assertNotSame(prompt, result); assertSame(prompt.getOptions(), result.getOptions());
        assertSame(media, user.getMedia().getFirst());
        assertTrue(ImageIO.read(new ByteArrayInputStream(((UserMessage)result.getInstructions().getFirst()).getMedia().getFirst().getDataAsByteArray())).getWidth() <= 8192);
    }
    @Test void oversizedBytePayloadIsReducedWithinRequestBudget() throws Exception {
        var image = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(7);
        for (int y=0; y<1024; y++) for (int x=0; x<1024; x++) image.setRGB(x,y,random.nextInt());
        var encoded = new ByteArrayOutputStream(); ImageIO.write(image,"png",encoded);
        assertTrue(encoded.size() > 2*1024*1024);
        var media = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(encoded.toByteArray()).build();
        assertTrue(RequestImageResizer.resize(media).getDataAsByteArray().length <= 2*1024*1024);
    }
    @Test void animatedPngAndExifJpegAreNotFlattenedOrReoriented() throws Exception {
        byte[] png = image("png",9000,2,true);
        var animated = new ByteArrayOutputStream(); animated.write(png,0,33);
        var chunk = new ByteArrayOutputStream(); var out = new DataOutputStream(chunk);
        out.writeBytes("acTL"); out.writeInt(2); out.writeInt(0);
        var crc = new java.util.zip.CRC32(); crc.update(chunk.toByteArray());
        var container = new DataOutputStream(animated); container.writeInt(8); container.write(chunk.toByteArray()); container.writeInt((int)crc.getValue());
        animated.write(png,33,png.length-33);
        var apng = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(animated.toByteArray()).build();
        assertSame(apng, RequestImageResizer.resize(apng));
        byte[] jpeg = image("jpg",9000,2,false);
        var withExif = new ByteArrayOutputStream(); withExif.write(jpeg,0,2);
        withExif.write(new byte[]{(byte)0xff,(byte)0xff,(byte)0xe1,0,8,'E','x','i','f',0,0}); withExif.write(jpeg,2,jpeg.length-2);
        var exif = Media.builder().mimeType(MimeTypeUtils.IMAGE_JPEG).data(withExif.toByteArray()).build();
        assertSame(exif, RequestImageResizer.resize(exif));
    }
    @Test void inlineDataUriIsResizedWhileMalformedBase64RemainsUntouched() throws Exception {
        byte[] bytes = image("png",9000,2,true);
        var uri = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG)
                .data("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)).build();
        var result = RequestImageResizer.resize(uri);
        assertNotSame(uri,result);
        assertTrue(ImageIO.read(new ByteArrayInputStream(result.getDataAsByteArray())).getWidth() <= 8192);
        var malformed = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data("data:image/png;base64,%%%broken").build();
        assertSame(malformed,RequestImageResizer.resize(malformed));
    }
    @Test void smallImagesRemoteReferencesAndUnsupportedFormatsRemainUntouched() throws Exception {
        var small = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(image("png", 2, 2, true)).build();
        var remote = new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.invalid/huge.png"));
        var gif = Media.builder().mimeType(MimeTypeUtils.IMAGE_GIF).data(image("gif", 9000, 2, false)).build();
        var broken = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[]{1,2,3}).build();
        for (var media : List.of(small, remote, gif, broken)) assertSame(media, RequestImageResizer.resize(media));
    }
}
