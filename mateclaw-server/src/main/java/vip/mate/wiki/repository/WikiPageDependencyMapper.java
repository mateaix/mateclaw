package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.wiki.model.WikiPageDependencyEntity;

/**
 * Mapper for {@link WikiPageDependencyEntity}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiPageDependencyMapper extends BaseMapper<WikiPageDependencyEntity> {
}
