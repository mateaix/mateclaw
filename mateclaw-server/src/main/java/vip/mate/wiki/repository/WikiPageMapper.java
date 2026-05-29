package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.wiki.dto.WikiPageLite;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.Collection;
import java.util.List;

/**
 * Wiki page mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiPageMapper extends BaseMapper<WikiPageEntity> {

    /**
     * DB keyword search (H2 + MySQL compatible LIKE).
     * Does not SELECT content CLOB to avoid loading large blobs into Java heap.
     */
    @Select("SELECT id, kb_id, slug, title, summary, source_raw_ids, last_updated_by, page_type " +
            "FROM mate_wiki_page " +
            "WHERE kb_id = #{kbId} AND deleted = 0 AND archived = 0 " +
            "AND (LOWER(title) LIKE #{pattern} OR LOWER(summary) LIKE #{pattern} " +
            "     OR LOWER(content) LIKE #{pattern}) " +
            "ORDER BY title LIMIT 20")
    List<WikiPageEntity> searchByKeyword(@Param("kbId") Long kbId, @Param("pattern") String pattern);

    // ==================== RFC-029: Relation model ====================

    /**
     * Batch-fetch lightweight page projections by IDs (no content).
     */
    @Select("<script>SELECT id, slug, title, summary, page_type AS pageType FROM mate_wiki_page " +
            "WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND deleted = 0 AND archived = 0</script>")
    List<WikiPageLite> selectBatchLite(@Param("ids") Collection<Long> ids);

    /**
     * List all pages as lightweight projections (no content).
     */
    @Select("SELECT id, slug, title, summary, page_type AS pageType FROM mate_wiki_page " +
            "WHERE kb_id = #{kbId} AND deleted = 0 AND archived = 0 ORDER BY update_time DESC")
    List<WikiPageLite> selectAllLite(@Param("kbId") Long kbId);

    /**
     * Fetch only the content column for a single page (lazy-load for snippet extraction).
     */
    @Select("SELECT content FROM mate_wiki_page WHERE id = #{id} AND deleted = 0")
    String selectContentById(@Param("id") Long id);

    /**
     * Candidate-set query for the cascade-delete / cascade-rename pipeline.
     * Find every page in {@code kbId} whose {@code outgoing_links} JSON array
     * might mention {@code slug}, using a LIKE pre-filter (works on both H2
     * and MySQL without a JSON_CONTAINS shim). The {@code slugPattern} should
     * be {@code %"<slug-lowercased>"%} so the surrounding quotes pin the
     * match to a full JSON string element rather than a substring; the
     * caller still re-verifies each row by parsing the content with
     * {@code WikiLinkService} because the actual rewrite must skip code
     * blocks and ignore false-positive substring matches inside other JSON
     * strings.
     * <p>
     * SQL guard order: {@code kb_id} + {@code deleted} + {@code archived} +
     * {@code id != excludeId} are all ANDed before the LIKE. The explicit
     * prefix prevents a multi-tenant leak where a future OR-clause might
     * accidentally cross KB boundaries.
     */
    @Select("SELECT id, kb_id, slug, title, content, outgoing_links " +
            "FROM mate_wiki_page " +
            "WHERE kb_id = #{kbId} " +
            "  AND deleted = 0 " +
            "  AND archived = 0 " +
            "  AND id != #{excludeId} " +
            "  AND outgoing_links LIKE #{slugPattern}")
    List<WikiPageEntity> findReferrersByOutgoingLink(
            @Param("kbId") Long kbId,
            @Param("excludeId") Long excludeId,
            @Param("slugPattern") String slugPattern);

    // ==================== RFC-032: Two-phase keyword search ====================

    /**
     * Phase 1 (fast): search only title + summary columns.
     */
    @Select("SELECT id FROM mate_wiki_page " +
            "WHERE kb_id = #{kbId} AND deleted = 0 AND archived = 0 " +
            "AND (LOWER(title) LIKE #{kw} OR LOWER(summary) LIKE #{kw}) " +
            "LIMIT #{limit}")
    List<Long> searchFastIds(@Param("kbId") Long kbId,
                              @Param("kw") String kw,
                              @Param("limit") int limit);

    /**
     * Phase 2 (slow): search full content, excluding already-found IDs.
     */
    @Select("<script>SELECT id FROM mate_wiki_page " +
            "WHERE kb_id = #{kbId} AND deleted = 0 AND archived = 0 " +
            "AND LOWER(content) LIKE #{kw} " +
            "<if test='excludeIds != null and !excludeIds.isEmpty()'>" +
            "AND id NOT IN <foreach collection='excludeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</if> " +
            "LIMIT #{limit}</script>")
    List<Long> searchContentIds(@Param("kbId") Long kbId,
                                 @Param("kw") String kw,
                                 @Param("excludeIds") List<Long> excludeIds,
                                 @Param("limit") int limit);
}
