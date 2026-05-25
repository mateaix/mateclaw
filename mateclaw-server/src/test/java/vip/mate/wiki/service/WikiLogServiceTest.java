package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * RFC-051 PR-2c: pin appendBullet + trimOldest behavior.
 */
class WikiLogServiceTest {

    private WikiLogService svc;

    @BeforeEach
    void setUp() {
        svc = new WikiLogService(
                mock(WikiPageService.class),
                mock(vip.mate.wiki.repository.WikiPageMapper.class),
                mock(WikiScaffoldService.class));
    }

    @Test
    @DisplayName("creates a new dated section when none matches today")
    void newSectionCreated() {
        String result = svc.appendBullet("# Log\n", "2026-04-25", "ingest", "- 09:00 — first ingest");
        assertTrue(result.contains("## 2026-04-25 ingest"));
        assertTrue(result.contains("- 09:00 — first ingest"));
    }

    @Test
    @DisplayName("appends to existing section without duplicating the heading")
    void appendsToExistingSection() {
        String content = """
                # Log

                ## 2026-04-25 ingest

                - 09:00 — first ingest
                """;
        String result = svc.appendBullet(content, "2026-04-25", "ingest", "- 10:00 — second ingest");
        // Heading appears exactly once.
        assertEquals(1, result.split("## 2026-04-25 ingest", -1).length - 1);
        assertTrue(result.contains("- 09:00 — first ingest"));
        assertTrue(result.contains("- 10:00 — second ingest"));
    }

    @Test
    @DisplayName("new section is inserted at the top so newest activity reads first")
    void newSectionAtTop() {
        String content = """
                # Log

                ## 2026-04-20 ingest

                - 12:00 — old
                """;
        String result = svc.appendBullet(content, "2026-04-25", "compile", "- 11:00 — new");
        int posNew = result.indexOf("2026-04-25 compile");
        int posOld = result.indexOf("2026-04-20 ingest");
        assertTrue(posNew < posOld, "new section must appear before old section");
    }

    @Test
    @DisplayName("trimOldest drops sections from the bottom until under cap")
    void trimOldest() {
        StringBuilder big = new StringBuilder("# Log\n\n");
        for (int i = 0; i < 50; i++) {
            big.append("## 2026-04-").append(String.format("%02d", i)).append(" ingest\n\n");
            big.append("- 12:00 — line ").append(i).append("\n\n");
        }
        String trimmed = svc.trimOldest(big.toString(), 200);
        assertTrue(trimmed.length() <= 200 + 100, "should be roughly under the cap");
        // Newer dates near the top remain, older dates dropped.
        assertTrue(trimmed.contains("2026-04-00") || trimmed.contains("2026-04-01"),
                "earliest sections (top of doc under prepend strategy) should survive");
    }
}
