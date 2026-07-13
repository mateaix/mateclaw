package vip.mate.tool.document.preview;

import org.junit.jupiter.api.Test;
import vip.mate.tool.document.pdf.PdfProperties;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OfficePreviewService} covering the pure decision logic
 * (convertible-extension gate, availability gate, cache freshness) without
 * requiring a real {@code soffice} install on the build host.
 */
class OfficePreviewServiceTest {

    private OfficePreviewService serviceWith(boolean enabled, String binary) {
        PdfProperties props = new PdfProperties(null, null, new PdfProperties.Libreoffice(enabled, binary));
        return new OfficePreviewService(props);
    }

    @Test
    void isConvertible_acceptsOfficeExtensions_caseInsensitive() {
        OfficePreviewService svc = serviceWith(true, "soffice");
        assertThat(svc.isConvertible("deck.pptx")).isTrue();
        assertThat(svc.isConvertible("REPORT.PPT")).isTrue();
        assertThat(svc.isConvertible("legacy.doc")).isTrue();
        assertThat(svc.isConvertible("book.xls")).isTrue();
        assertThat(svc.isConvertible("notes.odt")).isTrue();
    }

    @Test
    void isConvertible_rejectsFrontendRenderedAndUnknownFormats() {
        OfficePreviewService svc = serviceWith(true, "soffice");
        // These are handled client-side and must never hit the converter.
        assertThat(svc.isConvertible("a.pdf")).isFalse();
        assertThat(svc.isConvertible("a.docx")).isFalse();
        assertThat(svc.isConvertible("a.xlsx")).isFalse();
        assertThat(svc.isConvertible("a.html")).isFalse();
        assertThat(svc.isConvertible("a.png")).isFalse();
        assertThat(svc.isConvertible("noext")).isFalse();
    }

    @Test
    void isAvailable_falseWhenDisabled() {
        // Disabled short-circuits before any process spawn.
        OfficePreviewService svc = serviceWith(false, "soffice");
        assertThat(svc.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_falseWhenBinaryMissing() {
        // A binary that cannot be exec'd probes to unavailable, not an exception.
        OfficePreviewService svc = serviceWith(true, "definitely-not-a-real-soffice-binary-xyz");
        assertThat(svc.isAvailable()).isFalse();
    }

    @Test
    void renderPdf_servesFreshCacheWithoutInvokingConverter() throws Exception {
        // A cached PDF newer than the source must be returned verbatim, proving
        // the cache short-circuits before any soffice call (host has none).
        Path dir = Files.createTempDirectory("mc_preview_test_");
        try {
            Path source = dir.resolve("deck.pptx");
            Files.write(source, new byte[]{1, 2, 3});

            Path cacheDir = dir.resolve(OfficePreviewService.PREVIEW_DIR);
            Files.createDirectories(cacheDir);
            Path cached = cacheDir.resolve("deck.pptx.pdf");
            byte[] cachedPdf = "%PDF-1.4 cached".getBytes();
            Files.write(cached, cachedPdf);
            // Ensure the cache is at least as new as the source.
            Files.setLastModifiedTime(cached, Files.getLastModifiedTime(source));

            OfficePreviewService svc = serviceWith(true, "definitely-not-a-real-soffice-binary-xyz");
            assertThat(svc.renderPdf(source)).isEqualTo(cachedPdf);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
    }
}
