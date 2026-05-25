package vip.mate.agent.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 配置实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_agent")
public class AgentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Agent 名称 */
    private String name;

    /** Agent 描述 */
    private String description;

    /** Agent 类型：react / plan_execute */
    private String agentType;

    /** 系统提示词 */
    @TableField(value = "system_prompt", updateStrategy = FieldStrategy.ALWAYS)
    private String systemPrompt;

    /**
     * Per-Agent model override.
     *
     * <p>When non-blank, the runtime resolves this value via
     * {@code ModelConfigService.resolveModel(...)} — a case-sensitive,
     * enabled-only lookup against {@code mate_model_config.model_name}.
     * On match, the resolved entity is used as the primary model in
     * place of {@code getDefaultModel()}.
     *
     * <p>Null / blank → fall back to the global default (preserves the
     * original behavior). Stale rows whose named model has been removed
     * or disabled also fall back, since {@code resolveModel} returns the
     * default when no enabled match is found.
     *
     * <p>{@link FieldStrategy#ALWAYS} so a {@code PUT} with explicit null
     * actually clears the column — the MyBatis-Plus default {@code NOT_NULL}
     * strategy silently drops null fields from UPDATE, which means a user
     * who once picked a model could never revert back to "use global default"
     * via the UI (only by directly editing the DB). Smoke test on 2026-05-02
     * caught it.
     *
     * <p>RFC-03 Lane G1 — re-enables this field after it was silently
     * deprecated in earlier work; the database column is unchanged.
     */
    @TableField(value = "model_name", updateStrategy = FieldStrategy.ALWAYS)
    private String modelName;

    /** 最大迭代次数 */
    private Integer maxIterations;

    /** 是否启用 */
    private Boolean enabled;

    /** 图标（emoji 或 URL） */
    private String icon;

    /** 标签（逗号分隔） */
    private String tags;

    /** 所属工作区 ID（默认 1 = default） */
    private Long workspaceId;

    /** Creator user ID — backfilled on create; lets members delete their own Agents without admin role */
    private Long creatorUserId;

    /** 默认思考深度：off / low / medium / high / max，null 表示跟随模型默认 */
    private String defaultThinkingLevel;

    /**
     * Agent-level working directory override. When non-blank, takes priority
     * over the workspace's basePath; relative values are resolved under the
     * workspace basePath. Null/blank means inherit the workspace value.
     */
    @TableField(value = "workspace_base_path", updateStrategy = FieldStrategy.ALWAYS)
    private String workspaceBasePath;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
