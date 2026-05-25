package vip.mate.wiki.support;

import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.model.WikiRelationEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Static factories for wiki-domain test entities.
 *
 * <p>Tests should construct fixtures through these helpers instead of
 * {@code new}-ing entities by hand. Factories set every required field to
 * a sensible default so the same call site keeps compiling when columns
 * are added later — schema growth is otherwise the most common reason
 * unit tests across the wiki domain start failing in unrelated PRs.
 *
 * <p>All factories return mutable entities; tests are free to override
 * any field after construction.
 *
 * @author MateClaw Team
 */
public final class WikiTestSupport {

    private WikiTestSupport() {
        // static factory only
    }

    public static WikiKnowledgeBaseEntity kb() {
        return kb("test-kb-" + shortRandom());
    }

    public static WikiKnowledgeBaseEntity kb(String name) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setName(name);
        kb.setDescription("test fixture");
        kb.setStatus("active");
        kb.setPageCount(0);
        kb.setRawCount(0);
        kb.setWorkspaceId(1L);
        kb.setCreateTime(LocalDateTime.now());
        kb.setUpdateTime(LocalDateTime.now());
        kb.setDeleted(0);
        return kb;
    }

    public static WikiRawMaterialEntity raw(Long kbId, String title, String content) {
        WikiRawMaterialEntity raw = new WikiRawMaterialEntity();
        raw.setKbId(kbId);
        raw.setTitle(title);
        raw.setSourceType("text");
        raw.setOriginalContent(content);
        raw.setProcessingStatus("pending");
        raw.setCreateTime(LocalDateTime.now());
        raw.setUpdateTime(LocalDateTime.now());
        raw.setDeleted(0);
        return raw;
    }

    public static WikiPageEntity page(Long kbId, String slug, String title, String content) {
        WikiPageEntity page = new WikiPageEntity();
        page.setKbId(kbId);
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setSummary(content == null ? "" : content.substring(0, Math.min(80, content.length())));
        page.setPageType("concept");
        page.setCreateTime(LocalDateTime.now());
        page.setUpdateTime(LocalDateTime.now());
        page.setDeleted(0);
        return page;
    }

    public static WikiChunkEntity chunk(Long kbId, Long rawId, String content) {
        WikiChunkEntity chunk = new WikiChunkEntity();
        chunk.setKbId(kbId);
        chunk.setRawId(rawId);
        chunk.setContent(content);
        chunk.setCreateTime(LocalDateTime.now());
        chunk.setUpdateTime(LocalDateTime.now());
        chunk.setDeleted(0);
        return chunk;
    }

    public static WikiRelationEntity relation(Long kbId, Long pageA, Long pageB, double score) {
        WikiRelationEntity rel = new WikiRelationEntity();
        rel.setKbId(kbId);
        rel.setPageAId(pageA);
        rel.setPageBId(pageB);
        rel.setTotalScore(BigDecimal.valueOf(score));
        rel.setComputedAt(LocalDateTime.now());
        rel.setCreateTime(LocalDateTime.now());
        rel.setUpdateTime(LocalDateTime.now());
        rel.setDeleted(0);
        return rel;
    }

    private static String shortRandom() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
