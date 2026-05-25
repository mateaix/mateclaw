package vip.mate.channel.media;

/**
 * SPI for "what does this channel platform accept at what size".
 *
 * <p>One implementation per channel type. Encodes the platform's
 * per-media-type byte ceilings and any "wrong MIME → downgrade to file"
 * rules. Kept independent of {@link MediaUploader} so the policy can be
 * unit-tested in pure isolation and reused by any caller that wants to
 * pre-validate before commit (e.g. an admin UI showing "this file is
 * too big for WeCom" up-front).
 *
 * <p>Pure function — no platform credentials, no network I/O.
 */
public interface MediaSizePolicy {

    /** Channel type this policy serves, matching {@link MediaUploader#channelType()}. */
    String channelType();

    /**
     * Decide whether to accept, downgrade, or reject the given upload.
     *
     * @param fileSize    payload size in bytes
     * @param mediaType   requested type — {@code image} / {@code file} /
     *                    {@code audio} / {@code video}
     * @param contentType MIME (may be null; policies that don't care
     *                    about MIME ignore it)
     */
    MediaSizeDecision evaluate(long fileSize, String mediaType, String contentType);
}
