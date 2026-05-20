package vip.mate.channel.media;

import java.nio.file.Path;

/**
 * Sealed input source for {@link MediaUploadRequest}.
 *
 * <p>Exactly one of the three variants carries the payload. Concrete
 * {@link MediaUploader} implementations decide how to normalize each
 * variant to whatever shape the platform SDK requires (the Feishu SDK,
 * for instance, takes {@link java.io.File}, so bytes/url variants are
 * staged through a temp file).
 */
public sealed interface MediaSource permits MediaSource.Bytes, MediaSource.LocalPath, MediaSource.RemoteUrl {

    /** In-memory bytes — typical for content produced by an agent tool. */
    record Bytes(byte[] data) implements MediaSource {
        public Bytes {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("MediaSource.Bytes payload must be non-empty");
            }
        }
    }

    /** Already-on-disk file — typical for skill scripts that write to a workspace path. */
    record LocalPath(Path path) implements MediaSource {
        public LocalPath {
            if (path == null) {
                throw new IllegalArgumentException("MediaSource.LocalPath path must not be null");
            }
        }
    }

    /**
     * Remote HTTP(S) URL — uploader fetches it before handing the bytes
     * to the platform SDK. Implementations may choose to enforce a
     * size cap on the fetched body to protect memory.
     */
    record RemoteUrl(String url) implements MediaSource {
        public RemoteUrl {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("MediaSource.RemoteUrl url must be non-blank");
            }
        }
    }
}
