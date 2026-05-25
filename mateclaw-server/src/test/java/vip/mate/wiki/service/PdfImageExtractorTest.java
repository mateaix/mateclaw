package vip.mate.wiki.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.tool.image.vision.ImageVisionService;
import vip.mate.tool.image.vision.VisionRequest;
import vip.mate.tool.image.vision.VisionResult;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PdfImageExtractor}.
 *
 * <p>Each test programmatically constructs a small PDF in a JUnit
 * {@link TempDir} so the harness has no external fixture dependencies
 * and the failure modes (missing file, sub-threshold image, vision
 * exception) all stay in-process.
 */
class PdfImageExtractorTest {

    @Test
    @DisplayName("Single inline image at 200×200 produces one marker")
    void singleInlineImage_emitsMarker(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("one-image.pdf");
        writePdfWithImages(pdf, new int[][]{{200, 200}});

        ImageVisionService vision = mock(ImageVisionService.class);
        when(vision.caption(any())).thenReturn(VisionResult.builder()
                .caption("a 200 by 200 red square")
                .providerId("test").model("test").capturedAt(Instant.now()).build());

        PdfImageExtractor extractor = new PdfImageExtractor(vision);
        List<String> snippets = extractor.captionInlineImages(pdf);

        assertThat(snippets).hasSize(1);
        assertThat(snippets.get(0)).startsWith("[图 P1#1]: ").contains("red square");
        verify(vision, atLeastOnce()).caption(any(VisionRequest.class));
    }

    @Test
    @DisplayName("Sub-threshold image (50x50) is filtered before vision is invoked")
    void subThresholdImage_filtered(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("tiny.pdf");
        writePdfWithImages(pdf, new int[][]{{50, 50}});

        ImageVisionService vision = mock(ImageVisionService.class);
        PdfImageExtractor extractor = new PdfImageExtractor(vision);

        List<String> snippets = extractor.captionInlineImages(pdf);

        assertThat(snippets).isEmpty();
        verify(vision, never()).caption(any());
    }

    @Test
    @DisplayName("Mixed sizes: 100x100 passes, 80x100 fails (short side dominates)")
    void mixedSizes_filterByShortSide(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("mixed.pdf");
        writePdfWithImages(pdf, new int[][]{{100, 100}, {80, 100}});

        ImageVisionService vision = mock(ImageVisionService.class);
        when(vision.caption(any())).thenReturn(VisionResult.builder()
                .caption("a square").providerId("t").model("t").capturedAt(Instant.now()).build());

        PdfImageExtractor extractor = new PdfImageExtractor(vision);
        List<String> snippets = extractor.captionInlineImages(pdf);

        assertThat(snippets).hasSize(1);
        verify(vision, times(1)).caption(any());
    }

    @Test
    @DisplayName("Vision exception on one image does not abort the rest")
    void visionException_skipsBadImage(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("two-images.pdf");
        writePdfWithImages(pdf, new int[][]{{120, 120}, {130, 130}});

        ImageVisionService vision = mock(ImageVisionService.class);
        // Throw on the first call, succeed on the second.
        when(vision.caption(any()))
                .thenThrow(new RuntimeException("rate limited"))
                .thenReturn(VisionResult.builder()
                        .caption("good").providerId("t").model("t").capturedAt(Instant.now()).build());

        PdfImageExtractor extractor = new PdfImageExtractor(vision);
        List<String> snippets = extractor.captionInlineImages(pdf);

        assertThat(snippets).hasSize(1);
        assertThat(snippets.get(0)).contains("good");
    }

    @Test
    @DisplayName("Missing file returns empty list without throwing")
    void missingFile_returnsEmpty() {
        ImageVisionService vision = mock(ImageVisionService.class);
        PdfImageExtractor extractor = new PdfImageExtractor(vision);

        List<String> snippets = extractor.captionInlineImages(Path.of("/nope/does/not/exist.pdf"));

        assertThat(snippets).isEmpty();
        verify(vision, never()).caption(any());
    }

    @Test
    @DisplayName("Null path returns empty list")
    void nullPath_returnsEmpty() {
        ImageVisionService vision = mock(ImageVisionService.class);
        PdfImageExtractor extractor = new PdfImageExtractor(vision);

        assertThat(extractor.captionInlineImages(null)).isEmpty();
    }

    @Test
    @DisplayName("Caption returning blank skips the snippet (no [图] marker emitted)")
    void blankCaption_skipped(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("blank-caption.pdf");
        writePdfWithImages(pdf, new int[][]{{150, 150}});

        ImageVisionService vision = mock(ImageVisionService.class);
        when(vision.caption(any())).thenReturn(VisionResult.builder()
                .caption("   ").providerId("t").model("t").capturedAt(Instant.now()).build());

        PdfImageExtractor extractor = new PdfImageExtractor(vision);
        List<String> snippets = extractor.captionInlineImages(pdf);

        assertThat(snippets).isEmpty();
    }

    // ==================== helpers ====================

    /**
     * Writes a PDF with one page per image. Each image is a solid-color
     * rectangle of the requested dimensions in pixels (drawn via PDFBox's
     * {@code LosslessFactory}, which produces a real {@code PDImageXObject}
     * the extractor will discover via {@code resources.getXObjectNames()}).
     */
    private static void writePdfWithImages(Path output, int[][] sizes) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            int colorSeed = 0;
            for (int[] size : sizes) {
                int w = size[0];
                int h = size[1];
                BufferedImage bi = solidColorImage(w, h, new Color(200, 60 + colorSeed, 60 + colorSeed));
                colorSeed = (colorSeed + 30) % 200;

                PDPage page = new PDPage();
                doc.addPage(page);
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, bi);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(pdImage, 50, 50, w, h);
                }
            }
            doc.save(output.toFile());
        }
    }

    private static BufferedImage solidColorImage(int w, int h, Color color) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, color.getRGB());
            }
        }
        return bi;
    }
}
