package vip.mate.channel.media;

/**
 * Channel-agnostic request to upload one media asset.
 *
 * <p>Required: {@code channelId}, {@code source}, {@code fileName},
 * {@code mediaType}. The remaining fields are best-effort hints —
 * platform SDKs that need them will use them, others ignore.
 *
 * @param channelId      identifies which channel-row credentials to use
 *                       when the uploader needs to authenticate
 * @param source         the payload (bytes / on-disk path / remote URL)
 * @param fileName       file name with extension (used by platform SDKs
 *                       and shown to end users in IM file bubbles)
 * @param mediaType      one of {@code image} / {@code file} /
 *                       {@code audio} / {@code video}. Decides which
 *                       platform endpoint the uploader picks and how
 *                       the receiving IM client renders the bubble.
 *                       May be downgraded by a {@link MediaSizePolicy}.
 * @param contentType    MIME type (e.g. {@code image/png},
 *                       {@code audio/opus}); used by both the size
 *                       policy and the platform SDK
 * @param durationMillis playback duration in ms for audio/video; some
 *                       SDKs surface it on the receiver UI; nullable
 */
public record MediaUploadRequest(
        Long channelId,
        MediaSource source,
        String fileName,
        String mediaType,
        String contentType,
        Integer durationMillis) {

    public MediaUploadRequest {
        if (channelId == null) {
            throw new IllegalArgumentException("MediaUploadRequest.channelId must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("MediaUploadRequest.source must not be null");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("MediaUploadRequest.fileName must be non-blank");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("MediaUploadRequest.mediaType must be non-blank");
        }
    }
}
