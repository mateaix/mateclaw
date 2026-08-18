package vip.mate.skill.lifecycle.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.skill.lifecycle.model.SkillSnapshotEntity;

/**
 * Data access for skill library restore points.
 *
 * @author MateClaw Team
 */
@Mapper
public interface SkillSnapshotMapper extends BaseMapper<SkillSnapshotEntity> {
}
