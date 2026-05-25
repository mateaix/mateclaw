package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-051 §5.2: pin TikaExtractor's safety guarantees.
 * <p>
 * The actual format-specific extraction quality (PDF, DOCX, etc.) is verified
 * by manual testing against real documents — these unit tests only lock down
 * the wrapper's contract: null-handling, missing files, and the BodyContentHandler
 * output cap.
 */
class TikaExtractorTest {

    @Test
    @DisplayName("null path returns null without throwing")
    void nullPath() {
        assertNull(TikaExtractor.extract(null));
    }

    @Test
    @DisplayName("non-existent path returns null without throwing")
    void missingFile(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.txt");
        assertNull(TikaExtractor.extract(missing));
    }

    @Test
    @DisplayName("directory (non-regular file) returns null")
    void directoryRejected(@TempDir Path tmp) {
        assertNull(TikaExtractor.extract(tmp));
    }

    @Test
    @DisplayName("plain text file is extracted verbatim under the cap")
    void plainTextRoundTrip(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("note.txt");
        Files.writeString(file, "hello world");
        String out = TikaExtractor.extract(file);
        assertNotNull(out);
        assertTrue(out.contains("hello world"), "Extracted text should contain the original content. Got: " + out);
    }

    @Test
    @DisplayName("output is capped at maxChars; truncated parse still returns useful prefix")
    void outputCapped(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("long.txt");
        // Build a file well above the cap so Tika hits the limit mid-parse.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) sb.append("Lorem ipsum dolor sit amet. ");
        Files.writeString(file, sb.toString());

        // Cap at 100 chars; we expect a non-null, capped output.
        String out = TikaExtractor.extract(file, 100);
        assertNotNull(out, "should return partial text when cap reached, not null");
        assertTrue(out.length() <= 200, "should respect cap (some whitespace slack OK). Got len=" + out.length());
        assertTrue(out.contains("Lorem"), "partial output should still contain the leading text");
    }
}
