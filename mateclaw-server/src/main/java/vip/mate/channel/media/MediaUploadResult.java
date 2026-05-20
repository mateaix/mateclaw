package vip.mate.channel.media;

/**
 * Result of a successful {@link MediaUploader#upload} call.
 *
 * @param mediaId         platform-side identifier — Feishu
 *                        {@code image_key} / {@code file_key};
 *                        DingTalk {@code mediaId} / {@code downloadCode};
 *                        WeCom {@code media_id}. Callers store this on
 *                        the {@code MessageContentPart} and feed it to
 *                        the platform's message-send call.
 * @param finalMediaType  the media type actually uploaded under — may
 *                        differ from {@link MediaUploadRequest#mediaType()}
 *                        if a {@link MediaSizePolicy} downgraded it
 *                        (e.g. oversized image → file)
 * @param downgradeNote   optional user-visible note explaining a
 *                        downgrade; null when no downgrade happened
 */
public record MediaUploadResult(
        String mediaId,
        String finalMediaType,
        String downgradeNote) {

    public MediaUploadResult {
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("MediaUploadResult.mediaId must be non-blank");
        }
        if (finalMediaType == null || finalMediaType.isBlank()) {
            throw new IllegalArgumentException("MediaUploadResult.finalMediaType must be non-blank");
        }
    }

    /** Convenience for the common "no downgrade" path. */
    public static MediaUploadResult of(String mediaId, String mediaType) {
        return new MediaUploadResult(mediaId, mediaType, null);
    }
}
