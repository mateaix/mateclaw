package vip.mate.cron.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import vip.mate.cron.model.CronJobEntity;

import java.util.List;

/**
 * 定时任务 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface CronJobMapper extends BaseMapper<CronJobEntity> {

    /**
     * RFC-063r §2.14 + RFC-083: list cron jobs in the given workspace together
     * with their most-recent delivery status / error (subquery against
     * {@code mate_cron_job_run}).
     *
     * <p>Both H2 and MySQL accept {@code LIMIT 1} inside a correlated
     * subquery, so the same SQL is portable across the two profiles. Index
     * coverage: {@code mate_cron_job_run(cron_job_id, started_at)} (created
     * by V1 baseline migration) makes the subquery cheap; V62 adds
     * {@code idx_cron_job_workspace(workspace_id, deleted)} for the outer
     * filter.
     *
     * <p>Filters out logically-deleted rows and orders by create_time DESC
     * to mirror the existing {@code list()} ordering.
     */
    // Shared result map for every hand-written query in this mapper. The
    // typeHandler declared on CronJobEntity.deliveryConfig only reaches the
    // result map MyBatis Plus generates for the injected BaseMapper methods;
    // annotation-driven statements build their own, and auto-mapping finds no
    // handler for the DeliveryConfig record, so MyBatis silently skips the
    // column (default AutoMappingUnknownColumnBehavior.NONE) and every job
    // read through these queries came back with a null deliveryConfig.
    // Restating the handler here fixes it — the other columns still auto-map.
    @Results(id = "cronJobResultMap", value = {
            @Result(column = "delivery_config", property = "deliveryConfig",
                    typeHandler = JacksonTypeHandler.class)
    })
    @Select("""
            SELECT j.*,
                   (SELECT r.delivery_status FROM mate_cron_job_run r
                    WHERE r.cron_job_id = j.id
                    ORDER BY r.started_at DESC LIMIT 1) AS last_delivery_status,
                   (SELECT r.delivery_error FROM mate_cron_job_run r
                    WHERE r.cron_job_id = j.id
                    ORDER BY r.started_at DESC LIMIT 1) AS last_delivery_error
            FROM mate_cron_job j
            WHERE j.deleted = 0 AND j.workspace_id = #{workspaceId}
            ORDER BY j.create_time DESC
            """)
    List<CronJobEntity> selectListWithDeliveryStatus(@Param("workspaceId") Long workspaceId);

    /**
     * RFC-063r §2.14 + RFC-083: per-job variant for the detail page. Same
     * subquery pattern, restricted to a single id within the given workspace
     * (cross-workspace access returns null → caller throws not_found, matching
     * the "deleted" shape so workspace existence isn't enumerable).
     */
    @ResultMap("cronJobResultMap")
    @Select("""
            SELECT j.*,
                   (SELECT r.delivery_status FROM mate_cron_job_run r
                    WHERE r.cron_job_id = j.id
                    ORDER BY r.started_at DESC LIMIT 1) AS last_delivery_status,
                   (SELECT r.delivery_error FROM mate_cron_job_run r
                    WHERE r.cron_job_id = j.id
                    ORDER BY r.started_at DESC LIMIT 1) AS last_delivery_error
            FROM mate_cron_job j
            WHERE j.id = #{id} AND j.deleted = 0 AND j.workspace_id = #{workspaceId}
            """)
    CronJobEntity selectByIdWithDeliveryStatus(@Param("id") Long id,
                                               @Param("workspaceId") Long workspaceId);

    /**
     * RFC-083: workspace-scoped lookup for write paths (update / delete /
     * toggle / runNow). Skips the delivery-status subquery — those paths
     * don't need it and pay for the correlated lookup otherwise.
     */
    @ResultMap("cronJobResultMap")
    @Select("SELECT * FROM mate_cron_job WHERE id = #{id} AND deleted = 0 AND workspace_id = #{workspaceId}")
    CronJobEntity selectByIdAndWorkspace(@Param("id") Long id,
                                         @Param("workspaceId") Long workspaceId);
}
