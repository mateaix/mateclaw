package vip.mate.workflow.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.workflow.model.WorkflowEntity;

@Mapper
public interface WorkflowMapper extends BaseMapper<WorkflowEntity> {

    /**
     * Row-locking lookup used by the publish path. Two concurrent publishes
     * for the same workflow would otherwise both compute the same
     * {@code max(revision)+1} and the second would crash on the
     * {@code uk_workflow_revision} unique constraint, leaving
     * {@code latest_revision_id} pointing at the first while the second
     * caller saw a 500. Locking the workflow row in a single transaction
     * serializes the two publishes cleanly.
     */
    @Select("SELECT * FROM mate_workflow WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    WorkflowEntity selectByIdForUpdate(@Param("id") long id);
}
