package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.content.model.ContentItemEntity;
import vip.mate.content.repository.ContentItemMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Built-in tool: the content calendar / dedup ledger for the content studio.
 * Lets the daily scheduler avoid repeating a topic, records produced pieces, and
 * marks them published so publishing is idempotent and auditable.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code check_recent} — has this topic been produced on this platform in
 *       the last N days? Call BEFORE committing to a topic.</li>
 *   <li>{@code record} — log a produced piece (draft/packaged) with its title and
 *       preview link.</li>
 *   <li>{@code mark_published} — flip an item to published with its platform ref.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentItemTool {

    private static final int DEFAULT_RECENT_DAYS = 14;

    private final ContentItemMapper contentItemMapper;

    @Tool(name = "content_item", description = """
        Content calendar / dedup ledger for 公众号 & 小红书 pieces.

        Actions:
          - check_recent: has `topic` already been produced for `platform`
            (gzh|xhs) within the last `days` (default 14)? Call this BEFORE picking
            a topic in a scheduled run, to avoid repeats. Returns whether it's a
            repeat plus recent titles.
          - record: log a produced piece — platform, topic, title, status
            (draft|packaged|published, default packaged), optional previewUrl /
            externalRef. Returns the item id.
          - mark_published: set item `id` to published with optional externalRef
            (draft media_id / publish id).
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
            Long id) {

        String act = action == null ? "" : action.trim().toLowerCase();
        return switch (act) {
            case "check_recent" -> checkRecent(platform, topic, days);
            case "record" -> record(platform, topic, title, status, previewUrl, externalRef);
            case "mark_published" -> markPublished(id, externalRef);
            default -> "Error: unknown action '" + act + "'. Use check_recent | record | mark_published.";
        };
    }

    private String checkRecent(String platform, String topic, Integer days) {
        if (isBlank(platform) || isBlank(topic)) {
            return "Error: platform and topic are required for check_recent.";
        }
        int window = (days == null || days <= 0) ? DEFAULT_RECENT_DAYS : days;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(window);
        String fp = fingerprint(topic);
        List<ContentItemEntity> recent = contentItemMapper.selectList(
                new LambdaQueryWrapper<ContentItemEntity>()
                        .eq(ContentItemEntity::getPlatform, platform.trim().toLowerCase())
                        .eq(ContentItemEntity::getTopicFingerprint, fp)
                        .ge(ContentItemEntity::getCreateTime, cutoff)
                        .orderByDesc(ContentItemEntity::getCreateTime));
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
                          String previewUrl, String externalRef) {
        if (isBlank(platform) || isBlank(topic)) {
            return "Error: platform and topic are required for record.";
        }
        ContentItemEntity e = new ContentItemEntity();
        e.setPlatform(platform.trim().toLowerCase());
        e.setTopic(topic.trim());
        e.setTopicFingerprint(fingerprint(topic));
        e.setTitle(title != null ? title.trim() : null);
        e.setStatus(isBlank(status) ? "packaged" : status.trim().toLowerCase());
        e.setPreviewUrl(previewUrl);
        e.setExternalRef(externalRef);
        contentItemMapper.insert(e);
        log.info("[ContentItem] recorded id={} platform={} status={} title='{}'",
                e.getId(), e.getPlatform(), e.getStatus(), title);
        return "✅ 已记入内容日历。item id: " + e.getId() + "（status=" + e.getStatus() + "）";
    }

    private String markPublished(Long id, String externalRef) {
        if (id == null) {
            return "Error: id is required for mark_published.";
        }
        ContentItemEntity e = contentItemMapper.selectById(id);
        if (e == null) {
            return "Error: content item " + id + " not found.";
        }
        e.setStatus("published");
        e.setPublishTime(LocalDateTime.now());
        if (!isBlank(externalRef)) {
            e.setExternalRef(externalRef);
        }
        contentItemMapper.updateById(e);
        log.info("[ContentItem] item {} marked published (ref={})", id, externalRef);
        return "✅ 已标记为已发布。item id: " + id;
    }

    /** Stable 32-hex fingerprint of the normalized topic (lowercased, alnum/CJK only). */
    static String fingerprint(String topic) {
        String normalized = topic == null ? "" : topic.toLowerCase()
                .replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+", "");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
