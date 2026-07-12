package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.content.model.ContentItemEntity;
import vip.mate.content.service.ContentItemService;

import java.util.List;

/**
 * Built-in tool: the content calendar / dedup ledger for the content studio.
 * Thin wrapper over {@link ContentItemService}; the package tools also call the
 * service to auto-record on delivery.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code check_recent} — has this topic been produced on this platform in
 *       the last N days? Call BEFORE committing to a topic.</li>
 *   <li>{@code record} — log a produced piece (usually done automatically by the
 *       package tools; available for manual use).</li>
 *   <li>{@code mark_published} — flip an item to published.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentItemTool {

    private static final int DEFAULT_RECENT_DAYS = 14;

    private final ContentItemService contentItemService;

    @Tool(name = "content_item", description = """
        Content calendar / dedup ledger for 公众号 & 小红书 pieces.

        Actions:
          - check_recent: has `topic` already been produced for `platform`
            (gzh|xhs) within the last `days` (default 14)? Call this BEFORE picking
            a topic in a scheduled run, to avoid repeats. Only counts committed
            pieces (packaged/published). Returns whether it's a repeat plus titles.
          - record: log a produced piece (platform, topic, title, status). NOTE:
            gzh_package / xhs_package already auto-record on delivery, so you rarely
            need this by hand.
          - mark_published: set item `id` to published with optional externalRef.
        """)
    public String content_item(
            @ToolParam(description = "Action: check_recent | record | mark_published")
            String action,
            @ToolParam(description = "Platform: gzh (公众号) or xhs (小红书)", required = false)
            String platform,
            @ToolParam(description = "Topic text (check_recent / record)", required = false)
            String topic,
            @ToolParam(description = "Title of the produced piece (record)", required = false)
            String title,
            @ToolParam(description = "Lifecycle status for record: draft|packaged|published", required = false)
            String status,
            @ToolParam(description = "Online preview link (record)", required = false)
            String previewUrl,
            @ToolParam(description = "Platform ref — draft media_id / publish id (record / mark_published)", required = false)
            String externalRef,
            @ToolParam(description = "Recency window in days for check_recent (default 14)", required = false)
            Integer days,
            @ToolParam(description = "Item id (mark_published)", required = false)
            Long id,
            @Nullable ToolContext ctx) {

        String act = action == null ? "" : action.trim().toLowerCase();
        return switch (act) {
            case "check_recent" -> checkRecent(platform, topic, days);
            case "record" -> record(platform, topic, title, status, previewUrl, externalRef, ctx);
            case "mark_published" -> markPublished(id, externalRef);
            default -> "Error: unknown action '" + act + "'. Use check_recent | record | mark_published.";
        };
    }

    private String checkRecent(String platform, String topic, Integer days) {
        if (isBlank(platform) || isBlank(topic)) {
            return "Error: platform and topic are required for check_recent.";
        }
        int window = (days == null || days <= 0) ? DEFAULT_RECENT_DAYS : days;
        List<ContentItemEntity> recent = contentItemService.findRecent(platform, topic, window);
        if (recent.isEmpty()) {
            return "✅ 未重复：最近 " + window + " 天没有在 " + platform + " 做过「" + topic + "」，可以继续。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 疑似重复：最近 ").append(window).append(" 天已在 ").append(platform)
          .append(" 做过同题「").append(topic).append("」").append(recent.size()).append(" 次：\n");
        for (ContentItemEntity e : recent) {
            sb.append("- ").append(e.getCreateTime() != null ? e.getCreateTime().toLocalDate() : "?")
              .append("｜").append(e.getTitle() != null ? e.getTitle() : "(无标题)")
              .append("｜").append(e.getStatus()).append('\n');
        }
        sb.append("建议换个角度或另选选题。");
        return sb.toString();
    }

    private String record(String platform, String topic, String title, String status,
                          String previewUrl, String externalRef, @Nullable ToolContext ctx) {
        if (isBlank(platform) || isBlank(topic)) {
            return "Error: platform and topic are required for record.";
        }
        Long id = contentItemService.record(workspaceFromContext(ctx), platform, topic, title,
                status, previewUrl, externalRef);
        return "✅ 已记入内容日历。item id: " + id + "（status=" + (isBlank(status) ? "packaged" : status) + "）";
    }

    private String markPublished(Long id, String externalRef) {
        if (id == null) {
            return "Error: id is required for mark_published.";
        }
        return contentItemService.markPublished(id, externalRef)
                ? "✅ 已标记为已发布。item id: " + id
                : "Error: content item " + id + " not found.";
    }

    private static Long workspaceFromContext(@Nullable ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        return origin != null && origin.workspaceId() != null ? origin.workspaceId() : 1L;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
