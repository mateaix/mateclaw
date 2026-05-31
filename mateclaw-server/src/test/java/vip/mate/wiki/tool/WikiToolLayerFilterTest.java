package vip.mate.wiki.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the retrieval knowledge-layer filter ({@link WikiTool#matchesLayer}).
 */
class WikiToolLayerFilterTest {

    @Test
    void noFilterOrAll_matchesEverything() {
        assertTrue(WikiTool.matchesLayer("experience", null));
        assertTrue(WikiTool.matchesLayer("experience", ""));
        assertTrue(WikiTool.matchesLayer("experience", "all"));
        assertTrue(WikiTool.matchesLayer(null, "all"));
    }

    @Test
    void factFilter_includesUnlayeredPages() {
        assertTrue(WikiTool.matchesLayer(null, "fact"));     // legacy / unlayered counts as fact
        assertTrue(WikiTool.matchesLayer("", "fact"));
        assertTrue(WikiTool.matchesLayer("fact", "fact"));
        assertFalse(WikiTool.matchesLayer("experience", "fact"));
    }

    @Test
    void experienceFilter_excludesFactAndUnlayered() {
        assertTrue(WikiTool.matchesLayer("experience", "experience"));
        assertFalse(WikiTool.matchesLayer("fact", "experience"));
        assertFalse(WikiTool.matchesLayer(null, "experience"));
    }

    @Test
    void caseInsensitive() {
        assertTrue(WikiTool.matchesLayer("Experience", "EXPERIENCE"));
        assertTrue(WikiTool.matchesLayer("FACT", "fact"));
    }
}
