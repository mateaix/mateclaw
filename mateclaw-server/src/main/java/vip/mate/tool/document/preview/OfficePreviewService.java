package vip.mate.tool.document.preview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.tool.document.pdf.PdfProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Converts office documents (pptx / ppt / doc / xls / odt / ods / odp / rtf / …)
 * to PDF for in-browser preview, using a {@code soffice --convert-to pdf}
 * subprocess. Formats that the frontend can render directly (pdf / docx / xlsx /
 * html / text) never reach this service — it is the fallback path for the ones
 * no client-side library covers.
 *
 * <p>Reuses the LibreOffice availability contract from {@link PdfProperties}:
 * when {@code soffice} is absent or disabled, {@link #isAvailable()} returns
 * false and the controller answers {@code 501}, letting the UI degrade to a
 * download link. No LibreOffice install is required for the rest of preview to
 * work.
 *
 * <p>Converted PDFs are cached next to the source under a hidden
 * {@code .preview/} directory, keyed by the source file's last-modified time,
 * so repeated opens of the same attachment convert only once. The cache lives
 * inside the conversation directory and is removed wholesale when the
 * conversation's attachments are cleaned up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfficePreviewService {

    /** Hidden sub-directory (per conversation dir) holding converted preview PDFs. */
    public static final String PREVIEW_DIR = ".preview";

    private static final long CONVERT_TIMEOUT_SECONDS = 90;

    /**
     * Extensions this service will convert. Kept in sync with the frontend
     * {@code OFFICE_CONVERT_EXTS} set in {@code previewKind.ts}. Formats the
     * browser renders natively (pdf/docx/xlsx/csv/html/text) are intentionally
     * excluded — they never hit this endpoint.
     */
    private static final Set<String> CONVERTIBLE_EXTS = Set.of(
            "ppt", "pptx", "doc", "xls", "odt", "ods", "odp", "rtf", "wps");

    private final PdfProperties properties;

    /** Whether a usable {@code soffice} binary is present (probed each call, cheap). */
    public boolean isAvailable() {
        if (!properties.libreoffice().enabled()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(properties.libreoffice().binary(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("[OfficePreview] soffice probe failed: {}", e.getMessage());
            return false;
        }
    }

    /** Whether {@code filename}'s extension is one this service can convert. */
    public boolean isConvertible(String filename) {
        return CONVERTIBLE_EXTS.contains(extensionOf(filename));
    }

    /**
     * Return the preview PDF bytes for {@code source}, converting via soffice on
     * a cache miss. Callers must have already verified {@link #isConvertible}
     * and {@link #isAvailable}.
     *
     * @param source an existing, readable office document
     * @return converted PDF bytes
     * @throws IOException conversion failed or produced no output
     */
    public byte[] renderPdf(Path source) throws IOException {
        Path cached = cachePathFor(source);
        if (isCacheFresh(cached, source)) {
            return Files.readAllBytes(cached);
        }
        byte[] pdf = convert(source);
        writeCache(cached, pdf);
        return pdf;
    }

    // ==================== internals ====================

    private Path cachePathFor(Path source) {
        Path dir = source.getParent().resolve(PREVIEW_DIR);
        return dir.resolve(source.getFileName().toString() + ".pdf");
    }

    private boolean isCacheFresh(Path cached, Path source) {
        try {
            if (!Files.isRegularFile(cached)) return false;
            // Fresh only when the cached PDF is at least as new as the source,
            // so a re-uploaded/overwritten source invalidates the stale preview.
            return Files.getLastModifiedTime(cached).toMillis()
                    >= Files.getLastModifiedTime(source).toMillis();
        } catch (IOException e) {
            return false;
        }
    }

    private void writeCache(Path cached, byte[] pdf) {
        try {
            Files.createDirectories(cached.getParent());
            Files.write(cached, pdf);
        } catch (IOException e) {
            // Non-fatal: a failed cache write just means the next open reconverts.
            log.debug("[OfficePreview] failed to cache preview {}: {}", cached, e.getMessage());
        }
    }

    private byte[] convert(Path source) throws IOException {
        Path tempDir = Files.createTempDirectory("mc_preview_");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    properties.libreoffice().binary(),
                    "--headless",
                    "--convert-to", "pdf",
                    "--outdir", tempDir.toString(),
                    source.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] stderr = p.getInputStream().readAllBytes();
            boolean finished;
            try {
                finished = p.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("soffice conversion interrupted", e);
            }
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("soffice conversion timed out after " + CONVERT_TIMEOUT_SECONDS + "s");
            }
            if (p.exitValue() != 0) {
                throw new IOException("soffice exit " + p.exitValue() + ": " + new String(stderr).strip());
            }
            // soffice names the output after the input basename, extension swapped to .pdf.
            String base = stripExtension(source.getFileName().toString());
            Path pdfFile = tempDir.resolve(base + ".pdf");
            if (!Files.isRegularFile(pdfFile)) {
                throw new IOException("soffice produced no PDF (stderr: " + new String(stderr).strip() + ")");
            }
            return Files.readAllBytes(pdfFile);
        } finally {
            cleanup(tempDir);
        }
    }

    private void cleanup(Path tempDir) {
        try (var stream = Files.walk(tempDir)) {
            List<Path> entries = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // Best-effort; the OS reclaims java.io.tmpdir on reboot.
                }
            }
        } catch (IOException ignored) {
            // ditto
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(0, idx) : filename;
    }
}
