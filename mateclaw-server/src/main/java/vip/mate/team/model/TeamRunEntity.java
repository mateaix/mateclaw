package vip.mate.team.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent identity and lifecycle state for one team execution.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_team_run")
public class TeamRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long teamId;

    private Long workspaceId;

    private Long leadAgentId;

    private String leadConversationId;

    private Long originMessageId;

    private String title;

    private String objective;

    private String status;

    @TableField(value = "final_summary", updateStrategy = FieldStrategy.ALWAYS)
    private String finalSummary;

    @TableField(value = "stop_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String stopReason;

    @TableField(value = "metadata", updateStrategy = FieldStrategy.ALWAYS)
    private String metadata;

    @TableField(value = "started_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    @TableField(value = "completed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
