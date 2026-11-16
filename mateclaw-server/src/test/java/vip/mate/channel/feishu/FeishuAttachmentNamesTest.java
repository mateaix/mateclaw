package vip.mate.channel.feishu;

import org.junit.jupiter.api.Test;
import vip.mate.channel.media.MediaSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FeishuAttachmentNamesTest {
    private String remote(String url) { return FeishuAttachmentNames.resolve(null, new MediaSource.RemoteUrl(url), "file.bin"); }
    @Test void decodesChineseAndDropsSignedQueryAndFragment() {
        assertEquals("附件 报告.docx", remote("https://example.invalid/%E9%99%84%E4%BB%B6%20%E6%8A%A5%E5%91%8A.docx?token=secret#download"));
    }
    @Test void plusIsLiteralInUrlPath() {
        assertEquals("a+b c.pdf", remote("https://example.invalid/a+b%20c.pdf"));
    }
    @Test void explicitNameWinsWithoutDecodingLiteralPercentOrPlus() {
        assertEquals("报告%20+final.pdf", FeishuAttachmentNames.resolve("C:\\tmp\\报告%20+final.pdf", new MediaSource.RemoteUrl("https://example.invalid/other.pdf"), "file.bin"));
    }
    @Test void blankNameFallsBackToSource() {
        assertEquals("报告.pdf", FeishuAttachmentNames.resolve("  ", new MediaSource.LocalPath(Path.of("/tmp/报告.pdf")), "file.bin"));
    }
    @Test void localNamesKeepLiteralPercentSequences() {
        assertEquals("report%20+final.pdf", FeishuAttachmentNames.resolve(null, new MediaSource.LocalPath(Path.of("/tmp/report%20+final.pdf")), "file.bin"));
    }
    @Test void fileUriNamesAreDecodedOnce() {
        assertEquals("报告%20.pdf", remote("file:///tmp/%E6%8A%A5%E5%91%8A%2520.pdf"));
    }
    @Test void missingOrMalformedLocatorDegradesSafely() {
        assertEquals("file.bin", remote("https://example.invalid/?token=secret"));
        assertEquals("file.bin", remote("https://example.invalid/%broken"));
        assertEquals("file.bin", FeishuAttachmentNames.resolve(null, new MediaSource.Bytes(new byte[]{1}), "file.bin"));
    }
    @Test void decodedSeparatorsCannotBecomeAnUploadPath() {
        assertEquals("报告.pdf", remote("https://example.invalid/a%2Fb%5C%E6%8A%A5%E5%91%8A.pdf"));
    }
    @Test void adapterUploadsUsingResolvedSourceName() throws Exception {
        var entity = new vip.mate.channel.model.ChannelEntity();
        entity.setId(1L); entity.setChannelType("feishu"); entity.setConfigJson("{}");
        class RecordingAdapter extends FeishuChannelAdapter {
            String uploadedName;
            RecordingAdapter() {
                super(entity, null, new com.fasterxml.jackson.databind.ObjectMapper(),
                        org.mockito.Mockito.mock(FeishuMediaUploader.class), null);
            }
            @Override void uploadAndSendAttachment(String targetId, Long channelId, MediaSource source,
                    String fileName, String type, String contentType, Integer duration) {
                uploadedName = fileName;
            }
        }
        var adapter = new RecordingAdapter();
        var part = vip.mate.workspace.conversation.model.MessageContentPart.file(null, " ", "application/pdf");
        part.setPath("/missing/path/incorrect.pdf");
        part.setFileUrl("https://example.invalid/%E6%8A%A5%E5%91%8A.pdf?token=secret");
        var method = FeishuChannelAdapter.class.getDeclaredMethod("handleMediaPart", String.class, Long.class,
                vip.mate.workspace.conversation.model.MessageContentPart.class, String.class, String.class);
        method.setAccessible(true);
        method.invoke(adapter, "user", 1L, part, "file", "file.bin");
        assertEquals("报告.pdf", adapter.uploadedName);
    }
}
