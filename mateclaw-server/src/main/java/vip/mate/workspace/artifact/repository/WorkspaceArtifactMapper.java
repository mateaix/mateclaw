package vip.mate.workspace.artifact.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.workspace.artifact.model.WorkspaceArtifactEntity;

/**
 * MyBatis-Plus mapper for {@link WorkspaceArtifactEntity}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface WorkspaceArtifactMapper extends BaseMapper<WorkspaceArtifactEntity> {
}
