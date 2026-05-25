package vip.mate.goal.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Append-only event row in the goal's audit trail.
 *
 * <p>{@link #eventType} values are defined in {@link GoalEventType}.
 * The drawer timeline reads these rows in reverse chronological order.
 */
@Data
@TableName("mate_agent_goal_event")
public class GoalEventEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long goalId;

    /** One of the {@link GoalEventType} string values. */
    private String eventType;

    /** Optional FK to mate_message.id for the assistant turn this event ties to. */
    private Long messageId;

    /** JSON detail payload — schema varies per event_type. */
    private String detailJson;

    private LocalDateTime createTime;
}
