package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.wiki.model.WikiPipelineStepRunEntity;

/**
 * Mapper for {@link WikiPipelineStepRunEntity}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiPipelineStepRunMapper extends BaseMapper<WikiPipelineStepRunEntity> {
}
