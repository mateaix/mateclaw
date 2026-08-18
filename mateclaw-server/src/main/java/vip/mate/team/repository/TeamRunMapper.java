package vip.mate.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.team.model.TeamRunEntity;

/** Persistent team run mapper. */
@Mapper
public interface TeamRunMapper extends BaseMapper<TeamRunEntity> {
}
