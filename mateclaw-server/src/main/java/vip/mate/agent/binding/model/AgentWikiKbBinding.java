package vip.mate.agent.binding.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Agent ↔ knowledge base access scope row.
 * <p>
 * Each enabled row whitelists one KB for one agent. When an agent has at
 * least one row the wiki tools restrict their visible KB set to the bound
 * ones; an agent with no rows stays workspace-wide (legacy behavior).
 */
@Data
@TableName("mate_agent_wiki_kb")
public class AgentWikiKbBinding {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long agentId;
    private Long kbId;
    private Boolean enabled;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}
