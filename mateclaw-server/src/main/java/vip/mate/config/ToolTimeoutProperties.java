package vip.mate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行超时配置
 * <p>
 * 支持三级配置：per-tool → per-category → default。
 * 查找优先级：先精确匹配工具名，再匹配类别，最后用默认值。
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.agent.tool.timeout")
public class ToolTimeoutProperties {

    /** 默认超时（秒） */
    private int defaultTimeoutSeconds = 300;

    /** 按工具类别的超时（秒）。key: shell/web/mcp/file */
    private Map<String, Integer> perCategory = new HashMap<>();

    /** 按工具名的超时（秒）。key: 工具名（如 web_fetch） */
    private Map<String, Integer> perTool = new HashMap<>();

    // 内置类别映射
    private static final Map<String, String> TOOL_CATEGORY_MAP = Map.of(
            "execute_bash", "shell",
            "run_command", "shell",
            "web_fetch", "web",
            "url_fetch", "web",
            "write_file", "file",
            "append_file", "file",
            "edit_file", "file",
            "read_file", "file"
    );

    /**
     * 获取指定工具的超时时间（秒）
     * 查找顺序：per-tool → per-category → default
     */
    public int getTimeoutSeconds(String toolName) {
        // 1. 精确匹配工具名
        if (toolName != null && perTool.containsKey(toolName)) {
            return perTool.get(toolName);
        }
        // 2. 匹配类别
        if (toolName != null) {
            String category = TOOL_CATEGORY_MAP.get(toolName);
            // MCP 工具通常以 mcp_ 开头
            if (category == null && toolName.startsWith("mcp_")) {
                category = "mcp";
            }
            if (category != null && perCategory.containsKey(category)) {
                return perCategory.get(category);
            }
        }
        // 3. 默认值
        return defaultTimeoutSeconds;
    }
}
