package vip.mate.channel.feishu;

import vip.mate.channel.media.MediaSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Resolve the user-facing upload name independently of the upload transport. */
final class FeishuAttachmentNames {
    private FeishuAttachmentNames() {}

    static String resolve(String explicit, MediaSource source, String fallback) {
        String name = basename(explicit);
        if (name != null) return name;
        if (source instanceof MediaSource.LocalPath local) {
            name = basename(local.path().toString());
        } else if (source instanceof MediaSource.RemoteUrl remote) {
            try {
                URI uri = URI.create(remote.url());
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                        || "file".equalsIgnoreCase(scheme)) {
                    // URI paths are percent-encoded, not form-encoded: '+' is literal.
                    String raw = basename(uri.getRawPath());
                    if (raw != null) name = basename(URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8));
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed locator must not prevent the attachment from being attempted.
            }
        }
        return name != null ? name : fallback;
    }

    private static String basename(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.isBlank() || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(Character::isISOControl)) return null;
        return name;
    }
}
