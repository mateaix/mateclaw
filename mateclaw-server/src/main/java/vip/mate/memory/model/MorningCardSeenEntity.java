package vip.mate.memory.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Morning card seen state — tracks per (user, agent) whether the card was dismissed.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_morning_card_seen")
public class MorningCardSeenEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long agentId;

    private LocalDateTime lastSeenAt;

    private Long lastReportId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
