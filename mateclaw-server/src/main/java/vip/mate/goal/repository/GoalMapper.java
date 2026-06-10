package vip.mate.goal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.goal.model.GoalEntity;

/**
 * MyBatis Plus mapper for {@link GoalEntity}.
 *
 * <p>Lives under the {@code repository} sub-package per project convention —
 * {@code MateClawApplication} uses {@code @MapperScan("vip.mate.**.repository")}.
 * Putting the mapper elsewhere makes startup fail with a missing-bean error.
 */
@Mapper
public interface GoalMapper extends BaseMapper<GoalEntity> {
}
