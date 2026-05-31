package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.wiki.model.WikiPipelineDefinitionEntity;

/**
 * Mapper for {@link WikiPipelineDefinitionEntity}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiPipelineDefinitionMapper extends BaseMapper<WikiPipelineDefinitionEntity> {
}
