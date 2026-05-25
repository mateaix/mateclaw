package vip.mate.channel.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lock in the {@link MediaSource} sealed contract — each variant
 * validates its required payload at construction so downstream
 * uploaders don't have to defensive-null-check every field.
 */
class MediaSourceTest {

    @Test
    @DisplayName("Bytes rejects null and empty payload")
    void bytesRequiresContent() {
        assertThrows(IllegalArgumentException.class, () -> new MediaSource.Bytes(null));
        assertThrows(IllegalArgumentException.class, () -> new MediaSource.Bytes(new byte[0]));
    }

    @Test
    @DisplayName("LocalPath rejects null path")
    void localPathRequiresPath() {
        assertThrows(IllegalArgumentException.class, () -> new MediaSource.LocalPath(null));
    }

    @Test
    @DisplayName("RemoteUrl rejects blank URL")
    void remoteUrlRequiresUrl() {
        assertThrows(IllegalArgumentException.class, () -> new MediaSource.RemoteUrl(null));
        assertThrows(IllegalArgumentException.class, () -> new MediaSource.RemoteUrl(""));
        assertThrows(IllegalArgumentException.class, () -> new MediaSource.RemoteUrl("  "));
    }

    @Test
    @DisplayName("happy paths accept the three valid forms")
    void happyPaths() {
        MediaSource b = new MediaSource.Bytes(new byte[]{1, 2, 3});
        MediaSource p = new MediaSource.LocalPath(Paths.get("/tmp/x"));
        MediaSource u = new MediaSource.RemoteUrl("https://example.com/x.png");

        // Exhaustive switch — verifies the sealed contract at compile time too.
        assertEquals("Bytes", classifyVariant(b));
        assertEquals("LocalPath", classifyVariant(p));
        assertEquals("RemoteUrl", classifyVariant(u));
    }

    private static String classifyVariant(MediaSource s) {
        return switch (s) {
            case MediaSource.Bytes ignored -> "Bytes";
            case MediaSource.LocalPath ignored -> "LocalPath";
            case MediaSource.RemoteUrl ignored -> "RemoteUrl";
        };
    }
}
