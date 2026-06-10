package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.wiki.WikiProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WikiSourcePathValidator}: empty roots allow anything
 * (opt-in), configured roots enforce containment, and symlinks are resolved so
 * they cannot escape an allowed root.
 */
class WikiSourcePathValidatorTest {

    private WikiSourcePathValidator validator(List<String> roots) {
        WikiProperties props = new WikiProperties();
        props.setAllowedSourceRoots(roots);
        return new WikiSourcePathValidator(props);
    }

    private WikiSourcePathValidator failClosedValidator() {
        WikiProperties props = new WikiProperties();
        props.setRequireAllowedRoots(true);
        return new WikiSourcePathValidator(props);
    }

    @Test
    void blankPath_rejected() {
        assertThrows(IllegalArgumentException.class, () -> validator(List.of()).validateDirectory(" "));
    }

    @Test
    void emptyRoots_allowAnyPath(@TempDir Path tmp) throws IOException {
        Path resolved = validator(List.of()).validateDirectory(tmp.toString());
        assertEquals(tmp.toRealPath(), resolved);
    }

    @Test
    void emptyRoots_failClosed_rejectsEverything(@TempDir Path tmp) {
        // With require-allowed-roots enabled, an empty allow-list denies all.
        assertThrows(IllegalArgumentException.class,
                () -> failClosedValidator().validateDirectory(tmp.toString()));
    }

    @Test
    void insideAllowedRoot_isAccepted(@TempDir Path root) throws IOException {
        Path sub = Files.createDirectory(root.resolve("kb-source"));
        WikiSourcePathValidator v = validator(List.of(root.toString()));
        assertTrue(v.isAllowed(sub.toString()));
    }

    @Test
    void outsideAllowedRoot_isRejected(@TempDir Path root, @TempDir Path other) {
        WikiSourcePathValidator v = validator(List.of(root.toString()));
        assertFalse(v.isAllowed(other.toString()));
        assertThrows(IllegalArgumentException.class, () -> v.validateDirectory(other.toString()));
    }

    @Test
    void symlinkEscapingRoot_isRejected(@TempDir Path root, @TempDir Path secret) throws IOException {
        // A symlink inside the allowed root that points outside must be rejected
        // because validation resolves the real path first.
        Path link = root.resolve("escape");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — skip
        }
        WikiSourcePathValidator v = validator(List.of(root.toString()));
        assertFalse(v.isAllowed(link.toString()));
    }
}
