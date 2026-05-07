package vip.mate.tool.mcp.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP Server 配置实体
 * <p>
 * 表示一个外部 MCP 服务器的连接配置，一个 server 可暴露多个 tools。
 * 独立于 mate_tool 表，因为 server 是工具来源配置，tool 是工具展示元数据。
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_mcp_server")
public class McpServerEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 服务器名称（唯一标识） */
    private String name;

    /** 描述 */
    private String description;

    /** 传输协议：stdio / streamable_http / sse */
    private String transport;

    /** 远端 URL（http/sse 类型使用） */
    private String url;

    /** HTTP 请求头 JSON（如 {"Authorization": "Bearer xxx"}） */
    private String headersJson;

    /** 启动命令（stdio 类型使用） */
    private String command;

    /** 命令参数 JSON 数组（如 ["-y", "@modelcontextprotocol/server-filesystem"]） */
    private String argsJson;

    /** 环境变量 JSON（如 {"API_KEY": "xxx"}） */
    private String envJson;

    /** 工作目录（stdio 类型使用） */
    private String cwd;

    /** 是否启用 */
    private Boolean enabled;

    /** 连接超时（秒） */
    private Integer connectTimeoutSeconds;

    /** 读取超时（秒） */
    private Integer readTimeoutSeconds;

    /** 最后连接状态：connected / disconnected / error */
    private String lastStatus;

    /** 最后错误信息 */
    private String lastError;

    /** 最后成功连接时间 */
    private LocalDateTime lastConnectedTime;

    /** 远端暴露的工具数量 */
    private Integer toolCount;

    /**
     * Last successful {@code listTools()} response, serialized as a JSON
     * array of {@code {name, description, inputSchema}} entries. Refreshed
     * by {@code McpServerService} after every successful (re)connect; never
     * cleared on failure so the picker keeps working while the upstream
     * server is briefly unavailable. Reverse-lookup of a prefixed callback
     * name to its raw tool name reads from this column.
     */
    @TableField(value = "tools_cache_json", updateStrategy = FieldStrategy.ALWAYS)
    private String toolsCacheJson;

    /** Wall-clock timestamp of the last successful tools-cache write. */
    private LocalDateTime toolsCacheUpdatedAt;

    /** 是否系统内置 */
    private Boolean builtin;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
