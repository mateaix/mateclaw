package vip.mate.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.team.model.TeamTaskEntity;

/**
 * Team task board mapper.
 *
 * @author MateClaw Team
 */
@Mapper
public interface TeamTaskMapper extends BaseMapper<TeamTaskEntity> {
}
