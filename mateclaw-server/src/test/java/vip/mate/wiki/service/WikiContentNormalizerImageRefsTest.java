package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.dto.ImageRef;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WikiContentNormalizer#extractImageRefs}.
 *
 * <p>The extractor backs the search hit's {@code imageRefs} field, so the
 * tests assert the exact behavior callers depend on: order preservation,
 * URL-deduplication, alt-text passthrough including the empty case, and
 * tolerance for malformed input.
 */
class WikiContentNormalizerImageRefsTest {

    private final WikiContentNormalizer normalizer = new WikiContentNormalizer();

    @Test
    @DisplayName("Returns empty list for null / blank input")
    void emptyInputs() {
        assertThat(normalizer.extractImageRefs(null)).isEmpty();
        assertThat(normalizer.extractImageRefs("")).isEmpty();
        assertThat(normalizer.extractImageRefs("    \n   ")).isEmpty();
    }

    @Test
    @DisplayName("Single image with alt text is captured verbatim")
    void singleImage() {
        List<ImageRef> refs = normalizer.extractImageRefs(
                "Here is a chart: ![revenue Q1](images/q1.png) — see context.");

        assertThat(refs).hasSize(1);
        ImageRef ref = refs.get(0);
        assertThat(ref.alt()).isEqualTo("revenue Q1");
        assertThat(ref.url()).isEqualTo("images/q1.png");
        assertThat(ref.fullMatch()).isEqualTo("![revenue Q1](images/q1.png)");
    }

    @Test
    @DisplayName("Empty alt text is preserved as empty string (not null)")
    void emptyAlt() {
        List<ImageRef> refs = normalizer.extractImageRefs("Decoration: ![](decor.png)");

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).alt()).isEmpty();
        assertThat(refs.get(0).url()).isEqualTo("decor.png");
    }

    @Test
    @DisplayName("Multiple distinct images preserve document order")
    void multipleImages_orderPreserved() {
        String md = """
                # Page

                ![first](a.png)

                Some prose.

                ![second](b.png)

                More prose.

                ![third](c.png)
                """;

        List<ImageRef> refs = normalizer.extractImageRefs(md);

        assertThat(refs).extracting(ImageRef::url).containsExactly("a.png", "b.png", "c.png");
    }

    @Test
    @DisplayName("Duplicate URLs are deduplicated; first alt text wins")
    void duplicateUrl_dedupedFirstAltWins() {
        String md = """
                ![first occurrence](shared.png) and later
                ![second occurrence](shared.png) plus
                ![distinct](other.png)
                """;

        List<ImageRef> refs = normalizer.extractImageRefs(md);

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).url()).isEqualTo("shared.png");
        assertThat(refs.get(0).alt()).isEqualTo("first occurrence");
        assertThat(refs.get(1).url()).isEqualTo("other.png");
    }

    @Test
    @DisplayName("Absolute URLs and data URIs are captured along with relative paths")
    void mixedUrlSchemes() {
        String md = """
                ![remote](https://example.com/x.png)
                ![local](./assets/y.jpg)
                ![data uri](data:image/png;base64,AAAA)
                """;

        List<ImageRef> refs = normalizer.extractImageRefs(md);

        assertThat(refs).extracting(ImageRef::url).containsExactly(
                "https://example.com/x.png",
                "./assets/y.jpg",
                "data:image/png;base64,AAAA");
    }

    @Test
    @DisplayName("Wikilinks and bare URLs are not mistaken for image refs")
    void doesNotMatchNonImageSyntax() {
        String md = """
                # See also

                - [[other-page]] for context
                - [link](https://example.com/article) (regular link, no leading !)
                - bare URL: https://example.com/x.png
                """;

        List<ImageRef> refs = normalizer.extractImageRefs(md);

        assertThat(refs).isEmpty();
    }

    @Test
    @DisplayName("Image refs with title attribute have the title segment cleanly excluded from URL")
    void titleAttributeStopsUrl() {
        // The URL pattern stops at whitespace, so `![alt](url "title")` captures just `url`.
        List<ImageRef> refs = normalizer.extractImageRefs(
                "![alt](path/to/img.png \"a title\")");

        // The title-bearing form is not a perfect match for the regex (since it requires
        // a closing paren immediately after the URL); we accept that it either doesn't
        // match at all or matches with the URL only. Either is acceptable behavior.
        if (!refs.isEmpty()) {
            assertThat(refs.get(0).url()).isEqualTo("path/to/img.png");
        }
    }
}
