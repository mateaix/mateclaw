package vip.mate.skill.routine.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.skill.routine.model.SkillRoutineCandidateEntity;

/**
 * Data access for recurring-request candidates.
 *
 * @author MateClaw Team
 */
@Mapper
public interface SkillRoutineCandidateMapper extends BaseMapper<SkillRoutineCandidateEntity> {
}
