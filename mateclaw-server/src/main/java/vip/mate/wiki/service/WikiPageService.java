package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.audit.service.AuditEventService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRelationEntity;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiRelationMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private final WikiLinkService linkService;
    // Cascade dependencies — optional via setter so the legacy unit-test
    // constructor (mapper + ObjectMapper + linkService) still compiles. In
    // production these are auto-wired through the field setters Lombok
    // generates from @Setter on Spring's post-construct path.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WikiRelationMapper relationMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditEventService auditEventService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WikiProperties wikiProperties;

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

    /**
     * Lightweight page reference for client-side wikilink resolution.
     * <p>
     * Carries only {slug, title, archived} — no content, no source, no enrichment
     * fields. Designed so the frontend can build a slug/title lookup map without
     * dragging full page entities (each of which can be tens of KB once content
     * is loaded). The {@code archived} flag lets the renderer pick the correct
     * visual state (active link vs archived link vs broken span) without a
     * second roundtrip.
     */
    public record PageRef(String slug, String title, boolean archived) {}

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
     * List all wikilink resolution refs in a knowledge base.
     * <p>
     * The frontend wikilink resolver needs a complete {slug → page} index that
     * is independent of the user's raw-material filter and unaffected by lazy
     * pagination. {@link #listByKbId} only returns non-archived rows and is
     * filtered by the UI's selected raw, so it cannot back wikilink resolution.
     * This method serves the dedicated {@code GET /pages/refs} endpoint and
     * returns minimal projections (slug + title + archived flag).
     * <p>
     * When {@code includeArchived} is false (default), reuses the 5-minute
     * summary cache for free; archived pages are absent there by construction.
     * When true, runs a fresh query selecting only the three projected columns
     * — uncached, because archived links appear on a small subset of pages and
     * are not worth caching invalidation complexity.
     *
     * @param kbId             knowledge base
     * @param includeArchived  true to include archived=1 rows; false (default)
     *                         returns only active pages
     */
    public List<PageRef> listAllRefs(Long kbId, boolean includeArchived) {
        if (!includeArchived) {
            return listSummaries(kbId).stream()
                    .map(p -> new PageRef(p.getSlug(), p.getTitle(), false))
                    .toList();
        }
        List<WikiPageEntity> rows = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .select(WikiPageEntity::getSlug, WikiPageEntity::getTitle,
                                WikiPageEntity::getArchived)
                        .eq(WikiPageEntity::getKbId, kbId)
                        .orderByAsc(WikiPageEntity::getTitle));
        return rows.stream()
                .map(p -> new PageRef(p.getSlug(), p.getTitle(),
                        p.getArchived() != null && p.getArchived() == 1))
                .toList();
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
        entity.setSourceRawIds(sourceRawIds);
        entity.setVersion(1);
        entity.setLastUpdatedBy("ai");
        if (pageType != null && !pageType.isBlank()) {
            entity.setPageType(pageType.toLowerCase());
        }
        // Compute outgoing_links + broken_links + scanned_at from the new
        // content in the same transaction. See {@link #applyLinkAnalysis}.
        applyLinkAnalysis(entity);
        pageMapper.insert(entity);
        evictSummaryCache(kbId);
        return entity;
    }

    /**
     * Apply schema-validated structured metadata to an existing page via a
     * partial column update — only the metadata columns are written, so this
     * never disturbs content / summary / links set by the ingest pipeline.
     * Null arguments are written as-is (e.g. clearing a prior validation set).
     */
    public void applyMetadata(Long pageId, String metadataJson, String validationStatus,
                              String validationJson, Integer profileVersion) {
        if (pageId == null) {
            return;
        }
        pageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiPageEntity>()
                .eq(WikiPageEntity::getId, pageId)
                .set(WikiPageEntity::getMetadataJson, metadataJson)
                .set(WikiPageEntity::getMetadataValidationStatus, validationStatus)
                .set(WikiPageEntity::getMetadataValidationJson, validationJson)
                .set(WikiPageEntity::getProfileVersion, profileVersion));
    }

    /** Set only a page's knowledge layer via a partial update (leaves depends_on untouched). */
    public void setKnowledgeLayer(Long pageId, String knowledgeLayer) {
        if (pageId == null || knowledgeLayer == null) {
            return;
        }
        pageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiPageEntity>()
                .eq(WikiPageEntity::getId, pageId)
                .set(WikiPageEntity::getKnowledgeLayer, knowledgeLayer));
    }

    /** Set a page's knowledge layer and depends-on snapshot via a partial update. */
    public void setLayerAndDependencies(Long pageId, String knowledgeLayer, String dependsOnJson) {
        if (pageId == null) {
            return;
        }
        pageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiPageEntity>()
                .eq(WikiPageEntity::getId, pageId)
                .set(WikiPageEntity::getKnowledgeLayer, knowledgeLayer)
                .set(WikiPageEntity::getDependsOnJson, dependsOnJson));
    }

    /** Mark a batch of pages stale with a shared reason JSON via a partial update. */
    public int markStale(java.util.Collection<Long> pageIds, String staleReasonJson) {
        if (pageIds == null || pageIds.isEmpty()) {
            return 0;
        }
        return pageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiPageEntity>()
                .in(WikiPageEntity::getId, pageIds)
                .set(WikiPageEntity::getStale, 1)
                .set(WikiPageEntity::getStaleReasonJson, staleReasonJson));
    }

    /** Clear the stale flag on a single page (e.g. after regeneration). */
    public void clearStale(Long pageId) {
        if (pageId == null) {
            return;
        }
        pageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiPageEntity>()
                .eq(WikiPageEntity::getId, pageId)
                .set(WikiPageEntity::getStale, 0)
                .set(WikiPageEntity::getStaleReasonJson, null));
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
        existing.setVersion(existing.getVersion() + 1);
        existing.setLastUpdatedBy("ai");
        existing.setUpdateTime(LocalDateTime.now());
        applyLinkAnalysis(existing);

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
        existing.setVersion(existing.getVersion() + 1);
        existing.setLastUpdatedBy("manual");
        existing.setUpdateTime(LocalDateTime.now());
        applyLinkAnalysis(existing);
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

        // Snapshot the title BEFORE the row goes away. Referrer rewrites
        // demote `[[slug]]` to plain text using the title as the visible
        // word; without the snapshot the demotion would fall back to the
        // raw slug, which reads worse.
        Long pageId = existing.getId();
        String snapshotTitle = (existing.getTitle() != null && !existing.getTitle().isBlank())
                ? existing.getTitle() : slug;

        // Cascade-rewrite every other page that linked to this slug. Feature-
        // flagged so a hypothetical content-mangling regression has a
        // production kill-switch; default-on because the legacy behaviour
        // (just dropping the row) left dangling [[slug]] tokens that this
        // RFC exists to eliminate.
        List<Long> affectedReferrers = java.util.Collections.emptyList();
        boolean cascadeOn = wikiProperties == null || wikiProperties.isCascadeDeleteEnabled();
        if (cascadeOn) {
            try {
                affectedReferrers = cascadeStripReferrers(kbId, pageId, slug, snapshotTitle);
            } catch (RuntimeException e) {
                // Don't fail the delete on a referrer-rewrite hiccup — the
                // page itself coming out is the user's primary intent; lint
                // will catch any stragglers on the next scan.
                log.warn("[Wiki] Cascade rewrite failed for slug={} (continuing with delete): {}",
                        slug, e.toString());
            }
        }

        // Defensive relation-cache cleanup. The mate_wiki_relation table is
        // currently a reserved cache (no production writer today), but we
        // wipe matching rows anyway so a future writer that populates it
        // can't strand entries pointing at a deleted page.
        if (relationMapper != null) {
            try {
                relationMapper.delete(
                        new LambdaQueryWrapper<WikiRelationEntity>()
                                .eq(WikiRelationEntity::getKbId, kbId)
                                .and(w -> w.eq(WikiRelationEntity::getPageAId, pageId)
                                        .or().eq(WikiRelationEntity::getPageBId, pageId)));
            } catch (RuntimeException e) {
                log.warn("[Wiki] Failed to purge mate_wiki_relation rows for pageId={}: {}",
                        pageId, e.toString());
            }
        }

        pageMapper.delete(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .eq(WikiPageEntity::getSlug, slug));
        evictSummaryCache(kbId);

        // Audit event runs after the row is gone so the resourceId reflects
        // the actual deletion. Async insert means a failing audit log won't
        // poison the transaction.
        if (auditEventService != null) {
            try {
                String detail = objectMapper.writeValueAsString(java.util.Map.of(
                        "kbId", kbId,
                        "slug", slug,
                        "title", snapshotTitle,
                        "affectedPageIds", affectedReferrers,
                        "cascadeEnabled", cascadeOn));
                auditEventService.record("wiki.page.delete", "wiki_page",
                        String.valueOf(pageId), snapshotTitle, detail);
            } catch (Exception e) {
                log.debug("[Wiki] Audit event emit failed for delete kbId={} slug={}: {}",
                        kbId, slug, e.toString());
            }
        }
    }

    /**
     * Walk every page in {@code kbId} that links to {@code targetSlug},
     * rewrite the wikilink to plain text via the parser, and persist the
     * referrer with refreshed outgoing_links + broken_links. Returns the
     * affected page ids so the caller can include them in the audit event.
     * <p>
     * Candidate set comes from {@link WikiPageMapper#findReferrersByOutgoingLink}
     * (a LIKE pre-filter on {@code outgoing_links}). Each candidate is then
     * verified by re-extracting outlinks from its content — LIKE matches on
     * the raw JSON column can include false positives if the slug happens
     * to appear as a substring of another value, so we trust the parser as
     * the final word.
     */
    private List<Long> cascadeStripReferrers(Long kbId, Long deletedPageId,
                                              String deletedSlug, String snapshotTitle) {
        // outgoing_links is stored as a JSON array of lowercased strings, so
        // we wrap with quotes to anchor the match to a full JSON element
        // rather than any substring match.
        String slugLower = deletedSlug.toLowerCase(Locale.ROOT);
        String likePattern = "%\"" + slugLower + "\"%";
        List<WikiPageEntity> candidates = pageMapper.findReferrersByOutgoingLink(
                kbId, deletedPageId, likePattern);
        if (candidates.isEmpty()) return List.of();

        // Pre-compute the active slug set ONCE for the recompute pass — every
        // referrer's broken_links recompute would otherwise re-trigger the
        // summary query.
        Set<String> activeSlugs;
        try {
            activeSlugs = linkService.lowercaseSlugSet(listSummaries(kbId));
        } catch (RuntimeException e) {
            activeSlugs = java.util.Collections.emptySet();
        }
        // The deleted page is, by construction, no longer "active" — remove
        // its slug from the set so any referrers' broken_links recompute
        // doesn't accidentally still resolve `[[deletedSlug]]` in their
        // (now-rewritten) content.
        if (!activeSlugs.contains(slugLower)) {
            // already missing — common case
        } else {
            Set<String> trimmed = new HashSet<>(activeSlugs);
            trimmed.remove(slugLower);
            activeSlugs = trimmed;
        }

        List<Long> affected = new ArrayList<>(candidates.size());
        for (WikiPageEntity referrer : candidates) {
            String originalContent = referrer.getContent();
            if (originalContent == null) continue;
            String rewritten = linkService.stripDeletedLink(originalContent, deletedSlug, snapshotTitle);
            if (rewritten.equals(originalContent)) {
                // LIKE matched but parser found no real wikilink — pure
                // false-positive (e.g. slug appeared as substring inside an
                // alias of an unrelated link). Skip.
                continue;
            }

            // Recompute outgoing + broken from the rewritten content, including
            // the referrer's own slug so any self-links remain non-broken.
            Set<String> activeForThisReferrer = activeSlugs;
            if (referrer.getSlug() != null && !referrer.getSlug().isBlank()) {
                Set<String> withSelf = new HashSet<>(activeSlugs);
                withSelf.add(referrer.getSlug().toLowerCase(Locale.ROOT));
                activeForThisReferrer = withSelf;
            }
            WikiLinkService.LinkAnalysis a = linkService.analyze(rewritten, activeForThisReferrer);

            // LambdaUpdateWrapper — content, summary, outgoing_links and
            // broken_links all carry FieldStrategy.ALWAYS on WikiPageEntity,
            // so a partial-entity updateById would generate SET summary=NULL
            // (and clear any other ALWAYS column we didn't explicitly set).
            // The wrapper-based update only writes the four columns we mean
            // to touch, leaving summary and the rest intact.
            pageMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiPageEntity>()
                            .eq(WikiPageEntity::getId, referrer.getId())
                            .set(WikiPageEntity::getContent, rewritten)
                            .set(WikiPageEntity::getOutgoingLinks, linkService.toJsonArray(a.outgoingLinks()))
                            .set(WikiPageEntity::getBrokenLinks, linkService.toJsonArray(a.brokenLinks()))
                            .set(WikiPageEntity::getBrokenLinksScannedAt, LocalDateTime.now()));
            affected.add(referrer.getId());
        }
        return affected;
    }

    /**
     * Rename a page from {@code oldSlug} to {@code newSlug}.
     * <p>
     * Updates the page row's slug AND rewrites every referrer's
     * {@code [[oldSlug]]} (and {@code [[oldSlug|alias]]}) to point at the
     * new slug, preserving aliases. Both pieces run in the same transaction
     * so a partial rename can never leave a "page exists at new slug but
     * referrers still point at old slug" inconsistency.
     *
     * @return the renamed page entity, or {@code null} if {@code oldSlug}
     *         didn't exist
     * @throws IllegalArgumentException if {@code newSlug} is blank, equals
     *         the current slug, or collides with another page in the same KB
     */
    @Transactional
    public WikiPageEntity rename(Long kbId, String oldSlug, String newSlug) {
        if (newSlug == null || newSlug.isBlank()) {
            throw new IllegalArgumentException("new slug must not be blank");
        }
        if (newSlug.equals(oldSlug)) {
            throw new IllegalArgumentException("new slug equals old slug — no-op");
        }
        WikiPageEntity existing = getBySlug(kbId, oldSlug);
        if (existing == null) return null;
        if (isProtected(existing)) {
            throw new IllegalStateException("page is protected (system or locked), refusing to rename");
        }
        WikiPageEntity collision = getBySlug(kbId, newSlug);
        // The collision-check has to ignore "renaming yourself" — on
        // case-insensitive DB collations (e.g. MySQL's default
        // utf8mb4_unicode_ci), getBySlug returns the SAME row when
        // newSlug differs from oldSlug only in case. Treating that as a
        // collision would forbid case-only renames on MySQL while H2
        // (case-sensitive) silently allowed them, producing an
        // environment-dependent error. Comparing ids makes the rule
        // identical on both backends: only a row owned by a different
        // page is a true collision.
        if (collision != null && !existing.getId().equals(collision.getId())) {
            throw new IllegalArgumentException("a page with slug '" + newSlug + "' already exists in this KB");
        }

        Long pageId = existing.getId();
        // Update the row's own slug first so referrer rewrites that include
        // a self-link to the same page (rare but possible — e.g. a "see also"
        // anchor) resolve to the new slug as well.
        existing.setSlug(newSlug);
        existing.setUpdateTime(LocalDateTime.now());
        pageMapper.updateById(existing);
        evictSummaryCache(kbId);

        List<Long> affected = java.util.Collections.emptyList();
        boolean cascadeOn = wikiProperties == null || wikiProperties.isCascadeDeleteEnabled();
        if (cascadeOn) {
            try {
                affected = cascadeRenameReferrers(kbId, pageId, oldSlug, newSlug);
            } catch (RuntimeException e) {
                log.warn("[Wiki] Cascade rename failed for {}→{} (continuing): {}",
                        oldSlug, newSlug, e.toString());
            }
        }

        if (auditEventService != null) {
            try {
                String detail = objectMapper.writeValueAsString(java.util.Map.of(
                        "kbId", kbId,
                        "oldSlug", oldSlug,
                        "newSlug", newSlug,
                        "affectedPageIds", affected,
                        "cascadeEnabled", cascadeOn));
                auditEventService.record("wiki.page.rename", "wiki_page",
                        String.valueOf(pageId), existing.getTitle(), detail);
            } catch (Exception e) {
                log.debug("[Wiki] Audit event emit failed for rename: {}", e.toString());
            }
        }

        return existing;
    }

    /**
     * Mirror of {@link #cascadeStripReferrers} for the rename path —
     * replaces {@code [[oldSlug]]} with {@code [[newSlug]]} (preserving the
     * wikilink form and any alias) instead of demoting to plain text.
     */
    private List<Long> cascadeRenameReferrers(Long kbId, Long renamedPageId,
                                                String oldSlug, String newSlug) {
        String slugLower = oldSlug.toLowerCase(Locale.ROOT);
        String likePattern = "%\"" + slugLower + "\"%";
        List<WikiPageEntity> candidates = pageMapper.findReferrersByOutgoingLink(
                kbId, renamedPageId, likePattern);
        if (candidates.isEmpty()) return List.of();

        Set<String> activeSlugs;
        try {
            activeSlugs = linkService.lowercaseSlugSet(listSummaries(kbId));
        } catch (RuntimeException e) {
            activeSlugs = java.util.Collections.emptySet();
        }
        // The renamed page is now under newSlug; oldSlug is gone, newSlug
        // should resolve. listSummaries has been evicted above so this picks
        // up the new row when re-queried, but be defensive in case the cache
        // hasn't repopulated yet.
        Set<String> activeBase = new HashSet<>(activeSlugs);
        activeBase.remove(slugLower);
        activeBase.add(newSlug.toLowerCase(Locale.ROOT));
        activeSlugs = activeBase;

        List<Long> affected = new ArrayList<>(candidates.size());
        for (WikiPageEntity referrer : candidates) {
            String originalContent = referrer.getContent();
            if (originalContent == null) continue;
            String rewritten = linkService.renameLink(originalContent, oldSlug, newSlug);
            if (rewritten.equals(originalContent)) continue;

            Set<String> activeForThisReferrer = activeSlugs;
            if (referrer.getSlug() != null && !referrer.getSlug().isBlank()) {
                Set<String> withSelf = new HashSet<>(activeSlugs);
                withSelf.add(referrer.getSlug().toLowerCase(Locale.ROOT));
                activeForThisReferrer = withSelf;
            }
            WikiLinkService.LinkAnalysis a = linkService.analyze(rewritten, activeForThisReferrer);

            // LambdaUpdateWrapper to avoid the FieldStrategy.ALWAYS-induced
            // null overwrite on summary (and other ALWAYS columns we don't
            // touch in a rename).
            pageMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiPageEntity>()
                            .eq(WikiPageEntity::getId, referrer.getId())
                            .set(WikiPageEntity::getContent, rewritten)
                            .set(WikiPageEntity::getOutgoingLinks, linkService.toJsonArray(a.outgoingLinks()))
                            .set(WikiPageEntity::getBrokenLinks, linkService.toJsonArray(a.brokenLinks()))
                            .set(WikiPageEntity::getBrokenLinksScannedAt, LocalDateTime.now()));
            affected.add(referrer.getId());
        }
        return affected;
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
     * Extract {@code [[links]]} (and {@code [[target|label]]} alias form)
     * from Markdown content and return them as a JSON array of lowercased
     * target strings. Code blocks are skipped by {@link WikiLinkService}.
     * <p>
     * Behaviour change vs. the historical implementation: previously every
     * target was run through {@link #toSlug} (lowercase + strip + dash-collapse),
     * which silently coerced {@code [[Transformer Architecture]]} into
     * {@code transformer-architecture} regardless of whether such a page slug
     * actually existed. The new implementation preserves what the author
     * wrote (only lowercased + trimmed). The lint compares this against
     * {@code page.slug.toLowerCase()} so any title-form legacy content is
     * surfaced as broken — exactly the gap the wikilink overhaul exists to
     * close. The frontend resolver keeps a title fallback so the visible
     * link still navigates during the transition.
     * <p>
     * Kept public for callers outside this service (e.g. enrichment) that
     * still need the JSON-array serialisation; delegates to
     * {@link WikiLinkService} so there is exactly one extraction code path.
     */
    public String extractLinksAsJson(String content) {
        Set<String> outlinks = linkService.extractOutlinks(content);
        return linkService.toJsonArray(new ArrayList<>(outlinks));
    }

    /**
     * Compute and apply {@code outgoing_links} + {@code broken_links} +
     * {@code broken_links_scanned_at} fields on an entity from its content.
     * Called from every save/update path so the lint state is always in sync
     * with the content actually being persisted (same transaction). Excludes
     * the entity itself from the active-slug set when an id is present, so
     * self-links resolve correctly even when the entity is mid-update.
     */
    private void applyLinkAnalysis(WikiPageEntity entity) {
        if (entity == null || entity.getKbId() == null) return;
        // Fetch the active slug set defensively — in fully-wired production
        // context this never fails, but unit tests that mock the mapper can
        // trip MyBatis-Plus's lambda-cache lookup (TableInfo isn't seeded
        // outside a Spring context). Treating a fetch failure as "empty slug
        // set" means link analysis still runs (so the test verifies the
        // update path) and every extracted target is recorded as broken —
        // which is harmless because tests don't assert on broken_links
        // values, and production code paths never hit this branch.
        Set<String> activeSlugs;
        try {
            activeSlugs = linkService.lowercaseSlugSet(listSummaries(entity.getKbId()));
        } catch (RuntimeException e) {
            log.warn("[Wiki] applyLinkAnalysis: failed to load slug set for kbId={}, treating as empty: {}",
                    entity.getKbId(), e.toString());
            activeSlugs = java.util.Collections.emptySet();
        }
        // Include self-slug so [[my-own-slug]] doesn't appear as broken on the
        // very save that creates the page (listSummaries may not see it yet
        // depending on cache state).
        if (entity.getSlug() != null && !entity.getSlug().isBlank()) {
            Set<String> withSelf = new HashSet<>(activeSlugs);
            withSelf.add(entity.getSlug().toLowerCase(Locale.ROOT));
            activeSlugs = withSelf;
        }
        WikiLinkService.LinkAnalysis a = linkService.analyze(entity.getContent(), activeSlugs);
        entity.setOutgoingLinks(linkService.toJsonArray(a.outgoingLinks()));
        entity.setBrokenLinks(linkService.toJsonArray(a.brokenLinks()));
        entity.setBrokenLinksScannedAt(LocalDateTime.now());
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
