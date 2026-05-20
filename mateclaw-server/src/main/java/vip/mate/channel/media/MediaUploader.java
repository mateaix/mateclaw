package vip.mate.channel.media;

/**
 * SPI for "upload one media asset to a channel platform".
 *
 * <p>Implemented once per channel type (Feishu / WeCom / DingTalk / …).
 * The adapter for that channel injects all matching beans and routes
 * by {@link #channelType()}. Pure boundary contract — the SPI knows
 * nothing about adapters, message routing, or downstream send logic;
 * it just turns {@link MediaUploadRequest} into a platform-side
 * {@link MediaUploadResult#mediaId() mediaId}.
 *
 * <p>Implementations are expected to:
 * <ol>
 *   <li>Consult their paired {@link MediaSizePolicy} first; if the
 *       decision rejects, throw {@link MediaUploadException} with the
 *       reason so the caller can surface it to the user.</li>
 *   <li>Apply any necessary downgrade ({@code image} → {@code file})
 *       before calling the platform endpoint, and propagate the note
 *       on the returned {@link MediaUploadResult}.</li>
 *   <li>Avoid leaking temp files staged for SDK calls — always clean
 *       up in a {@code finally} block.</li>
 * </ol>
 */
public interface MediaUploader {

    /**
     * Channel type this uploader serves, matching
     * {@code ChannelAdapter.getChannelType()} (e.g. {@code "feishu"}).
     */
    String channelType();

    /**
     * Upload the media and return the platform identifier that can be
     * referenced in a subsequent send-message call.
     *
     * @throws MediaUploadException on size rejection, credential issues,
     *         platform API failure, or local I/O while staging
     */
    MediaUploadResult upload(MediaUploadRequest request) throws MediaUploadException;
}
