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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage for {@link WikiPageService#mergeDuplicateTitles} — the one-time
 * maintenance op that collapses pages sharing a canonical title (the duplicate
 * rows produced before title-based dedup existed, when one concept landed under
 * several LLM-minted slugs).
 *
 * <p>Boots the full Spring + H2 + Flyway context so MyBatis-Plus's lambda cache
 * and the real cascade/link machinery are exercised end to end.
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
class WikiMergeDuplicateTitlesE2ETest {

    @Autowired private WikiPageService pageService;
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
        WikiKnowledgeBaseEntity kb = kbService.create("merge-dup-" + System.nanoTime(), "merge test", null);
        kbId = kb.getId();
        pageMapper.delete(new LambdaQueryWrapper<WikiPageEntity>().eq(WikiPageEntity::getKbId, kbId));
        pageService.evictSummaryCache(kbId);
    }

    /**
     * Three pages share the canonical title "医宗金鉴" under different slugs (the
     * exact failure mode from the bug report), plus a referrer linking to a loser.
     */
    private void seedDuplicates() {
        // Winner: longest content.
        pageService.createPage(kbId, "yizong-jinjian", "医宗金鉴",
                "## 医宗金鉴\n\nThis is the most complete body with the richest detail aaa bbb ccc ddd.",
                "complete summary", "[]");
        // Losers: same canonical title, shorter content, different slugs.
        pageService.createPage(kbId, "yizong-jinjian-quanshu", "医宗金鉴",
                "Body B shorter.", "b summary", "[]");
        // Trailing-space title still canonicalizes equal.
        pageService.createPage(kbId, "yzjj", "医宗金鉴 ",
                "Body C tiny.", "c summary", "[]");
        // A referrer pointing at one of the losers.
        pageService.createPage(kbId, "ref", "Ref",
                "See [[yizong-jinjian-quanshu]] for the canonical text.",
                "referrer summary", "[]");
    }

    @Test
    @DisplayName("dry run reports duplicates without mutating anything")
    void dryRunReportsButDoesNotMutate() {
        seedKb();
        seedDuplicates();

        Map<String, Object> report = pageService.mergeDuplicateTitles(kbId, true, true);

        assertThat(report.get("dryRun")).isEqualTo(true);
        assertThat(report.get("duplicateGroups")).isEqualTo(1);
        assertThat(report.get("pagesWouldRemove")).isEqualTo(2);
        assertThat(report.get("pagesRemoved")).isEqualTo(0);

        // Nothing deleted: all four pages still present.
        assertThat(pageService.listByKbIdWithContent(kbId)).hasSize(4);
        assertThat(pageService.getBySlug(kbId, "yizong-jinjian-quanshu")).isNotNull();
        assertThat(pageService.getBySlug(kbId, "yzjj")).isNotNull();
    }

    @Test
    @DisplayName("apply with concatenate merges losers into winner, redirects refs, deletes losers")
    void applyConcatenateCollapsesGroup() {
        seedKb();
        seedDuplicates();

        Map<String, Object> report = pageService.mergeDuplicateTitles(kbId, false, true);
        assertThat(report.get("duplicateGroups")).isEqualTo(1);
        assertThat(report.get("pagesRemoved")).isEqualTo(2);

        // Only the winner + the referrer survive.
        assertThat(pageService.listByKbIdWithContent(kbId)).hasSize(2);
        assertThat(pageService.getBySlug(kbId, "yizong-jinjian-quanshu")).isNull();
        assertThat(pageService.getBySlug(kbId, "yzjj")).isNull();

        // Winner keeps its body and gains the losers' bodies (no content lost).
        WikiPageEntity winner = pageService.getBySlug(kbId, "yizong-jinjian");
        assertThat(winner).isNotNull();
        assertThat(winner.getContent())
                .contains("most complete body")
                .contains("Body B shorter.")
                .contains("Body C tiny.");
        assertThat(winner.getVersion()).isGreaterThan(1);

        // Referrer's [[loserSlug]] is redirected to the winner, not demoted.
        WikiPageEntity ref = pageService.getBySlug(kbId, "ref");
        assertThat(ref.getContent())
                .doesNotContain("[[yizong-jinjian-quanshu]]")
                .contains("[[yizong-jinjian]]");
    }

    @Test
    @DisplayName("apply without concatenate keeps only the winner's body")
    void applyWithoutConcatenateDiscardsLoserBodies() {
        seedKb();
        seedDuplicates();

        pageService.mergeDuplicateTitles(kbId, false, false);

        assertThat(pageService.listByKbIdWithContent(kbId)).hasSize(2);
        WikiPageEntity winner = pageService.getBySlug(kbId, "yizong-jinjian");
        assertThat(winner).isNotNull();
        assertThat(winner.getContent())
                .contains("most complete body")
                .doesNotContain("Body B shorter.")
                .doesNotContain("Body C tiny.");
    }
}
