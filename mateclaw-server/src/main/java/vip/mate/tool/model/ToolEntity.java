package vip.mate.tool.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具实体
 * 工具实体：Agent 可调用的原子能力
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_tool")
public class ToolEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 工具名称（唯一标识） */
    private String name;

    /** 工具显示名称 */
    private String displayName;

    /** 工具描述（用于 LLM 理解） */
    private String description;

    /** 工具类型：builtin（内置Java工具）/ mcp（MCP协议） */
    private String toolType;

    /** Spring Bean 名称（用于 ToolRegistry 与 DB 的映射，builtin 工具必填） */
    private String beanName;

    /** 工具图标 */
    private String icon;

    /** MCP 服务器地址（toolType=mcp 时使用） */
    private String mcpEndpoint;

    /** 工具参数 Schema（JSON Schema 格式） */
    @TableField(value = "params_schema", updateStrategy = FieldStrategy.ALWAYS)
    private String paramsSchema;

    /** 是否启用 */
    private Boolean enabled;

    /** 是否系统内置 */
    private Boolean builtin;

    /**
     * For channel-native tools ({@code tool_type="channel"}), the
     * {@code mate_channel.id} that materialised this tool. {@link
     * vip.mate.channel.tool.ChannelToolService} uses this column to
     * delete a channel's tool rows when its config row is removed and
     * to detect "config changed → rebuild tool" cases. Null for
     * built-in / MCP / skill tools.
     */
    private Long channelId;

    /**
     * Progressive disclosure tier: {@code core} (always advertised to the LLM)
     * or {@code extension} (hidden behind the extension-tools catalog until the
     * model calls {@code enable_tool}). Admin override for builtin / channel
     * atomic tools; sensible defaults for unset rows live in
     * {@code ToolDisclosureService}.
     */
    private String disclosureTier;

    /**
     * Runtime {@code @Tool} function names exposed by this row's bean/class
     * aliases. Not persisted; populated for admin UI so tier changes can be
     * correlated with the names the model actually sees.
     */
    @TableField(exist = false)
    private List<String> runtimeNames;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
