package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Wiki 页面服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiPageService {

    private final WikiPageMapper pageMapper;
    private final ObjectMapper objectMapper;

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)]]");

    /** 页面摘要缓存：kbId → (data, expiresAt)。5 分钟 TTL，写操作失效。 */
    private record CachedSummaries(List<WikiPageEntity> data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
    private final ConcurrentHashMap<Long, CachedSummaries> summaryCache = new ConcurrentHashMap<>();
    private static final long SUMMARY_CACHE_TTL_MS = 5 * 60_000; // 5 分钟

    /** Agent 引用计数器（内存，不持久化，重启归零） */
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> refCounter = new ConcurrentHashMap<>();

    /** 记录 Agent 引用（WikiTool 调用时触发） */
    public void trackReference(Long kbId, String slug) {
        refCounter.computeIfAbsent(kbId + ":" + slug, k -> new java.util.concurrent.atomic.AtomicInteger(0))
                .incrementAndGet();
    }

    /** Agent 引用记录 */
    public record ReferenceEntry(String slug, String title, int refCount) {}

    /** 获取被引用最多的页面 Top N */
    public List<ReferenceEntry> getTopReferenced(Long kbId, int limit) {
        String prefix = kbId + ":";
        return refCounter.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
                .limit(limit)
                .map(e -> {
                    String slug = e.getKey().substring(prefix.length());
                    WikiPageEntity page = getBySlug(kbId, slug);
                    String title = page != null ? page.getTitle() : slug;
                    return new ReferenceEntry(slug, title, e.getValue().get());
                })
                .toList();
    }

    /**
     * RFC-051 PR-7 follow-up: list ONLY archived pages — the inverse of the
     * default {@link #listByKbId} filter. Used by the admin UI's "show archived"
     * panel so users can see what they archived and recover it.
     */
    /**
     * Pages in {@code kbId} created at or after {@code since}, newest first.
     * Used by the hot-cache rebuilder to surface "what was just added";
     * archived pages and system pages (overview/log) are excluded so the
     * snapshot stays focused on user-visible knowledge.
     */
    public List<WikiPageEntity> findRecentCreated(Long kbId, LocalDateTime since, int limit) {
        if (kbId == null || since == null || limit <= 0) return java.util.List.of();
        List<WikiPageEntity> rows = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getArchived, 1)
                        .ne(WikiPageEntity::getPageType, WikiScaffoldService.SYSTEM_PAGE_TYPE)
                        .ge(WikiPageEntity::getCreateTime, since)
                        .orderByDesc(WikiPageEntity::getCreateTime)
                        .last("LIMIT " + limit));
        rows.forEach(p -> p.setContent(null));
        return rows;
    }

    /**
     * Pages in {@code kbId} updated at or after {@code since}, newest first.
     * Same exclusions as {@link #findRecentCreated}.
     *
     * <p>A row that was both created and updated in the window will appear
     * in both lists — the caller deduplicates if needed.
     */
    public List<WikiPageEntity> findRecentUpdated(Long kbId, LocalDateTime since, int limit) {
        if (kbId == null || since == null || limit <= 0) return java.util.List.of();
        List<WikiPageEntity> rows = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getArchived, 1)
                        .ne(WikiPageEntity::getPageType, WikiScaffoldService.SYSTEM_PAGE_TYPE)
                        .ge(WikiPageEntity::getUpdateTime, since)
                        .orderByDesc(WikiPageEntity::getUpdateTime)
                        .last("LIMIT " + limit));
        rows.forEach(p -> p.setContent(null));
        return rows;
    }

    public List<WikiPageEntity> listArchivedByKbId(Long kbId) {
        List<WikiPageEntity> pages = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .eq(WikiPageEntity::getArchived, 1)
                        .orderByDesc(WikiPageEntity::getUpdateTime));
        pages.forEach(p -> p.setContent(null));
        return pages;
    }

    /**
     * 列出知识库的所有页面（不含 content）。
     * RFC-051 PR-7: archived 页面默认不返回。
     */
    public List<WikiPageEntity> listByKbId(Long kbId) {
        List<WikiPageEntity> pages = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getArchived, 1)
                        .orderByAsc(WikiPageEntity::getTitle));
        pages.forEach(p -> p.setContent(null));
        return pages;
    }

    /**
     * 列出知识库所有页面（含 content，用于全文搜索）。
     * RFC-051 PR-7: archived 页面不参与 enrich / 全文搜索遍历。
     */
    public List<WikiPageEntity> listByKbIdWithContent(Long kbId) {
        return pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getArchived, 1)
                        .orderByAsc(WikiPageEntity::getTitle));
    }

    /**
     * 列出页面摘要（用于上下文注入和 LLM 消化）。
     * 带 5 分钟 TTL 缓存，写操作自动失效。
     */
    public List<WikiPageEntity> listSummaries(Long kbId) {
        CachedSummaries cached = summaryCache.get(kbId);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }
        // RFC-051 PR-7: archived pages are hidden from default summary listings;
        // PR-2 added page_type so callers can filter system pages too.
        List<WikiPageEntity> pages = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .select(WikiPageEntity::getSlug, WikiPageEntity::getTitle,
                                WikiPageEntity::getSummary, WikiPageEntity::getLastUpdatedBy,
                                WikiPageEntity::getPageType)
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getArchived, 1)
                        .orderByAsc(WikiPageEntity::getTitle));
        summaryCache.put(kbId, new CachedSummaries(pages, System.currentTimeMillis() + SUMMARY_CACHE_TTL_MS));
        return pages;
    }

    /** 失效指定知识库的摘要缓存（页面增删改时调用） */
    public void evictSummaryCache(Long kbId) {
        summaryCache.remove(kbId);
    }

    /**
     * DB 级别搜索页面（不加载 content CLOB 到 Java 内存）
     */
    public List<WikiPageEntity> searchPages(Long kbId, String query) {
        String escaped = query.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        return pageMapper.searchByKeyword(kbId, pattern);
    }

    public WikiPageEntity getBySlug(Long kbId, String slug) {
        return pageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .eq(WikiPageEntity::getSlug, slug));
    }

    /**
     * 把 slug 规范化为 canonical 形式：去掉所有连字符 / 下划线 + 转小写。
     * <p>
     * 用于跨拼写匹配：{@code "shennong-bencao-jing"} 和 {@code "shen-nong-ben-cao-jing"}
     * 都规范化为 {@code "shennongbencaojing"}，被视为同一概念。LLM 在并行处理大文档时
     * 经常对同一概念给出不同 slug 拼写（按词分组 vs 按字分隔），这是兜底归一逻辑的基础。
     */
    public static String canonicalSlug(String slug) {
        if (slug == null) return "";
        return slug.toLowerCase().replace("-", "").replace("_", "");
    }

    /**
     * 按 canonical slug 在指定 KB 中查找已存在的 page。
     * <p>
     * 命中条件：现有 page 的 slug 经 {@link #canonicalSlug(String)} 后与给定 slug 的
     * canonical 形式相等。复用 {@link #listSummaries(Long)} 的 5 分钟缓存，命中后再
     * {@link #getBySlug(Long, String)} 拿完整 entity，避免额外全表扫描。
     *
     * @return 第一个 canonical 匹配的 page；找不到返回 {@code null}
     */
    public WikiPageEntity findByCanonicalSlug(Long kbId, String slug) {
        String canonical = canonicalSlug(slug);
        if (canonical.isEmpty()) return null;
        for (WikiPageEntity p : listSummaries(kbId)) {
            if (canonicalSlug(p.getSlug()).equals(canonical)) {
                return getBySlug(kbId, p.getSlug());
            }
        }
        return null;
    }

    public WikiPageEntity getById(Long id) {
        return pageMapper.selectById(id);
    }

    /**
     * Direct update by entity (used by enrichment service).
     */
    @Transactional
    public void updateById(WikiPageEntity entity) {
        pageMapper.updateById(entity);
        if (entity.getKbId() != null) {
            evictSummaryCache(entity.getKbId());
        }
    }

    /**
     * Create a new wiki page (without explicit pageType)
     */
    @Transactional
    public WikiPageEntity createPage(Long kbId, String slug, String title, String content,
                                      String summary, String sourceRawIds) {
        return createPage(kbId, slug, title, content, summary, sourceRawIds, null);
    }

    /**
     * Create a new wiki page with explicit pageType classification.
     * pageType is stored lowercase (concept / person / place / event / technology /
     * organization / product / term / process / other).
     */
    @Transactional
    public WikiPageEntity createPage(Long kbId, String slug, String title, String content,
                                      String summary, String sourceRawIds, String pageType) {
        WikiPageEntity entity = new WikiPageEntity();
        entity.setKbId(kbId);
        entity.setSlug(slug);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setSummary(summary);
        entity.setOutgoingLinks(extractLinksAsJson(content));
        entity.setSourceRawIds(sourceRawIds);
        entity.setVersion(1);
        entity.setLastUpdatedBy("ai");
        if (pageType != null && !pageType.isBlank()) {
            entity.setPageType(pageType.toLowerCase());
        }
        pageMapper.insert(entity);
        evictSummaryCache(kbId);
        return entity;
    }

    /**
     * List pages derived from a specific raw material (for UI sidebar filtering).
     * Uses a LIKE search on sourceRawIds JSON field — cheap and dialect-agnostic.
     */
    public List<WikiPageEntity> listBySourceRawId(Long kbId, Long rawId) {
        List<WikiPageEntity> pages = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        // RFC-051 PR-7: a raw's archived pages stop showing up in the
                        // sidebar's "filter by raw" listing. Lineage is still queryable
                        // by hitting the page directly via slug.
                        .ne(WikiPageEntity::getArchived, 1)
                        .like(WikiPageEntity::getSourceRawIds, rawId.toString())
                        .orderByAsc(WikiPageEntity::getTitle));
        pages.forEach(p -> p.setContent(null));
        return pages;
    }

    /**
     * AI 更新页面内容（手动编辑的页面不覆盖内容，仅追加来源）
     */
    @Transactional
    public WikiPageEntity updatePageByAi(Long kbId, String slug, String content,
                                          String summary, Long newRawId) {
        WikiPageEntity existing = getBySlug(kbId, slug);
        if (existing == null) {
            log.warn("[Wiki] Page not found for AI update: kbId={}, slug={}", kbId, slug);
            return null;
        }

        // 手动编辑的页面：AI 不覆盖内容，仅追加来源 raw id
        if ("manual".equals(existing.getLastUpdatedBy())) {
            log.info("[Wiki] Skipping AI content update for manually edited page: kbId={}, slug={}", kbId, slug);
            if (newRawId != null) {
                List<Long> rawIds = parseSourceRawIds(existing.getSourceRawIds());
                if (!rawIds.contains(newRawId)) {
                    rawIds.add(newRawId);
                    existing.setSourceRawIds(toJson(rawIds));
                    existing.setUpdateTime(LocalDateTime.now());
                    pageMapper.updateById(existing);
                    evictSummaryCache(kbId);
                    return getBySlug(kbId, slug); // 从 DB 重新加载确保一致性
                }
            }
            return existing;
        }

        existing.setContent(content);
        existing.setSummary(summary);
        existing.setOutgoingLinks(extractLinksAsJson(content));
        existing.setVersion(existing.getVersion() + 1);
        existing.setLastUpdatedBy("ai");
        existing.setUpdateTime(LocalDateTime.now());

        // 追加新的 source raw id
        if (newRawId != null) {
            List<Long> rawIds = parseSourceRawIds(existing.getSourceRawIds());
            if (!rawIds.contains(newRawId)) {
                rawIds.add(newRawId);
                existing.setSourceRawIds(toJson(rawIds));
            }
        }

        pageMapper.updateById(existing);
        evictSummaryCache(kbId);
        return existing;
    }

    /**
     * RFC-047 P2: Paired source lineage entry (rawId + rawTitle snapshot at ingest time).
     * Keyed by rawId; rawTitle is a snapshot — the raw may be renamed later but lineage stays accurate.
     */
    public record SourceEntry(long rawId, String rawTitle) {}

    /**
     * RFC-047 P2: Merge a (rawId, rawTitle) pair into a page's source lineage.
     * Dual-writes to both sourceEntries (canonical) and sourceRawIds (legacy compat).
     * Idempotent: no-ops if rawId already present.
     */
    @Transactional
    public void mergeSourceLineage(Long pageId, Long rawId, String rawTitle) {
        WikiPageEntity page = pageMapper.selectById(pageId);
        if (page == null) return;

        List<SourceEntry> entries = parseSourceEntries(page.getSourceEntries());
        boolean entryExists = entries.stream().anyMatch(e -> e.rawId() == rawId);

        List<Long> rawIds = parseSourceRawIds(page.getSourceRawIds());
        boolean idExists = rawIds.contains(rawId);

        if (!entryExists) {
            entries.add(new SourceEntry(rawId, rawTitle != null ? rawTitle : ""));
            page.setSourceEntries(toJson(entries));
        }
        if (!idExists) {
            rawIds.add(rawId);
            page.setSourceRawIds(toJson(rawIds));
        }

        if (!entryExists || !idExists) {
            page.setUpdateTime(LocalDateTime.now());
            pageMapper.updateById(page);
            evictSummaryCache(page.getKbId());
        }
    }

    /**
     * 手动更新页面内容
     */
    @Transactional
    public WikiPageEntity updatePageManually(Long kbId, String slug, String content, String summary) {
        WikiPageEntity existing = getBySlug(kbId, slug);
        if (existing == null) {
            throw new IllegalArgumentException("Page not found: " + slug);
        }
        existing.setContent(content);
        existing.setOutgoingLinks(extractLinksAsJson(content));
        existing.setVersion(existing.getVersion() + 1);
        existing.setLastUpdatedBy("manual");
        existing.setUpdateTime(LocalDateTime.now());
        // 同步更新摘要，防止与 content 漂移
        if (summary != null) {
            existing.setSummary(summary);
        } else {
            // 无显式摘要时，从 content 首段提取
            existing.setSummary(extractFirstParagraph(content));
        }
        pageMapper.updateById(existing);
        evictSummaryCache(kbId);
        return existing;
    }

    /**
     * 从 Markdown 内容提取首段作为摘要
     */
    private String extractFirstParagraph(String content) {
        if (content == null || content.isBlank()) return null;
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() && sb.length() > 0) break; // 空行分段
            if (trimmed.startsWith("#")) continue; // 跳过标题行
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(trimmed);
            }
        }
        String para = sb.toString();
        if (para.length() > 300) para = para.substring(0, 300) + "...";
        return para.isEmpty() ? null : para;
    }

    /**
     * 获取反向链接（哪些页面链接到了这个页面）
     */
    public List<WikiPageEntity> getBacklinks(Long kbId, String slug) {
        // 在 outgoing_links JSON 中搜索包含此 slug 的页面
        List<WikiPageEntity> allPages = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getSlug, slug));
        return allPages.stream()
                .filter(p -> p.getOutgoingLinks() != null && p.getOutgoingLinks().contains("\"" + slug + "\""))
                .peek(p -> p.setContent(null))
                .collect(Collectors.toList());
    }

    /**
     * RFC-051 PR-2: a page is protected from AI / tool / batch deletion when
     * either {@code locked == 1} or {@code pageType == "system"}. The system
     * pages ({@code overview} / {@code log}) carry both flags; users may set
     * {@code locked} on individual curated pages without making them system.
     */
    public static boolean isProtected(WikiPageEntity page) {
        if (page == null) return false;
        if (page.getLocked() != null && page.getLocked() == 1) return true;
        return "system".equals(page.getPageType());
    }

    @Transactional
    public void delete(Long kbId, String slug) {
        WikiPageEntity existing = getBySlug(kbId, slug);
        if (existing == null) {
            // Nothing to delete; preserve idempotent behavior.
            return;
        }
        if (isProtected(existing)) {
            log.warn("[Wiki] Refusing to delete protected page kbId={}, slug={}, type={}, locked={}",
                    kbId, slug, existing.getPageType(), existing.getLocked());
            return;
        }
        pageMapper.delete(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .eq(WikiPageEntity::getSlug, slug));
        evictSummaryCache(kbId);
    }

    /**
     * RFC-051 PR-7: flip the {@code archived} flag.
     * <p>
     * Archive hides the page from default list/search/related results without
     * destroying it. Citation lineage and source-raw links survive, so an
     * archived page can still be unarchived later or audited from raw history.
     * Refuses to archive a system page since those are part of the KB's spine.
     *
     * @param archive true to archive, false to unarchive
     * @return true on a state change, false if no-op (page missing or already in target state)
     */
    @Transactional
    public boolean setArchived(Long kbId, String slug, boolean archive) {
        WikiPageEntity existing = getBySlug(kbId, slug);
        if (existing == null) return false;
        if ("system".equals(existing.getPageType())) {
            log.warn("[Wiki] Refusing to archive system page kbId={}, slug={}", kbId, slug);
            return false;
        }
        int target = archive ? 1 : 0;
        if (existing.getArchived() != null && existing.getArchived() == target) return false;
        existing.setArchived(target);
        pageMapper.updateById(existing);
        evictSummaryCache(kbId);
        return true;
    }

    /**
     * 批量删除页面（按 slug 列表）
     */
    @Transactional
    public int batchDelete(Long kbId, List<String> slugs) {
        int count = 0;
        for (String slug : slugs) {
            delete(kbId, slug);
            count++;
        }
        return count;
    }

    /**
     * 删除某材料独占的旧页面（重处理前清理）。
     * 安全策略：只删同时满足以下条件的页面：
     * 1. sourceRawIds 仅包含该 rawId（独占，非共享）
     * 2. lastUpdatedBy != 'manual'（非人工维护）
     * 多来源页面：仅移除该 rawId 引用，保留页面。
     */
    @Transactional
    public int deleteExclusiveBySourceRawId(Long kbId, Long rawId) {
        List<WikiPageEntity> allPages = listByKbId(kbId);
        int deleted = 0;
        for (WikiPageEntity page : allPages) {
            if ("manual".equals(page.getLastUpdatedBy())) continue;
            // RFC-051 PR-2: never sweep system / locked pages, even when their
            // source raw is being reprocessed.
            if (isProtected(page)) continue;
            List<Long> sourceIds = parseSourceRawIds(page.getSourceRawIds());
            if (sourceIds.contains(rawId)) {
                if (sourceIds.size() == 1) {
                    delete(kbId, page.getSlug());
                    deleted++;
                } else {
                    // Multi-source page: remove this rawId from both sourceRawIds and sourceEntries
                    sourceIds.remove(rawId);
                    page.setSourceRawIds(toJson(sourceIds));
                    List<SourceEntry> entries = parseSourceEntries(page.getSourceEntries());
                    entries.removeIf(e -> e.rawId() == rawId);
                    page.setSourceEntries(toJson(entries));
                    pageMapper.updateById(page);
                }
            }
        }
        return deleted;
    }

    public int countByKbId(Long kbId) {
        return Math.toIntExact(pageMapper.selectCount(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)));
    }

    /**
     * Count wiki pages derived from a specific raw material.
     * Uses sourceRawIds JSON array field (e.g. "[123]" or "[123,456]").
     */
    public int countBySourceRawId(Long kbId, Long rawId) {
        // Use LIKE search on sourceRawIds JSON — works for both single and multi-source pages
        return Math.toIntExact(pageMapper.selectCount(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .like(WikiPageEntity::getSourceRawIds, rawId.toString())));
    }

    /**
     * Extract {@code [[links]]} (and {@code [[target|label]]} alias form,
     * RFC-051 PR-5) from Markdown content and return them as a JSON array of
     * canonical slugs.
     * <p>
     * For aliased links the {@code label} part is purely display — only
     * {@code target} feeds slug resolution. Without this split we'd canonicalize
     * "Spring AI|Spring AI Alibaba" as a single slug, polluting outgoingLinks
     * and breaking graph view / backlinks.
     */
    String extractLinksAsJson(String content) {
        if (content == null) return "[]";
        List<String> links = new ArrayList<>();
        Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            int pipe = raw.indexOf('|');
            String target = pipe >= 0 ? raw.substring(0, pipe).trim() : raw;
            if (target.isEmpty()) continue;
            String slug = toSlug(target);
            if (slug.isEmpty()) continue;
            if (!links.contains(slug)) {
                links.add(slug);
            }
        }
        return toJson(links);
    }

    /**
     * 将标题转换为 slug（URL 安全标识符）
     */
    public static String toSlug(String title) {
        if (title == null) return "";
        return title.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private List<Long> parseSourceRawIds(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<SourceEntry> parseSourceEntries(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<SourceEntry>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
