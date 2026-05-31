package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.wiki.model.WikiPipelineRunEntity;

/**
 * Mapper for {@link WikiPipelineRunEntity}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiPipelineRunMapper extends BaseMapper<WikiPipelineRunEntity> {
}
