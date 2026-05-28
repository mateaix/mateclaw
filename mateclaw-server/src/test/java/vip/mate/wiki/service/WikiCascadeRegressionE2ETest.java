package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;
import vip.mate.wiki.repository.WikiPageMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression suite for the cascade / scan write paths.
 *
 * <p>Boots the full Spring context with the H2 + Flyway test profile so the
 * V129 broken_links migration is in place and MyBatis-Plus's lambda cache
 * for {@link WikiPageEntity} is fully primed. That priming is what
 * distinguishes this suite from the existing mock-mapper tests in
 * {@link WikiPageServiceTest} — only the real Spring + MP wiring exposes
 * the {@code FieldStrategy.ALWAYS} + partial-entity-update interaction
 * that the §8 incident exposed.
 *
 * <p>Three classes of guard, one per case:
 * <ul>
 *   <li>Scan must leave {@code content} and {@code summary} byte-identical.</li>
 *   <li>Cascade delete must leave the referrer's {@code summary} byte-identical
 *       (and its {@code content} only modified by the wikilink demotion).</li>
 *   <li>Cascade rename must leave the referrer's {@code summary} byte-identical
 *       (and its {@code content} only modified by the wikilink target swap).</li>
 * </ul>
 *
 * <p>Plus a small portability check around the case-only rename path (R4-G).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WikiCascadeRegressionE2ETest {

    @Autowired private WikiPageService pageService;
    @Autowired private WikiLintJobService lintJobService;
    @Autowired private WikiKnowledgeBaseService kbService;
    @Autowired private WikiPageMapper pageMapper;
    @Autowired private WikiKnowledgeBaseMapper kbMapper;

    private Long kbId;

    @AfterEach
    void cleanup() {
        if (kbId != null) {
            pageMapper.delete(new LambdaQueryWrapper<WikiPageEntity>().eq(WikiPageEntity::getKbId, kbId));
            kbMapper.deleteById(kbId);
            pageService.evictSummaryCache(kbId);
            kbId = null;
        }
    }

    private void seedKb() {
        WikiKnowledgeBaseEntity kb = kbService.create("cascade-regress-" + System.nanoTime(), "regression test", null);
        kbId = kb.getId();
        // Purge whatever the bootstrap may have auto-inserted so we own the page set.
        pageMapper.delete(new LambdaQueryWrapper<WikiPageEntity>().eq(WikiPageEntity::getKbId, kbId));
        pageService.evictSummaryCache(kbId);
    }

    // ----------------------------------------------------------------
    // §8 regression — scan / cascade must not null content + summary
    // ----------------------------------------------------------------

    @Test
    @DisplayName("scan x N leaves content + summary byte-identical")
    void scanPreservesContentAndSummary() throws Exception {
        seedKb();
        String content = "## Heading\n\nThis page has a [[ghost]] broken ref and prose body.";
        String summary = "summary that must survive every scan";
        WikiPageEntity created = pageService.createPage(kbId, "alpha", "Alpha",
                content, summary, "[]");

        // Round-trip through the DB so we read what was actually persisted.
        WikiPageEntity beforeScan = pageMapper.selectById(created.getId());
        assertThat(beforeScan.getContent()).isEqualTo(content);
        assertThat(beforeScan.getSummary()).isEqualTo(summary);

        // Run KB-wide scan three times in a row. With the bug present, each
        // pass would re-issue `UPDATE ... SET content=NULL, summary=NULL`
        // for every page in the KB.
        for (int i = 0; i < 3; i++) {
            lintJobService.startOrGetRunning(kbId);
            waitForJobCompletion(20);
        }

        WikiPageEntity afterScan = pageMapper.selectById(created.getId());
        assertThat(afterScan.getContent())
                .as("content must not be NULL'd by scan")
                .isEqualTo(content);
        assertThat(afterScan.getSummary())
                .as("summary must not be NULL'd by scan")
                .isEqualTo(summary);
        // Broken-link state still computed correctly.
        assertThat(afterScan.getBrokenLinks()).contains("ghost");
        assertThat(afterScan.getBrokenLinksScannedAt()).isNotNull();
    }

    @Test
    @DisplayName("cascade delete preserves referrer's summary; content reduced only by wikilink demotion")
    void cascadeDeletePreservesReferrerSummary() {
        seedKb();
        pageService.createPage(kbId, "alice", "Alice",
                "Alice is the team lead.",
                "Senior engineer, leads search.",
                "[]");
        WikiPageEntity bob = pageService.createPage(kbId, "bob", "Bob",
                "Bob reports to [[alice]] and pairs with [[alice|her]].",
                "Junior engineer mentored by Alice.",
                "[]");
        int bobLenBefore = bob.getContent().length();
        String bobSummary = bob.getSummary();

        pageService.delete(kbId, "alice");

        WikiPageEntity bobAfter = pageMapper.selectById(bob.getId());
        assertThat(bobAfter.getSummary())
                .as("referrer's summary must not be null'd by cascade delete")
                .isEqualTo(bobSummary);
        assertThat(bobAfter.getContent()).doesNotContain("[[alice]]");
        assertThat(bobAfter.getContent()).doesNotContain("[[alice|");
        // Snapshot title + alias preserved as visible text.
        assertThat(bobAfter.getContent()).contains("Alice").contains("her");
        // Length should shrink (the wikilink syntax overhead goes away) but not zero.
        assertThat(bobAfter.getContent().length())
                .isGreaterThan(0)
                .isLessThan(bobLenBefore);
        // outgoing_links should be empty now that the only target was removed.
        assertThat(bobAfter.getOutgoingLinks()).isEqualTo("[]");
    }

    @Test
    @DisplayName("cascade rename preserves referrer's summary; alias preserved")
    void cascadeRenamePreservesReferrerSummary() {
        seedKb();
        pageService.createPage(kbId, "old-slug", "Old Title",
                "stub", "stub summary", "[]");
        WikiPageEntity referrer = pageService.createPage(kbId, "ref", "Ref",
                "Links: [[old-slug]] and [[old-slug|displayed text]].",
                "Referrer summary that must survive rename.",
                "[]");
        String summaryBefore = referrer.getSummary();

        WikiPageEntity renamed = pageService.rename(kbId, "old-slug", "new-slug");
        assertThat(renamed).isNotNull();
        assertThat(renamed.getSlug()).isEqualTo("new-slug");

        WikiPageEntity refAfter = pageMapper.selectById(referrer.getId());
        assertThat(refAfter.getSummary())
                .as("referrer's summary must not be null'd by cascade rename")
                .isEqualTo(summaryBefore);
        assertThat(refAfter.getContent()).doesNotContain("[[old-slug]]");
        assertThat(refAfter.getContent()).doesNotContain("[[old-slug|");
        assertThat(refAfter.getContent()).contains("[[new-slug]]");
        assertThat(refAfter.getContent()).contains("[[new-slug|displayed text]]");
        assertThat(refAfter.getOutgoingLinks()).contains("new-slug");
    }

    // ----------------------------------------------------------------
    // R4-G regression — case-only rename portability
    // ----------------------------------------------------------------

    @Test
    @DisplayName("case-only rename (foo → FOO) is allowed: collision check ignores same row")
    void caseOnlyRenameIsAllowed() {
        seedKb();
        WikiPageEntity p = pageService.createPage(kbId, "foo", "Foo", "body", "sum", "[]");

        // Without the same-id collision-check escape, this would throw on
        // MySQL because getBySlug("FOO") returns the same row (case-insensitive
        // collation). The fix lets it through.
        WikiPageEntity renamed = pageService.rename(kbId, "foo", "FOO");
        assertThat(renamed).isNotNull();
        assertThat(renamed.getId()).isEqualTo(p.getId());
        assertThat(renamed.getSlug()).isEqualTo("FOO");
    }

    @Test
    @DisplayName("rename to a slug already owned by a DIFFERENT page still rejects with 400-equivalent")
    void renameRejectsRealCollision() {
        seedKb();
        pageService.createPage(kbId, "first", "First", "x", "x", "[]");
        pageService.createPage(kbId, "second", "Second", "y", "y", "[]");

        // first → second is a real collision (different existing page); the
        // same-id escape must NOT swallow this.
        assertThatThrownBy(() -> pageService.rename(kbId, "first", "second"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /**
     * Spin for at most {@code timeoutSec} waiting for the latest job on
     * {@link #kbId} to leave the queued/running state. The lint executor is
     * single-threaded and per-page work is sub-ms, so this returns almost
     * immediately in practice.
     */
    private void waitForJobCompletion(int timeoutSec) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            WikiLintJobService.LintJob job = lintJobService.getLatestJob(kbId);
            if (job == null) return;
            if (job.status() == WikiLintJobService.JobStatus.COMPLETED
                    || job.status() == WikiLintJobService.JobStatus.FAILED) {
                return;
            }
            Thread.sleep(25);
        }
    }
}
