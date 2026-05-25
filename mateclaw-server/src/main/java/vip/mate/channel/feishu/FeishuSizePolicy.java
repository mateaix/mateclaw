package vip.mate.channel.feishu;

import org.springframework.stereotype.Component;
import vip.mate.channel.media.MediaSizeDecision;
import vip.mate.channel.media.MediaSizePolicy;

import java.util.Locale;
import java.util.Set;

/**
 * Feishu's per-media-type ceilings, encoded as a {@link MediaSizePolicy}.
 *
 * <p>Sources (Feishu official OpenAPI docs):
 * <ul>
 *   <li>{@code /open-apis/im/v1/images} — image payload max <b>10 MB</b></li>
 *   <li>{@code /open-apis/im/v1/files}  — file payload max <b>30 MB</b>;
 *       {@code file_type} ∈ {opus, mp4, pdf, doc, xls, ppt, stream}.
 *       Anything outside that set must be sent as {@code stream}.</li>
 *   <li>Voice msgtype only accepts {@code opus} audio. Other audio
 *       MIMEs (mp3, wav, …) cannot render as a native voice bubble —
 *       the only way to deliver them is as a downgraded file.</li>
 *   <li>Video msgtype only accepts {@code mp4}. Same downgrade rule.</li>
 * </ul>
 *
 * <p>The 30 MB file ceiling is a hard limit — even after a downgrade
 * to {@code file} the payload cannot exceed it. So a 50 MB video gets
 * rejected outright; a 40 MB image likewise.
 */
@Component
public class FeishuSizePolicy implements MediaSizePolicy {

    /** Image ceiling — Feishu {@code /im/v1/images} accepts up to 10 MB. */
    public static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;

    /** File / audio / video ceiling — Feishu {@code /im/v1/files} accepts up to 30 MB. */
    public static final long FILE_MAX_BYTES = 30L * 1024 * 1024;

    /** Audio MIMEs that render as native voice bubbles. Anything else → file. */
    private static final Set<String> VOICE_SUPPORTED_MIMES = Set.of(
            "audio/opus", "audio/ogg", "audio/ogg;codecs=opus"
    );

    /** Video MIMEs that render as native video bubbles. Anything else → file. */
    private static final Set<String> VIDEO_SUPPORTED_MIMES = Set.of(
            "video/mp4"
    );

    @Override
    public String channelType() {
        return "feishu";
    }

    @Override
    public MediaSizeDecision evaluate(long fileSize, String mediaType, String contentType) {
        String type = mediaType == null ? "file" : mediaType.toLowerCase(Locale.ROOT);
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();

        // ---- Hard reject: nothing on Feishu carries > 30 MB
        if (fileSize > FILE_MAX_BYTES) {
            double mb = fileSize / 1024.0 / 1024.0;
            return MediaSizeDecision.reject(type, String.format(
                    Locale.ROOT,
                    "文件大小 %.2fMB 超过飞书 30MB 上限，无法发送。请压缩或拆分后再发。",
                    mb));
        }

        // ---- Image: 10 MB hard, oversized → file
        if ("image".equals(type)) {
            if (fileSize > IMAGE_MAX_BYTES) {
                double mb = fileSize / 1024.0 / 1024.0;
                return MediaSizeDecision.downgradeTo("file", String.format(
                        Locale.ROOT,
                        "图片 %.2fMB 超过飞书 10MB 限制，已转为文件形式发送",
                        mb));
            }
            return MediaSizeDecision.pass("image");
        }

        // ---- Audio: opus-only for voice bubble, else → file
        if ("audio".equals(type)) {
            if (!mime.isEmpty() && !isVoiceSupported(mime)) {
                return MediaSizeDecision.downgradeTo("file",
                        "语音格式 " + mime + " 不支持（飞书原生语音仅支持 opus），已转为文件形式发送");
            }
            return MediaSizeDecision.pass("audio");
        }

        // ---- Video: mp4-only for video bubble, else → file
        if ("video".equals(type)) {
            if (!mime.isEmpty() && !VIDEO_SUPPORTED_MIMES.contains(mime)) {
                return MediaSizeDecision.downgradeTo("file",
                        "视频格式 " + mime + " 不支持（飞书原生视频仅支持 mp4），已转为文件形式发送");
            }
            return MediaSizeDecision.pass("video");
        }

        // ---- Plain file — already passed the 30 MB gate above
        return MediaSizeDecision.pass("file");
    }

    private static boolean isVoiceSupported(String mime) {
        for (String supported : VOICE_SUPPORTED_MIMES) {
            if (mime.startsWith(supported)) return true;
        }
        return false;
    }
}
