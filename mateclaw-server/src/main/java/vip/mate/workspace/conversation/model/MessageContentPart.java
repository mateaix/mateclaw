package vip.mate.workspace.conversation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 结构化消息内容片段
 * <p>
 * 支持的 type：
 * - text: 纯文本内容
 * - thinking: AI 思考过程
 * - image: 图片（fileUrl 或 mediaId）
 * - file: 文件附件
 * - audio: 音频
 * - video: 视频
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageContentPart {

    /**
     * text / thinking / image / file / audio / video
     */
    private String type;

    private String text;

    /** 可公开访问的 URL（CDN / 对象存储） */
    private String fileUrl;

    private String fileName;

    private String storedName;

    /** MIME 类型，如 image/png, audio/ogg */
    private String contentType;

    /** 文件大小（字节） */
    private Long fileSize;

    /**
     * 服务端本地路径，仅用于后端工具/技能消费
     */
    private String path;

    /**
     * 平台媒体标识（飞书 image_key / file_key，钉钉 downloadCode，Telegram file_id 等）。
     * 发送侧据此调用平台富媒体 API。
     */
    private String mediaId;

    /**
     * Vision-model description of an image/video part, produced by the sidecar
     * captioning path when the primary model is text-only. Persisted so the
     * description survives into later turns: history replay sends user messages
     * as text, and without a stored caption the image content would be lost on
     * every follow-up question. Null for non-media parts or when no captioning ran.
     */
    private String caption;

    // ==================== 工厂方法 ====================

    public static MessageContentPart text(String text) {
        MessageContentPart part = new MessageContentPart();
        part.setType("text");
        part.setText(text);
        return part;
    }

    public static MessageContentPart image(String mediaId, String fileUrl) {
        MessageContentPart part = new MessageContentPart();
        part.setType("image");
        part.setMediaId(mediaId);
        part.setFileUrl(fileUrl);
        part.setContentType("image/*");
        return part;
    }

    public static MessageContentPart file(String mediaId, String fileName, String contentType) {
        MessageContentPart part = new MessageContentPart();
        part.setType("file");
        part.setMediaId(mediaId);
        part.setFileName(fileName);
        part.setContentType(contentType);
        return part;
    }

    public static MessageContentPart audio(String mediaId, String fileName) {
        MessageContentPart part = new MessageContentPart();
        part.setType("audio");
        part.setMediaId(mediaId);
        part.setFileName(fileName);
        part.setContentType("audio/*");
        return part;
    }

    public static MessageContentPart video(String mediaId, String fileName) {
        MessageContentPart part = new MessageContentPart();
        part.setType("video");
        part.setMediaId(mediaId);
        part.setFileName(fileName);
        part.setContentType("video/*");
        return part;
    }

    /**
     * 3D model asset (.glb / .obj / .fbx). The frontend renders this with a
     * &lt;model-viewer&gt; Web Component when contentType starts with
     * {@code model/} (e.g. {@code model/gltf-binary} for glb).
     */
    public static MessageContentPart model3d(String mediaId, String fileName) {
        MessageContentPart part = new MessageContentPart();
        part.setType("model3d");
        part.setMediaId(mediaId);
        part.setFileName(fileName);
        part.setContentType("model/gltf-binary");
        return part;
    }

    public static MessageContentPart toolCall(String jsonPayload) {
        MessageContentPart part = new MessageContentPart();
        part.setType("tool_call");
        part.setText(jsonPayload);
        return part;
    }

    /**
     * Create a parse_error content part to surface content_parts deserialization failures
     * instead of silently returning an empty list.
     */
    public static MessageContentPart parseError(String messageId, String cause) {
        MessageContentPart part = new MessageContentPart();
        part.setType("parse_error");
        part.setText("[Message content parse failed] Message ID: " + messageId + " | Cause: " + cause);
        return part;
    }
}
