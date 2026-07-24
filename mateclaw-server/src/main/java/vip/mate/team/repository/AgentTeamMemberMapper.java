package vip.mate.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.team.model.AgentTeamMemberEntity;

/**
 * Team membership mapper.
 *
 * @author MateClaw Team
 */
@Mapper
public interface AgentTeamMemberMapper extends BaseMapper<AgentTeamMemberEntity> {
}
