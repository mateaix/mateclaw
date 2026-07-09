package vip.mate.memory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the {@code mate_memory_recall.filename} VARCHAR(256) ceiling against
 * over-long section keys (file path + '#' + a long H2 heading slug). See #461.
 * <p>
 * Two boundaries are covered as pure functions, no Spring context needed:
 * <ul>
 *   <li>{@link MemoryRecallTracker#sanitizeSectionKey} — slug-side cap</li>
 *   <li>{@link MemoryRecallService#truncateFilename} — write-side cap</li>
 * </ul>
 */
class MemoryRecallFilenameTruncationTest {

    /** Repeated CJK filler so a heading can be grown past any threshold. */
    private static final String CN = "用户要求设置每日财经早报定时任务，每天早上推送汇总报告到指定群组";

    @Nested
    @DisplayName("sanitizeSectionKey — slug-side cap")
    class SanitizeSectionKey {

        @Test
        @DisplayName("normal CJK heading is slugified untouched (no false truncation)")
        void normalCjkHeadingPreserved() {
            String slug = MemoryRecallTracker.sanitizeSectionKey("## 08:30 用户设置每日财经早报定时任务");
            // "## " stripped, ':' and spaces → '-', CJK kept; "08-30-用户设置每日财经早报定时任务"
            assertEquals("08-30-用户设置每日财经早报定时任务", slug);
            assertTrue(slug.length() <= MemoryRecallTracker.MAX_SECTION_SLUG);
        }

        @Test
        @DisplayName("over-long CJK heading slug is capped at MAX_SECTION_SLUG and never throws")
        void overLongCjkHeadingCapped() {
            StringBuilder heading = new StringBuilder("## ");
            while (heading.length() < MemoryRecallTracker.MAX_SECTION_SLUG + 200) {
                heading.append(CN);
            }
            String slug = assertDoesNotThrow(() -> MemoryRecallTracker.sanitizeSectionKey(heading.toString()));
            assertTrue(slug.length() <= MemoryRecallTracker.MAX_SECTION_SLUG,
                    "slug must not exceed MAX_SECTION_SLUG, was " + slug.length());
        }

        @Test
        @DisplayName("ascii-only heading collapses runs of non-alnum to a single '-'")
        void asciiHeadingSlugified() {
            assertEquals("some-title-here", MemoryRecallTracker.sanitizeSectionKey("## Some Title Here"));
        }
    }

    @Nested
    @DisplayName("truncateFilename — write-side cap")
    class TruncateFilename {

        @Test
        @DisplayName("filename at/below the cap is returned unchanged")
        void underCapUnchanged() {
            String filename = "memory/2026-06-05.md#08-30-用户设置每日财经早报定时任务";
            assertTrue(filename.length() <= MemoryRecallService.MAX_FILENAME_LENGTH);
            assertSame(filename, MemoryRecallService.truncateFilename(filename),
                    "under-cap values must pass through without copying");
        }

        @Test
        @DisplayName("filename over the cap is truncated to MAX_FILENAME_LENGTH")
        void overCapTruncated() {
            StringBuilder filename = new StringBuilder("memory/2026-06-05.md#");
            while (filename.length() < MemoryRecallService.MAX_FILENAME_LENGTH + 100) {
                filename.append(CN);
            }
            String out = MemoryRecallService.truncateFilename(filename.toString());
            assertEquals(MemoryRecallService.MAX_FILENAME_LENGTH, out.length(),
                    "over-cap value must be exactly MAX_FILENAME_LENGTH");
        }

        @Test
        @DisplayName("truncation keeps the date prefix intact (computeFreshness still parses it)")
        void datePrefixPreserved() {
            StringBuilder filename = new StringBuilder("memory/2026-06-05.md#");
            while (filename.length() < MemoryRecallService.MAX_FILENAME_LENGTH + 100) {
                filename.append(CN);
            }
            String out = MemoryRecallService.truncateFilename(filename.toString());
            // The leading path/date — the only part computeFreshness uses — survives.
            assertTrue(out.startsWith("memory/2026-06-05.md"), "date prefix must survive truncation");
            int hash = out.indexOf('#');
            assertTrue(hash > 0 && hash < out.length(), "section anchor must still be present");
        }
    }

    @Test
    @DisplayName("full daily-note section key stays under the DB column ceiling end-to-end")
    void endToEndWithinColumnCeiling() {
        // Reproduce MemoryRecallTracker's key assembly on an over-long heading.
        String dailyFile = "memory/2026-06-05.md";
        StringBuilder heading = new StringBuilder("## 08:30 ");
        while (heading.length() < MemoryRecallTracker.MAX_SECTION_SLUG + 300) {
            heading.append(CN);
        }
        String sectionKey = dailyFile + "#" + MemoryRecallTracker.sanitizeSectionKey(heading.toString());
        // Even after the write-side fallback, the stored value must fit VARCHAR(256).
        String stored = MemoryRecallService.truncateFilename(sectionKey);
        assertTrue(stored.length() <= 255,
                "stored filename must fit VARCHAR(256), was " + stored.length());
    }
}
