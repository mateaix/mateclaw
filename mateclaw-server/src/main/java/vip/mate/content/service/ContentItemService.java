package vip.mate.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.content.model.ContentItemEntity;
import vip.mate.content.repository.ContentItemMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content calendar / dedup ledger service — the single home for content-item
 * logic, shared by {@code content_item} (the tool), the package tools (which
 * auto-record on delivery), and the read-only content-calendar API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentItemService {

    /** Statuses that count as "already covered" for dedup — a discarded draft doesn't. */
    private static final List<String> COMMITTED_STATUSES = List.of("packaged", "published");
    /** Ignore same-topic rows created within this window, so a record→check in one
     *  run doesn't flag itself as a repeat. */
    private static final long SELF_MATCH_GUARD_MINUTES = 2;

    private final ContentItemMapper contentItemMapper;

    /**
     * Recent committed items with the same topic fingerprint on this platform,
     * within {@code days}, excluding just-created rows (self-match guard). Empty
     * means "not a repeat".
     */
    public List<ContentItemEntity> findRecent(String platform, String topic, int days) {
        LocalDateTime now = LocalDateTime.now();
        return contentItemMapper.selectList(new LambdaQueryWrapper<ContentItemEntity>()
                .eq(ContentItemEntity::getPlatform, platform.trim().toLowerCase())
                .eq(ContentItemEntity::getTopicFingerprint, fingerprint(topic))
                .in(ContentItemEntity::getStatus, COMMITTED_STATUSES)
                .ge(ContentItemEntity::getCreateTime, now.minusDays(days))
                .lt(ContentItemEntity::getCreateTime, now.minusMinutes(SELF_MATCH_GUARD_MINUTES))
                .orderByDesc(ContentItemEntity::getCreateTime));
    }

    /** Record a produced piece; returns the new item id. */
    public Long record(Long workspaceId, String platform, String topic, String title,
                       String status, String previewUrl, String externalRef) {
        ContentItemEntity e = new ContentItemEntity();
        e.setWorkspaceId(workspaceId);
        e.setPlatform(platform.trim().toLowerCase());
        e.setTopic(topic != null ? topic.trim() : null);
        e.setTopicFingerprint(fingerprint(topic != null ? topic : title));
        e.setTitle(title != null ? title.trim() : null);
        e.setStatus(status == null || status.isBlank() ? "packaged" : status.trim().toLowerCase());
        e.setPreviewUrl(previewUrl);
        e.setExternalRef(externalRef);
        contentItemMapper.insert(e);
        log.info("[ContentItem] recorded id={} ws={} platform={} status={} title='{}'",
                e.getId(), workspaceId, e.getPlatform(), e.getStatus(), title);
        return e.getId();
    }

    /** Flip an item to published. Returns false if the id is unknown. */
    public boolean markPublished(Long id, String externalRef) {
        ContentItemEntity e = contentItemMapper.selectById(id);
        if (e == null) {
            return false;
        }
        e.setStatus("published");
        e.setPublishTime(LocalDateTime.now());
        if (externalRef != null && !externalRef.isBlank()) {
            e.setExternalRef(externalRef);
        }
        contentItemMapper.updateById(e);
        log.info("[ContentItem] item {} marked published (ref={})", id, externalRef);
        return true;
    }

    /** Paged content-calendar listing, newest first, optionally filtered by platform / status. */
    public IPage<ContentItemEntity> page(int page, int size, String platform, String status) {
        LambdaQueryWrapper<ContentItemEntity> w = new LambdaQueryWrapper<>();
        if (platform != null && !platform.isBlank()) {
            w.eq(ContentItemEntity::getPlatform, platform.trim().toLowerCase());
        }
        if (status != null && !status.isBlank()) {
            w.eq(ContentItemEntity::getStatus, status.trim().toLowerCase());
        }
        w.orderByDesc(ContentItemEntity::getCreateTime);
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        return contentItemMapper.selectPage(new Page<>(p, s), w);
    }

    /** Counts by status (draft/packaged/published/failed) plus total, for the summary cards. */
    public Map<String, Long> summary() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String s : List.of("draft", "packaged", "published", "failed")) {
            m.put(s, contentItemMapper.selectCount(
                    new LambdaQueryWrapper<ContentItemEntity>().eq(ContentItemEntity::getStatus, s)));
        }
        m.put("total", contentItemMapper.selectCount(null));
        return m;
    }

    /** Stable 32-hex fingerprint of the normalized topic (lowercased, alnum/CJK only). */
    public static String fingerprint(String topic) {
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
}
