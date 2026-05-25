package vip.mate.skill.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mate.skill.model.SkillFileEntity;

/**
 * Mapper for {@link SkillFileEntity}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface SkillFileMapper extends BaseMapper<SkillFileEntity> {

    /** Drop every file row owned by the given skill — used on hard-delete. */
    @Delete("DELETE FROM mate_skill_file WHERE skill_id = #{skillId}")
    int deleteBySkillId(@Param("skillId") Long skillId);
}
