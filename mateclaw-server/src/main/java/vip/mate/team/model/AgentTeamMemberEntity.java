package vip.mate.team.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Team membership row linking an agent to a team with a role.
 * An agent belongs to at most one active team (enforced in the service layer).
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_agent_team_member")
public class AgentTeamMemberEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long teamId;

    private Long agentId;

    /** Member role within the team: lead / member / reviewer. */
    private String role;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
