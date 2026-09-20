package vip.mate.tool.builtin;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RFC-051 §5.2: Apache Tika as the last-resort document extractor.
 * <p>
 * Used by {@link DocumentExtractTool} only after every other path
 * (pdftotext / pdfplumber / pdfbox / OCR for PDFs, and the system-command
 * + ZIP-XML chain for Office formats) has failed. Tika ships its own
 * PDFBox + POI internals, so it works on Windows installs without Python
 * or Poppler — which is the actual scenario the RFC §13.1 pointed to.
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>{@link BodyContentHandler} caps output at {@code maxChars}; when the
 *       cap is hit Tika throws {@link WriteLimitReachedException}, which we
 *       treat as a successful (truncated) extract rather than a failure.</li>
 *   <li>Tika 3.x has built-in zip-bomb defenses on its zip readers (POI's
 *       {@code ZipSecureFile}); we don't disable them.</li>
 *   <li>Any other parse failure returns {@code null} so the caller can fall
 *       through to its existing structured-error path.</li>
 * </ul>
 *
 * The extractor is deliberately stateless and synchronous: callers drive
 * concurrency externally.
 */
@Slf4j
public final class TikaExtractor {

    /**
     * Reasonable default for a single-document parse. 5MB of text is
     * well above any source we'd actually feed into the wiki pipeline,
     * and well below what would OOM a typical desktop install.
     */
    public static final int DEFAULT_MAX_CHARS = 5_000_000;

    private TikaExtractor() {}

    /** Extract with the default cap. */
    public static String extract(Path path) {
        return extract(path, DEFAULT_MAX_CHARS);
    }

    /**
     * Extract text from {@code path} using Tika's {@link AutoDetectParser},
     * capping output at {@code maxChars}. Returns the extracted text on
     * success (possibly truncated), or {@code null} on any failure.
     */
    public static String extract(Path path, int maxChars) {
        if (path == null) return null;
        if (!Files.isRegularFile(path)) {
            log.debug("[Tika] Path is not a regular file: {}", path);
            return null;
        }
        int cap = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;

        BodyContentHandler handler = new BodyContentHandler(cap) {
            @Override
            public void characters(char[] chars, int start, int length) throws SAXException {
                checkParseInterrupted();
                super.characters(chars, start, length);
            }

            @Override
            public void startElement(String uri, String localName, String name, Attributes attributes)
                    throws SAXException {
                checkParseInterrupted();
                super.startElement(uri, localName, name, attributes);
            }
        };
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try (InputStream is = new FilterInputStream(Files.newInputStream(path)) {
            @Override public int read() throws IOException {
                checkInterrupted();
                return super.read();
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                checkInterrupted();
                return super.read(bytes, offset, length);
            }
            @Override public long skip(long count) throws IOException {
                checkInterrupted();
                return super.skip(count);
            }
        }) {
            checkInterrupted();
            parser.parse(is, handler, metadata, context);
            return handler.toString();
        } catch (WriteLimitReachedException truncated) {
            // Cap hit — Tika filled the handler before parsing finished. The
            // partial text is still useful, especially since callers will chunk
            // anyway and only want the leading prose for routing/embedding.
            String partial = handler.toString();
            log.info("[Tika] Output cap reached at {} chars for {}; returning partial",
                    partial.length(), path.getFileName());
            return partial.isBlank() ? null : partial;
        } catch (Throwable t) {
            // Office parsers may wrap the SAX write-limit exception. A bounded
            // spreadsheet preview is still a successful extraction in that case.
            if (!Thread.currentThread().isInterrupted() && WriteLimitReachedException.isWriteLimitReached(t)) {
                String partial = handler.toString();
                return partial.isBlank() ? null : partial;
            }
            // Catching Throwable on purpose: Tika can throw NoClassDefFoundError /
            // LinkageError when an obscure transitive parser is missing on a
            // minimal classpath, and that should not crash the extract chain.
            log.warn("[Tika] Parse failed for {}: {}", path.getFileName(), t.getMessage());
            return null;
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Document extraction interrupted");
        }
    }

    private static void checkParseInterrupted() throws SAXException {
        try {
            checkInterrupted();
        } catch (InterruptedIOException e) {
            throw new SAXException(e);
        }
    }
}
