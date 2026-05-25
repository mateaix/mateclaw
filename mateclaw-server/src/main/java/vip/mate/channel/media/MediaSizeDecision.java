package vip.mate.channel.media;

/**
 * Decision returned by {@link MediaSizePolicy#evaluate}.
 *
 * <p>Three terminal cases, exposed as separate boolean flags so the
 * caller can branch without inspecting which fields are populated:
 * <ul>
 *   <li><b>pass</b> — {@code rejected=false}, {@code downgraded=false}.
 *       Upload as-is; {@code finalMediaType} matches request.</li>
 *   <li><b>downgraded</b> — {@code rejected=false}, {@code downgraded=true}.
 *       Still upload, but as {@code finalMediaType} (e.g. an oversized
 *       image is uploaded as {@code file}). Append {@code downgradeNote}
 *       to the message body so the user knows why.</li>
 *   <li><b>rejected</b> — {@code rejected=true}. Do not upload at all;
 *       surface {@code rejectReason} as the message body. Even
 *       {@code file} cannot carry it.</li>
 * </ul>
 *
 * <p>Generalised from WeCom's {@code WeComUploadLimitDecision} so every
 * channel that needs platform-specific size rules can implement
 * {@link MediaSizePolicy} without re-inventing the result shape.
 */
public record MediaSizeDecision(
        String finalMediaType,
        boolean rejected,
        String rejectReason,
        boolean downgraded,
        String downgradeNote) {

    public static MediaSizeDecision pass(String mediaType) {
        return new MediaSizeDecision(mediaType, false, null, false, null);
    }

    public static MediaSizeDecision reject(String mediaType, String reason) {
        return new MediaSizeDecision(mediaType, true, reason, false, null);
    }

    public static MediaSizeDecision downgradeTo(String newMediaType, String note) {
        return new MediaSizeDecision(newMediaType, false, null, true, note);
    }
}
