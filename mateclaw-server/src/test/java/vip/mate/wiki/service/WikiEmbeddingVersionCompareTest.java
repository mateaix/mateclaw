package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the comparison logic that drives the startup self-check for the
 * embedding input version. Numeric "vN" tags should order naturally (v2 &lt;
 * v10), and the fallback to case-insensitive string compare should kick in
 * for anything else without throwing.
 */
class WikiEmbeddingVersionCompareTest {

    @Test
    @DisplayName("compareInputVersions: numeric tags compare numerically (v2 < v10)")
    void numericOrdering() {
        assertThat(WikiEmbeddingService.compareInputVersions("v2", "v10")).isNegative();
        assertThat(WikiEmbeddingService.compareInputVersions("v10", "v2")).isPositive();
        assertThat(WikiEmbeddingService.compareInputVersions("v3", "v3")).isZero();
        assertThat(WikiEmbeddingService.compareInputVersions("V1", "v1")).isZero();
    }

    @Test
    @DisplayName("compareInputVersions: non-numeric tags fall back to string compare")
    void stringFallback() {
        assertThat(WikiEmbeddingService.compareInputVersions("alpha", "beta")).isNegative();
        assertThat(WikiEmbeddingService.compareInputVersions("beta", "alpha")).isPositive();
        assertThat(WikiEmbeddingService.compareInputVersions("preview", "preview")).isZero();
    }

    @Test
    @DisplayName("compareInputVersions: mixed numeric / non-numeric does not throw")
    void mixedTags() {
        // We don't assert sign — only that the comparator stays total.
        WikiEmbeddingService.compareInputVersions("v1", "alpha");
        WikiEmbeddingService.compareInputVersions("alpha", "v1");
    }
}
