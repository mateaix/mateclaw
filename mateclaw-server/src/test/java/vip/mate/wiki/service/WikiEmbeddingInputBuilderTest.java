package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiChunkEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WikiEmbeddingInputBuilder}.
 *
 * <p>The builder is the single place where chunk metadata is folded into the
 * model input. The behaviors under test cover the four shapes downstream
 * embeddings will see: full metadata, partial metadata, no metadata, and the
 * prefix-only variant used by the long-chunk path.
 */
class WikiEmbeddingInputBuilderTest {

    private final WikiEmbeddingInputBuilder builder = new WikiEmbeddingInputBuilder();

    @Test
    @DisplayName("build: full metadata wraps content with labeled prefix")
    void build_fullMetadata_wrapsContent() {
        WikiChunkEntity chunk = chunk("Vector search beats keyword for paraphrased queries.",
                42L, "Intro / Retrieval / Vector", "Vector", 7);
        RawTitleLookup lookup = id -> id.equals(42L) ? "RAG Survey" : null;

        String out = builder.build(chunk, lookup);

        assertThat(out).isEqualTo(
                "Source: RAG Survey\n"
                        + "Section: Intro / Retrieval / Vector\n"
                        + "Subsection: Vector\n"
                        + "Page: 7\n"
                        + "\n"
                        + "Vector search beats keyword for paraphrased queries.");
    }

    @Test
    @DisplayName("build: blank metadata fields are skipped, no stray header lines")
    void build_blankFieldsSkipped() {
        WikiChunkEntity chunk = chunk("body", 1L, null, "  ", null);

        String out = builder.build(chunk, id -> "");

        // Source title is blank, breadcrumb is null, sourceSection is whitespace,
        // pageNumber is null — the builder should produce just the body.
        assertThat(out).isEqualTo("body");
    }

    @Test
    @DisplayName("build: null lookup tolerated, falls back to body")
    void build_nullLookup_fallsBack() {
        WikiChunkEntity chunk = chunk("body", 1L, null, null, null);

        assertThat(builder.build(chunk, null)).isEqualTo("body");
    }

    @Test
    @DisplayName("build: null chunk returns empty string")
    void build_nullChunk_returnsEmpty() {
        assertThat(builder.build(null, id -> "ignored")).isEmpty();
    }

    @Test
    @DisplayName("buildPrefix: returns metadata block ending in blank line, content excluded")
    void buildPrefix_returnsMetadataOnly() {
        WikiChunkEntity chunk = chunk("body that should not appear",
                10L, "Intro", null, 3);
        RawTitleLookup lookup = RawTitleLookups.of(Map.of(10L, "Manual"));

        String prefix = builder.buildPrefix(chunk, lookup);

        assertThat(prefix).isEqualTo(
                "Source: Manual\n"
                        + "Section: Intro\n"
                        + "Page: 3\n"
                        + "\n");
        assertThat(prefix).doesNotContain("body that should not appear");
    }

    @Test
    @DisplayName("buildPrefix: empty when no metadata available")
    void buildPrefix_noMetadata_returnsEmpty() {
        WikiChunkEntity chunk = chunk("body", null, null, null, null);

        assertThat(builder.buildPrefix(chunk, RawTitleLookups.empty())).isEmpty();
    }

    @Test
    @DisplayName("currentVersion exposes the static constant")
    void currentVersion_matchesConstant() {
        assertThat(builder.currentVersion()).isEqualTo(WikiEmbeddingInputBuilder.CURRENT_INPUT_VERSION);
    }

    private static WikiChunkEntity chunk(String content, Long rawId,
                                          String breadcrumb, String sourceSection, Integer page) {
        WikiChunkEntity c = new WikiChunkEntity();
        c.setContent(content);
        c.setRawId(rawId);
        c.setHeaderBreadcrumb(breadcrumb);
        c.setSourceSection(sourceSection);
        c.setPageNumber(page);
        return c;
    }
}
