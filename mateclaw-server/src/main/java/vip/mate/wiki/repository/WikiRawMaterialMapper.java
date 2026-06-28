package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.wiki.dto.RawTitleRef;
import vip.mate.wiki.dto.WikiFailureItem;
import vip.mate.wiki.model.WikiRawMaterialEntity;

import java.util.Collection;
import java.util.List;

/**
 * Wiki raw material mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiRawMaterialMapper extends BaseMapper<WikiRawMaterialEntity> {

    /**
     * RFC-032: Batch-fetch raw material titles by IDs (fixes N+1 in wiki_semantic_search).
     */
    @Select("<script>SELECT id, title FROM mate_wiki_raw_material " +
            "WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND deleted = 0</script>")
    List<RawTitleRef> selectBatchTitles(@Param("ids") Collection<Long> ids);

    /**
     * Predicate shared by the count + list of materials needing operator
     * attention: hard failure, partial (rerunnable), or completed-but-degraded
     * (an async sub-step recorded a warning).
     */
    String NEEDS_ATTENTION =
            "r.deleted = 0 AND (r.processing_status IN ('failed','partial') OR r.warning_code IS NOT NULL)";

    /** Count of attention-needing raw materials across all knowledge bases. */
    @Select("SELECT COUNT(*) FROM mate_wiki_raw_material r "
            + "JOIN mate_wiki_knowledge_base k ON k.id = r.kb_id AND k.deleted = 0 "
            + "WHERE " + NEEDS_ATTENTION)
    long countFailures();

    /**
     * Cross-KB list of attention-needing raw materials, newest first. Joined to
     * the knowledge base for the display name + workspace so the UI can route to
     * the owning KB without a second round-trip.
     */
    @Select("SELECT r.id AS rawId, r.kb_id AS kbId, k.name AS kbName, k.workspace_id AS workspaceId, "
            + "r.title AS title, r.processing_status AS processingStatus, "
            + "r.error_code AS errorCode, r.error_message AS errorMessage, "
            + "r.warning_code AS warningCode, r.warning_message AS warningMessage, "
            + "r.update_time AS updateTime "
            + "FROM mate_wiki_raw_material r "
            + "JOIN mate_wiki_knowledge_base k ON k.id = r.kb_id AND k.deleted = 0 "
            + "WHERE " + NEEDS_ATTENTION + " "
            + "ORDER BY r.update_time DESC "
            + "LIMIT #{limit}")
    List<WikiFailureItem> listFailures(@Param("limit") int limit);
}
