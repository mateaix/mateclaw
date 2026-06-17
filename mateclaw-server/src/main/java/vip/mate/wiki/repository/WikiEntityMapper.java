package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.wiki.model.WikiEntityEntity;

/**
 * Mapper for canonical named-entity nodes.
 *
 * <p>Write paths upsert keyed by ({@code kb_id}, {@code normalized_key},
 * {@code type}); read paths list by {@code kb_id} ordered by {@code salience}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiEntityMapper extends BaseMapper<WikiEntityEntity> {
}
