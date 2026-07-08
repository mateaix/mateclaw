package vip.mate.tool.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜索结果内存缓存
 *
 * <p>避免 Agent 同一对话中多次搜索相同/相似 query 时重复调用搜索 API。
 * <ul>
 *   <li>TTL: 15 分钟（搜索结果的时效性与 API quota 节省之间的平衡）</li>
 *   <li>最大条目: 100（超出时淘汰最早插入的条目）</li>
 *   <li>Key: providerId + query + freshness + language + count（归一化为小写）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class SearchCache {

    private static final int MAX_ENTRIES = 100;
    private static final long TTL_MS = 15 * 60 * 1000L; // 15 minutes

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(List<SearchResult> results, long expiresAt, long insertedAt) {
    }

    /**
     * 构建缓存 key（归一化为小写）
     */
    public String buildKey(String providerId, SearchQuery query) {
        return (providerId + ":"
                + query.query() + ":"
                + (query.freshness() != null ? query.freshness() : "") + ":"
                + (query.language() != null ? query.language() : "") + ":"
                + query.resolvedCount()
        ).toLowerCase().trim();
    }

    /**
     * 查询缓存，过期返回 null
     */
    public List<SearchResult> get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            cache.remove(key);
            return null;
        }
        log.debug("搜索缓存命中: {}", key);
        return entry.results();
    }

    /**
     * 写入缓存，超出容量时淘汰最早插入的条目
     */
    public void put(String key, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        // 简易 LRU：超出容量时删除最早的条目
        if (cache.size() >= MAX_ENTRIES) {
            evictOldest();
        }
        long now = System.currentTimeMillis();
        cache.put(key, new CacheEntry(results, now + TTL_MS, now));
    }

    private void evictOldest() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (var entry : cache.entrySet()) {
            if (entry.getValue().insertedAt() < oldestTime) {
                oldestTime = entry.getValue().insertedAt();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            cache.remove(oldestKey);
        }
    }

    /** 当前缓存条目数（用于日志/监控） */
    public int size() {
        return cache.size();
    }
}
