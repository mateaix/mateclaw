package vip.mate.team.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent team: one lead agent plus member agents collaborating through a
 * shared task board. The lead orchestrates work by creating tasks assigned
 * to members; members execute in isolated conversations and report results.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_agent_team")
public class AgentTeamEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String description;

    /** Agent that orchestrates this team; exactly one per team. */
    private Long leadAgentId;

    /** Team lifecycle status: active / paused. */
    private String status;

    /** Monotonic per-team counter backing human-readable task numbers. */
    private Integer taskSeq;

    /** Team-level settings as a JSON object (notification switches, escalation, ...). */
    private String settings;

    /** Username of the admin who created the team. */
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
