package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the extraction-quality classifier in {@link DocumentExtractTool}.
 *
 * <p>The decisive cases:
 * <ul>
 *   <li>CJK font encoding leak — many "characters", almost all junk → OCR.</li>
 *   <li>Scanned PDF with empty text layer → OCR.</li>
 *   <li>Mixed CN/EN body with realistic OCR noise → stays out of OCR.</li>
 *   <li>Pure ASCII body → stays out of OCR.</li>
 * </ul>
 */
class DocumentExtractToolReadableRatioTest {

    /**
     * Sample of the byte pattern observed when a PDF uses CID fonts without a
     * {@code ToUnicode} CMap and the extractor dumps glyph indices as bytes.
     * Mixes C0 control bytes, the C1 / Latin-1 Supplement block, and the
     * tail "(¢" pair that dominated the real incident's extraction —
     * a typical 8-page CID-encoded PDF lands here under 0.40 readable.
     * Written with explicit escapes so the source file stays pure ASCII.
     */
    private static final String CID_GLYPH_NOISE =
            ""
                    + "Ç£¨±Ð¼½"
                    + "Ò®º¶¡¥æ"
                    + "òÙçÄÚÊÅ"
                    + "(¢(¢(¢(¢(¢";

    @Test
    @DisplayName("readableRatio: pure CJK text scores near 1.0")
    void readableRatio_pureCjk_high() {
        String text = "向量检索在自然语言处"
                + "理中扮演重要角色。";
        assertThat(DocumentExtractTool.readableRatio(text)).isGreaterThan(0.95);
    }

    @Test
    @DisplayName("readableRatio: pure English text scores near 1.0")
    void readableRatio_pureAscii_high() {
        String text = "Vector retrieval improves recall on paraphrased queries by 18% over BM25.";
        assertThat(DocumentExtractTool.readableRatio(text)).isGreaterThan(0.95);
    }

    @Test
    @DisplayName("readableRatio: mixed Chinese / English / punctuation scores near 1.0")
    void readableRatio_mixed_high() {
        String text = "评估章节：accuracy 提升 12%"
                + "，latency 增加 ~15ms。详见 §3.2。";
        // § (section sign) is not in our readable ranges, so the mixed
        // string lands just under "near-1.0" but still well above the threshold.
        assertThat(DocumentExtractTool.readableRatio(text)).isGreaterThan(0.85);
    }

    @Test
    @DisplayName("readableRatio: CID glyph dump (PDFBox leak) scores well below 0.5")
    void readableRatio_cidGlyphDump_low() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append(CID_GLYPH_NOISE);
        }
        assertThat(DocumentExtractTool.readableRatio(sb.toString())).isLessThan(0.40);
    }

    @Test
    @DisplayName("readableRatio: empty / null inputs return 0")
    void readableRatio_emptyOrNull_zero() {
        assertThat(DocumentExtractTool.readableRatio(null)).isZero();
        assertThat(DocumentExtractTool.readableRatio("")).isZero();
    }

    @Test
    @DisplayName("classifyExtraction: CID glyph dump triggers low_readable_ratio")
    void classify_cidGlyphDump_triggersReadableRatio() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append(CID_GLYPH_NOISE);
        }
        DocumentExtractTool.ExtractionQuality q =
                DocumentExtractTool.classifyExtraction(sb.toString(), 8);
        assertThat(q.needsOcr()).isTrue();
        assertThat(q.trigger()).isEqualTo("low_readable_ratio");
        assertThat(q.readableRatio()).isLessThan(0.40);
    }

    @Test
    @DisplayName("classifyExtraction: empty text triggers empty")
    void classify_empty_triggersEmpty() {
        DocumentExtractTool.ExtractionQuality q =
                DocumentExtractTool.classifyExtraction("", 5);
        assertThat(q.needsOcr()).isTrue();
        assertThat(q.trigger()).isEqualTo("empty");
    }

    @Test
    @DisplayName("classifyExtraction: text under 20 chars triggers too_short")
    void classify_tooShort_triggersTooShort() {
        DocumentExtractTool.ExtractionQuality q =
                DocumentExtractTool.classifyExtraction("hi", 5);
        assertThat(q.needsOcr()).isTrue();
        assertThat(q.trigger()).isEqualTo("too_short");
    }

    @Test
    @DisplayName("classifyExtraction: thin scanned-PDF text layer triggers low_char_density")
    void classify_thinScannedLayer_triggersDensity() {
        // 8 pages with only ~13 chars per page: well past the 20-char min so it
        // doesn't short-circuit on too_short, but well under the 30-chars-per-page floor.
        String pageMarker = "Title page X\n";  // 13 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(pageMarker);
        }
        DocumentExtractTool.ExtractionQuality q =
                DocumentExtractTool.classifyExtraction(sb.toString(), 8);
        assertThat(q.needsOcr()).isTrue();
        assertThat(q.trigger()).isEqualTo("low_char_density");
    }

    @Test
    @DisplayName("classifyExtraction: real CJK body passes")
    void classify_realCjkBody_passes() {
        String line = "北京赛区竞赛安排"
                + "：报名截止时间 2026.\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append(line);
        }
        DocumentExtractTool.ExtractionQuality q =
                DocumentExtractTool.classifyExtraction(sb.toString(), 8);
        assertThat(q.needsOcr()).isFalse();
        assertThat(q.trigger()).isNull();
    }

    @Test
    @DisplayName("classifyExtraction: real English body passes")
    void classify_realAsciiBody_passes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("Vector retrieval improves recall on paraphrased queries.\n");
        }
        DocumentExtractTool.ExtractionQuality q =
                DocumentExtractTool.classifyExtraction(sb.toString(), 8);
        assertThat(q.needsOcr()).isFalse();
    }

    @Test
    @DisplayName("classifyExtraction: noisy OCR output (low-quality but readable) passes")
    void classify_noisyOcrOutput_passes() {
        // Simulates OCR result with the occasional non-Latin garbage char sprinkled
        // in real text. Θ (Greek capital theta) is outside our readable ranges.
        String segment = "第 X 题：a/Θ求最大子"
                + "序列和?";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append(segment);
        }
        DocumentExtractTool.ExtractionQuality q =
                DocumentExtractTool.classifyExtraction(sb.toString(), 4);
        assertThat(q.needsOcr()).isFalse();
    }

    @Test
    @DisplayName("classifyExtraction: unknown page count falls back to absolute-length check")
    void classify_unknownPageCount_usesLengthFallback() {
        String short_ = "二十一个字符的中"
                + "文示例文本输入";
        DocumentExtractTool.ExtractionQuality shortQ =
                DocumentExtractTool.classifyExtraction(short_, 0);
        assertThat(shortQ.needsOcr()).isTrue();
        assertThat(shortQ.trigger()).isEqualTo("too_short");

        String line = "足够长的中文示例文本"
                + "一二三四五六七八九十。";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append(line);
        }
        DocumentExtractTool.ExtractionQuality longQ =
                DocumentExtractTool.classifyExtraction(sb.toString(), 0);
        assertThat(longQ.needsOcr()).isFalse();
    }
}
