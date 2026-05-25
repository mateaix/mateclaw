package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * RFC-051 PR-2b: pin the marker-region splice algorithm.
 * <p>
 * The whole point of the markers is to keep user prose outside them
 * untouched while replacing the auto-generated stats inside. These tests
 * lock down the boundary cases where prior versions of similar code have
 * gone wrong: missing markers, scrambled order, no body.
 */
class WikiOverviewSpliceTest {

    private WikiOverviewService service;

    @BeforeEach
    void setUp() {
        // Heads-up: spliceMarkerRegion uses no Spring collaborators, so we can
        // construct with mocks and never actually touch them.
        service = new WikiOverviewService(
                mock(WikiPageService.class),
                mock(vip.mate.wiki.repository.WikiPageMapper.class),
                mock(vip.mate.wiki.repository.WikiRawMaterialMapper.class),
                mock(vip.mate.wiki.repository.WikiChunkMapper.class),
                mock(WikiScaffoldService.class));
    }

    @Test
    @DisplayName("replaces marker region, preserves prose outside")
    void replacesMarkerRegion() {
        String original = """
                # Overview

                <!-- mate:overview:v1:start -->
                old stats here
                <!-- mate:overview:v1:end -->

                ## Notes

                User prose that must survive.""";
        String result = service.spliceMarkerRegion(original, "fresh stats line\nsecond line");
        assertTrue(result.contains("fresh stats line"));
        assertFalse(result.contains("old stats here"));
        assertTrue(result.contains("User prose that must survive."));
    }

    @Test
    @DisplayName("synthesizes markers when content is empty")
    void emptyContentSynthesizesBoth() {
        String result = service.spliceMarkerRegion("", "stats");
        assertTrue(result.contains(WikiOverviewService.MARKER_START));
        assertTrue(result.contains(WikiOverviewService.MARKER_END));
        assertTrue(result.contains("stats"));
    }

    @Test
    @DisplayName("appends markers when missing from existing content")
    void missingMarkersAppend() {
        String original = "# Overview\n\nUser-written content with no markers.\n";
        String result = service.spliceMarkerRegion(original, "stats");
        // User prose preserved entirely.
        assertTrue(result.contains("User-written content with no markers."));
        assertTrue(result.contains(WikiOverviewService.MARKER_START));
        assertTrue(result.contains(WikiOverviewService.MARKER_END));
        assertTrue(result.contains("stats"));
    }

    @Test
    @DisplayName("scrambled markers (end before start) treated as missing → append")
    void scrambledMarkersAppend() {
        String original = WikiOverviewService.MARKER_END + "\nbroken\n" + WikiOverviewService.MARKER_START;
        String result = service.spliceMarkerRegion(original, "stats");
        // Original kept verbatim; new region added at the end.
        assertTrue(result.startsWith(original.replace("\r\n", "\n")));
        assertTrue(result.contains("stats"));
    }

    @Test
    @DisplayName("idempotent: re-running with the same stats yields the same content")
    void idempotent() {
        String original = """
                # Overview

                <!-- mate:overview:v1:start -->
                old
                <!-- mate:overview:v1:end -->
                """;
        String once = service.spliceMarkerRegion(original, "stats line");
        String twice = service.spliceMarkerRegion(once, "stats line");
        assertEquals(once, twice);
    }
}
