package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.wiki.model.WikiEntityMentionEntity;

/**
 * Mapper for entity-to-source occurrence links.
 *
 * <p>Read paths fetch mentions by {@code entity_id} or by {@code page_id};
 * cache invalidation soft-deletes by {@code chunk_id} or {@code kb_id}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiEntityMentionMapper extends BaseMapper<WikiEntityMentionEntity> {
}
