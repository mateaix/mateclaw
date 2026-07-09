package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.wiki.model.WikiSourceGroupEntity;

/**
 * Wiki 来源分组 mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiSourceGroupMapper extends BaseMapper<WikiSourceGroupEntity> {
}
