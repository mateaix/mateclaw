package vip.mate.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.team.model.TeamTaskCommentEntity;

/**
 * Team task comment mapper.
 *
 * @author MateClaw Team
 */
@Mapper
public interface TeamTaskCommentMapper extends BaseMapper<TeamTaskCommentEntity> {
}
