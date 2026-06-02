package vip.mate.memory.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.memory.service.StructuredMemoryService;

import java.util.List;
import java.util.Map;

/**
 * Structured memory tool — gives the agent typed memory read/write capabilities.
 * <p>
 * Memory types: user (preferences/expertise), feedback (corrections/confirmations),
 * project (decisions/deadlines), reference (external system pointers).
 * <p>
 * Entries are stored as workspace files (structured/*.md) via StructuredMemoryService.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructuredMemoryTool {

    private final StructuredMemoryService structuredMemoryService;
    private final MemoryOwnerResolver memoryOwnerResolver;
    private final MemoryProperties memoryProperties;

    /** Owner key for reads: the resolved requester (visibility = shared + own personal). */
    private String readOwner(ToolContext ctx) {
        return memoryOwnerResolver.resolve(ChatOrigin.from(ctx));
    }

    /**
     * Owner key for writes/deletes: the resolved requester only when per-owner
     * isolation is active; otherwise null so the entry lands in the shared
     * bucket rather than an un-read PERSONAL row.
     */
    private String writeOwner(ToolContext ctx) {
        return memoryProperties.isLifecycleMediatorEnabled() ? readOwner(ctx) : null;
    }

    @Tool(description = """
            记住一条结构化信息到 Agent 的长期记忆。
            适用于持久化离散的事实、偏好、纠正或外部指针。
            type 必须是以下之一：
            - user: 用户画像、偏好、专长、沟通风格
            - feedback: 行为纠正或确认（附带原因）
            - project: 项目决策、里程碑、约束（不在代码或 git 中的）
            - reference: 外部系统指针（工单系统、仪表盘、文档链接等）
            key 用 snake_case 标识符，例如 preferred_language, no_mock_db
            """)
    public String remember_structured(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "记忆类型：user / feedback / project / reference") String type,
            @ToolParam(description = "条目标识符（snake_case），例如 preferred_language") String key,
            @ToolParam(description = "条目内容") String content,
            ToolContext toolContext) {

        if (agentId == null || type == null || key == null || content == null) {
            return error("agentId, type, key, content 均不能为空");
        }

        try {
            structuredMemoryService.remember(agentId, type.trim().toLowerCase(),
                    key.trim(), content.trim(), "agent", writeOwner(toolContext));

            JSONObject result = new JSONObject();
            result.set("success", true);
            result.set("type", type);
            result.set("key", key);
            result.set("message", "结构化记忆已保存");
            return JSONUtil.toJsonPrettyStr(result);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("[StructuredMemoryTool] remember failed: {}", e.getMessage());
            return error("保存失败: " + e.getMessage());
        }
    }

    @Tool(description = """
            搜索 Agent 的结构化记忆。
            可按类型过滤，也可按关键词搜索（匹配 key 和 content）。
            type 为空时搜索所有类型。
            """)
    public String recall_structured(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "记忆类型过滤（可选）：user / feedback / project / reference", required = false) String type,
            @ToolParam(description = "搜索关键词（可选），匹配 key 和内容", required = false) String keyword,
            ToolContext toolContext) {

        if (agentId == null) {
            return error("agentId 不能为空");
        }

        try {
            List<Map<String, String>> results = structuredMemoryService.recall(
                    agentId,
                    type != null && !type.isBlank() ? type.trim().toLowerCase() : null,
                    keyword,
                    readOwner(toolContext));

            JSONObject result = new JSONObject();
            result.set("agentId", agentId);
            result.set("count", results.size());
            result.set("entries", results);
            return JSONUtil.toJsonPrettyStr(result);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("[StructuredMemoryTool] recall failed: {}", e.getMessage());
            return error("查询失败: " + e.getMessage());
        }
    }

    @Tool(description = """
            删除 Agent 的一条结构化记忆。
            需要指定类型和 key。
            """)
    public String forget_structured(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "记忆类型：user / feedback / project / reference") String type,
            @ToolParam(description = "要删除的条目标识符") String key,
            ToolContext toolContext) {

        if (agentId == null || type == null || key == null) {
            return error("agentId, type, key 均不能为空");
        }

        try {
            boolean removed = structuredMemoryService.forget(agentId,
                    type.trim().toLowerCase(), key.trim(), writeOwner(toolContext));

            JSONObject result = new JSONObject();
            result.set("success", removed);
            result.set("message", removed ? "记忆条目已删除" : "未找到匹配的记忆条目");
            return JSONUtil.toJsonPrettyStr(result);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("[StructuredMemoryTool] forget failed: {}", e.getMessage());
            return error("删除失败: " + e.getMessage());
        }
    }

    private String error(String message) {
        JSONObject result = new JSONObject();
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
