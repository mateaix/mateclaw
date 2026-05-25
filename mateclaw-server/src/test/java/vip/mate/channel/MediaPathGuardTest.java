package vip.mate.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RFC-03 Lane K3 — covers {@link MediaPathGuard} validation rules.
 *
 * <p>Each test reads as a property of "what every channel adapter must
 * agree on": no path traversal, no implicit directory writes, no
 * surprise extensions, no DoS via giant files. Failures bind to the
 * stable {@link MediaPathGuard.Reason} codes so audit / metrics
 * downstream can group violations without parsing message text.
 */
class MediaPathGuardTest {

    private static MediaPathGuard.Policy policy(Path workspace) {
        return new MediaPathGuard.Policy(
                workspace,
                Set.of("png", "jpg", "pdf", "txt"),
                10 * 1024 * 1024 // 10 MiB
        );
    }

    // ── Containment ───────────────────────────────────────────────────────

    @Test
    @DisplayName("file under workspace root → returns canonical path")
    void fileInWorkspaceAccepted(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("hello.txt");
        Files.writeString(file, "hi");

        Path resolved = MediaPathGuard.validate(file, policy(workspace));

        assertNotNull(resolved);
        assertEquals(workspace.toRealPath(), resolved.getParent());
    }

    @Test
    @DisplayName("file outside workspace via ../ → PATH_OUTSIDE_WORKSPACE")
    void traversalRejected(@TempDir Path workspace, @TempDir Path elsewhere) throws Exception {
        Path victim = elsewhere.resolve("secret.txt");
        Files.writeString(victim, "top secret");

        var ex = assertThrows(MediaPathGuard.MediaValidationException.class,
                () -> MediaPathGuard.validate(victim, policy(workspace)));
        assertEquals(MediaPathGuard.Reason.PATH_OUTSIDE_WORKSPACE, ex.reason());
    }

    @Test
    @DisplayName("workspace prefix-name overlap not exploitable — /ws-foo doesn't match /ws-foobar")
    void prefixOverlapIsNotContainment(@TempDir Path tmp) throws Exception {
        // /tmp/ws/             ← workspace
        // /tmp/wsbig/file.txt  ← attacker file with a similar prefix
        Path workspace = tmp.resolve("ws");
        Path neighbor = tmp.resolve("wsbig");
        Files.createDirectory(workspace);
        Files.createDirectory(neighbor);
        Path attackerFile = neighbor.resolve("file.txt");
        Files.writeString(attackerFile, "x");

        var ex = assertThrows(MediaPathGuard.MediaValidationException.class,
                () -> MediaPathGuard.validate(attackerFile, policy(workspace)));
        assertEquals(MediaPathGuard.Reason.PATH_OUTSIDE_WORKSPACE, ex.reason(),
                "Path.startsWith must compare elements, not strings — otherwise wsbig looks like a child of ws");
    }

    @Test
    @DisplayName("missing file → FILE_MISSING (not opaque IO_ERROR)")
    void missingFile(@TempDir Path workspace) {
        Path nope = workspace.resolve("does-not-exist.txt");

        var ex = assertThrows(MediaPathGuard.MediaValidationException.class,
                () -> MediaPathGuard.validate(nope, policy(workspace)));
        assertEquals(MediaPathGuard.Reason.FILE_MISSING, ex.reason(),
                "missing files have a dedicated reason so audit output isn't misleading");
    }

    // ── Type ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("directory passed in place of file → NOT_A_REGULAR_FILE")
    void directoryRejected(@TempDir Path workspace) throws Exception {
        Path subdir = workspace.resolve("subdir");
        Files.createDirectory(subdir);

        var ex = assertThrows(MediaPathGuard.MediaValidationException.class,
                () -> MediaPathGuard.validate(subdir, policy(workspace)));
        assertEquals(MediaPathGuard.Reason.NOT_A_REGULAR_FILE, ex.reason());
    }

    // ── Extension ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("extension allowlist case-insensitive")
    void extensionCaseInsensitive(@TempDir Path workspace) throws Exception {
        Path uppercaseExt = workspace.resolve("HelloWorld.PNG");
        Files.write(uppercaseExt, new byte[]{0x1a});

        // Should pass — policy allows "png" lowercase, file has "PNG".
        Path ok = MediaPathGuard.validate(uppercaseExt, policy(workspace));
        assertNotNull(ok);
    }

    @Test
    @DisplayName("policy allowlist normalizes leading-dot extensions")
    void allowlistAcceptsDotPrefix(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "x");

        // Same policy, but the dev specified ".txt" instead of "txt" — both work.
        var p = new MediaPathGuard.Policy(workspace, Set.of(".txt"), 1024L);
        assertNotNull(MediaPathGuard.validate(file, p));
    }

    @Test
    @DisplayName("extension not in allowlist → EXTENSION_NOT_ALLOWED")
    void extensionNotAllowed(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("malware.exe");
        Files.write(file, new byte[]{0x4d, 0x5a});

        var ex = assertThrows(MediaPathGuard.MediaValidationException.class,
                () -> MediaPathGuard.validate(file, policy(workspace)));
        assertEquals(MediaPathGuard.Reason.EXTENSION_NOT_ALLOWED, ex.reason());
    }

    @Test
    @DisplayName("file with no extension → EXTENSION_NOT_ALLOWED (defensive)")
    void noExtension(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("README");
        Files.writeString(file, "doc");

        var ex = assertThrows(MediaPathGuard.MediaValidationException.class,
                () -> MediaPathGuard.validate(file, policy(workspace)));
        assertEquals(MediaPathGuard.Reason.EXTENSION_NOT_ALLOWED, ex.reason());
    }

    // ── Size ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("file at policy size cap is accepted (boundary)")
    void atCapAccepted(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("at-cap.txt");
        Files.write(file, new byte[100]);

        var p = new MediaPathGuard.Policy(workspace, Set.of("txt"), 100L);
        Path ok = MediaPathGuard.validate(file, p);
        assertNotNull(ok);
    }

    @Test
    @DisplayName("file 1 byte over cap → FILE_TOO_LARGE")
    void overCapRejected(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("big.txt");
        Files.write(file, new byte[101]);

        var p = new MediaPathGuard.Policy(workspace, Set.of("txt"), 100L);
        var ex = assertThrows(MediaPathGuard.MediaValidationException.class,
                () -> MediaPathGuard.validate(file, p));
        assertEquals(MediaPathGuard.Reason.FILE_TOO_LARGE, ex.reason());
    }

    // ── Returned canonical path ───────────────────────────────────────────

    @Test
    @DisplayName("returned path is canonical (toRealPath) — TOCTOU-safe for downstream callers")
    void canonicalPathReturned(@TempDir Path workspace) throws Exception {
        // Use a relative path that resolves to a real file via . segment —
        // the returned value must drop the redundant segment.
        Path realFile = workspace.resolve("real.png");
        Files.write(realFile, new byte[]{1});
        Path withDotSegment = workspace.resolve(".").resolve("real.png");

        Path canonical = MediaPathGuard.validate(withDotSegment, policy(workspace));

        assertEquals(realFile.toRealPath(), canonical);
        assertNotEquals(withDotSegment, canonical,
                "validate must return the canonical form, not echo the user-supplied path verbatim");
    }

    // ── Policy guards ─────────────────────────────────────────────────────

    @Test
    @DisplayName("non-positive maxBytes → IllegalArgumentException at policy construction")
    void zeroMaxBytesRejected(@TempDir Path workspace) {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaPathGuard.Policy(workspace, Set.of("txt"), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaPathGuard.Policy(workspace, Set.of("txt"), -100L));
    }

    @Test
    @DisplayName("extensionOf — public-internals helper coverage")
    void extensionOfHelper() {
        assertEquals("png", MediaPathGuard.extensionOf(Path.of("a.png")));
        assertEquals("png", MediaPathGuard.extensionOf(Path.of("PATH/a.PNG")));
        assertEquals("", MediaPathGuard.extensionOf(Path.of("README")));
        assertEquals("", MediaPathGuard.extensionOf(Path.of("trailing.")));
        assertEquals("gz", MediaPathGuard.extensionOf(Path.of("archive.tar.gz")),
                "double-dot filenames should report the rightmost segment");
    }
}
