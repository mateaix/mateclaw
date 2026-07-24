package vip.mate.team.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Comment on a team task, written by an agent, a human, or the system.
 * A comment of type "blocker" auto-fails the task and escalates to the lead.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_team_task_comment")
public class TeamTaskCommentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long taskId;

    /** Denormalized team id for board-level queries. */
    private Long teamId;

    /** Author kind: agent / user / system. */
    private String authorType;

    /** Agent id or username depending on authorType. */
    private String authorId;

    /** note / blocker. */
    private String commentType;

    private String content;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
