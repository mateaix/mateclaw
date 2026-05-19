package vip.mate.wiki.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.wiki.dto.*;
import vip.mate.wiki.job.WikiProcessingJobService;
import vip.mate.wiki.job.event.WikiJobCreatedEvent;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.model.WikiTransformationRunEntity;
import vip.mate.wiki.repository.WikiRawMaterialMapper;
import vip.mate.wiki.service.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Wiki knowledge base tools for agent conversations.
 * <p>
 * All tools auto-resolve kbId from agentId; LLM never needs to pass it directly.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class WikiTool {

    private final WikiPageService pageService;
    private final WikiKnowledgeBaseService kbService;
    private final WikiRawMaterialService rawService;
    private final HybridRetriever hybridRetriever;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private WikiRelationService relationService;

    @Autowired(required = false)
    private WikiProcessingJobService jobService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private WikiRawMaterialMapper rawMaterialMapper;

    /** RFC-051 PR-4: optional on-demand compile. Tool surface skipped when missing. */
    @Autowired(required = false)
    private WikiCompileService compileService;

    /** Optional transformation engine. Tools degrade with a clear error when missing. */
    @Autowired(required = false)
    private WikiTransformationService transformationService;

    @Autowired(required = false)
    private WikiTransformationExecutor transformationExecutor;

    @Autowired(required = false)
    private WikiTransformationAggregator transformationAggregator;

    public WikiTool(WikiPageService pageService,
                     WikiKnowledgeBaseService kbService,
                     WikiRawMaterialService rawService,
                     HybridRetriever hybridRetriever,
                     ObjectMapper objectMapper) {
        this.pageService = pageService;
        this.kbService = kbService;
        this.rawService = rawService;
        this.hybridRetriever = hybridRetriever;
        this.objectMapper = objectMapper;
    }

    // ==================== RFC-032: Enhanced wiki_read_page ====================

    @Tool(description = """
            Read a wiki page. Use maxChars to limit size (recommended: 3000-6000 for most tasks).
            Use sectionHeading to read only one section by its heading text.
            The result includes a "sourceFiles" field listing the source documents this page was derived from.
            When using content from this page in your answer, cite the page title and source files.
            """)
    public String wiki_read_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Page slug") String slug,
            @ToolParam(description = "Max characters to return (null = full page)", required = false) Integer maxChars,
            @ToolParam(description = "Section heading to extract (null = all sections)", required = false) String sectionHeading) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: " + slug);
        }

        pageService.trackReference(kbId, slug);

        String content = page.getContent();

        if (sectionHeading != null && !sectionHeading.isBlank()) {
            content = extractSection(content, sectionHeading);
        }
        if (maxChars != null && maxChars > 0) {
            content = applyMaxChars(content, maxChars);
        }

        JSONObject result = JSONUtil.createObj()
                .set("title", page.getTitle())
                .set("slug", page.getSlug())
                .set("version", page.getVersion())
                .set("lastUpdatedBy", page.getLastUpdatedBy())
                .set("content", content)
                .set("sourceFiles", resolveSourceFiles(page.getSourceRawIds()));
        return result.toString();
    }

    // ==================== RFC-032: Enhanced wiki_list_pages ====================

    @Tool(description = """
            List wiki pages. Add query to filter by title keyword (max 30 results).
            Without query returns all pages (use only for small KBs).
            """)
    public String wiki_list_pages(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Title keyword filter (optional)", required = false) String query) {

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        List<WikiPageLite> pages;
        if (query != null && !query.isBlank()) {
            List<Long> ids = pageService.searchPages(kbId, query).stream()
                    .filter(p -> !"system".equals(p.getPageType()))
                    .map(WikiPageEntity::getId).limit(30).toList();
            if (ids.isEmpty()) {
                pages = List.of();
            } else {
                pages = pageService.listSummaries(kbId).stream()
                        .filter(p -> !"system".equals(p.getPageType()))
                        .filter(p -> ids.stream().anyMatch(id -> Objects.equals(id, p.getId())))
                        .map(p -> new WikiPageLite(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getPageType()))
                        .toList();
            }
        } else {
            // RFC-051 PR-2: hide system pages (overview / log) from default listings.
            // Agents can still wiki_read_page("overview") explicitly.
            pages = pageService.listSummaries(kbId).stream()
                    .filter(p -> !"system".equals(p.getPageType()))
                    .map(p -> new WikiPageLite(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getPageType()))
                    .toList();
        }

        JSONArray arr = new JSONArray();
        for (WikiPageLite page : pages) {
            arr.add(JSONUtil.createObj()
                    .set("slug", page.slug())
                    .set("title", page.title())
                    .set("summary", page.summary()));
        }

        return JSONUtil.createObj()
                .set("kbId", kbId)
                .set("pageCount", pages.size())
                .set("pages", arr)
                .toString();
    }

    // ==================== RFC-032: Enhanced wiki_search_pages ====================

    @Tool(description = """
            Search wiki pages. Returns snippet so you can judge relevance without reading the full page.
            Default topK=5 is sufficient for most queries.
            Each result includes "slug" and "title" — use wiki_read_page to get full content.
            When using wiki information in your answer, always cite the source page title.
            """)
    public String wiki_search_pages(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Search query") String query,
            @ToolParam(description = "Mode: keyword|semantic|hybrid (default: hybrid)", required = false) String mode,
            @ToolParam(description = "Max results (default 5, max 20)", required = false) Integer topK) {

        if (query == null || query.isBlank()) {
            return error("query is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;
        List<PageSearchResult> results = hybridRetriever.search(kbId, query, mode, k);

        for (PageSearchResult r : results) {
            pageService.trackReference(kbId, r.slug());
        }

        JSONArray arr = new JSONArray();
        for (PageSearchResult r : results) {
            arr.add(JSONUtil.createObj()
                    .set("slug", r.slug())
                    .set("title", r.title())
                    .set("snippet", r.snippet() != null ? r.snippet() : r.summary())
                    .set("matchedBy", r.matchedBy())
                    .set("reason", r.reason() != null ? r.reason() : "")
                    .set("score", String.format("%.4f", r.score())));
        }

        return JSONUtil.createObj()
                .set("kbId", kbId)
                .set("query", query)
                .set("mode", mode != null ? mode : "hybrid")
                .set("matchCount", results.size())
                .set("pages", arr)
                .toString();
    }

    // ==================== RFC-032: N+1 fixed wiki_semantic_search ====================

    @Tool(description = """
            Chunk-level semantic search in the wiki knowledge base.
            Returns raw text fragments closest to the query with similarity scores and source page title.
            Use when wiki_search_pages results are not specific enough.
            When using retrieved content in your answer, cite the source page title shown in each result.
            """)
    public String wiki_semantic_search(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Natural language query") String query,
            @ToolParam(description = "Max results (default 5)", required = false) Integer topK) {

        if (query == null || query.isBlank()) {
            return error("query is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;
        List<HybridRetriever.ChunkHit> hits = hybridRetriever.searchChunks(kbId, query, k);

        if (hits.isEmpty()) {
            return JSONUtil.createObj()
                    .set("kbId", kbId)
                    .set("query", query)
                    .set("matchCount", 0)
                    .set("message", "No semantic matches found. Try wiki_search_pages with mode=keyword.")
                    .toString();
        }

        // RFC-032: Batch-fetch raw titles (N+1 fix)
        Set<Long> rawIds = hits.stream().map(HybridRetriever.ChunkHit::rawId).collect(Collectors.toSet());
        Map<Long, String> rawTitles;
        if (rawMaterialMapper != null && !rawIds.isEmpty()) {
            rawTitles = rawMaterialMapper.selectBatchTitles(rawIds)
                    .stream().collect(Collectors.toMap(RawTitleRef::id, RawTitleRef::title));
        } else {
            rawTitles = Map.of();
        }

        JSONArray arr = new JSONArray();
        for (HybridRetriever.ChunkHit hit : hits) {
            cn.hutool.json.JSONObject obj = JSONUtil.createObj()
                    .set("chunkId", hit.chunkId())
                    .set("rawTitle", rawTitles.getOrDefault(hit.rawId(), "unknown"))
                    .set("snippet", hit.snippet())
                    .set("score", String.format("%.4f", hit.score()));
            // RFC-051 PR-1c: surface chunk metadata when available so the agent
            // can cite "page 12, section 'Setup / Linux'" rather than an opaque snippet.
            if (hit.pageNumber() != null) obj.set("pageNumber", hit.pageNumber());
            if (hit.headerBreadcrumb() != null && !hit.headerBreadcrumb().isBlank()) {
                obj.set("section", hit.headerBreadcrumb());
            }
            arr.add(obj);
        }

        return JSONUtil.createObj()
                .set("kbId", kbId)
                .set("query", query)
                .set("matchCount", hits.size())
                .set("chunks", arr)
                .toString();
    }

    @Tool(description = """
            Trace the source raw materials for a wiki page.
            Returns file names, types, and paths of the original documents.
            """)
    public String wiki_trace_source(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Page slug") String slug) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: " + slug);
        }

        return JSONUtil.createObj()
                .set("pageTitle", page.getTitle())
                .set("pageSlug", page.getSlug())
                .set("sourceFiles", resolveSourceFiles(page.getSourceRawIds()))
                .toString();
    }

    @Tool(description = """
            Create a new wiki page. Used to save task results, analysis reports, etc.
            Content should be Markdown. Slug is auto-generated from title.
            """)
    public String wiki_create_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Page title") String title,
            @ToolParam(description = "Page content (Markdown)") String content) {

        if (title == null || title.isBlank()) {
            return error("title is required");
        }
        if (content == null || content.isBlank()) {
            return error("content is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent. Create one first.");
        }

        String slug = title.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isBlank()) {
            slug = "page-" + System.currentTimeMillis();
        }

        WikiPageEntity existing = pageService.getBySlug(kbId, slug);
        if (existing != null) {
            slug = slug + "-" + System.currentTimeMillis() % 10000;
        }

        String summary = content.length() > 200 ? content.substring(0, 200) + "..." : content;
        WikiPageEntity page = pageService.createPage(kbId, slug, title, content, summary, null);
        log.info("[WikiTool] Created page: {} (slug={}, kbId={})", title, slug, kbId);

        return JSONUtil.createObj()
                .set("ok", true)
                .set("message", "Page created successfully")
                .set("title", page.getTitle())
                .set("slug", page.getSlug())
                .set("pageId", page.getId())
                .set("kbId", kbId)
                .toString();
    }

    // ==================== RFC-051 PR-4: on-demand compile + batch read ====================

    @Tool(description = """
            Compile (or update) a single wiki page about a topic from existing chunks.
            Use this AFTER lazy ingest when search has surfaced relevant content but no
            page exists yet. The page will cite only the evidence chunks the compile
            prompt actually used — not every chunk of the source raw material.
            Set slug to control the page slug; otherwise it's derived from the topic.
            """)
    public String wiki_compile_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Topic to compile a page about (natural language)") String topic,
            @ToolParam(description = "Optional explicit slug for the page", required = false) String slug,
            @ToolParam(description = "Max evidence chunks (default 8, max 20)", required = false) Integer maxEvidenceChunks) {

        if (topic == null || topic.isBlank()) {
            return error("topic is required");
        }
        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");
        if (compileService == null) return error("Compile service not available");

        try {
            WikiCompileService.CompileResult res = compileService.compilePage(kbId, topic, slug, maxEvidenceChunks);
            // RFC-051 follow-up: distinguish "no source material" from a hard error
            // so the agent can decide whether to retry, fall back to search, or tell
            // the user there's nothing on this topic.
            if (res.evidenceChunkCount() == 0) {
                return JSONUtil.createObj()
                        .set("ok", true)
                        .set("compiled", false)
                        .set("reason", "no_evidence")
                        .set("message", "No chunks matched the topic. Try wiki_search_pages, or upload source material first.")
                        .set("evidenceChunks", 0)
                        .toString();
            }
            return JSONUtil.createObj()
                    .set("ok", true)
                    .set("compiled", true)
                    .set("slug", res.slug())
                    .set("title", res.title())
                    .set("evidenceChunks", res.evidenceChunkCount())
                    .set("created", res.created())
                    .toString();
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("[WikiTool] wiki_compile_page failed: {}", e.getMessage());
            return error("Compile failed: " + e.getMessage());
        }
    }

    @Tool(description = """
            Read multiple wiki pages in one call. Prefer this over multiple wiki_read_page
            calls when you already know the slugs you need. The response is capped per
            page; protected/system pages can still be read explicitly here.
            """)
    public String wiki_read_many(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Comma-separated slugs (max 10)") String slugs,
            @ToolParam(description = "Max chars returned per page (default 2000, max 8000)", required = false) Integer maxCharsPerPage) {

        if (slugs == null || slugs.isBlank()) return error("slugs is required");
        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");

        int cap = (maxCharsPerPage == null || maxCharsPerPage <= 0) ? 2000 : Math.min(8000, maxCharsPerPage);
        List<String> slugList = Arrays.stream(slugs.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).limit(10).toList();
        if (slugList.isEmpty()) return error("No valid slugs supplied");

        JSONArray arr = new JSONArray();
        for (String s : slugList) {
            WikiPageEntity page = pageService.getBySlug(kbId, s);
            if (page == null) {
                arr.add(JSONUtil.createObj().set("slug", s).set("found", false));
                continue;
            }
            String content = page.getContent() == null ? "" : page.getContent();
            boolean truncated = content.length() > cap;
            if (truncated) content = content.substring(0, cap) + "\n…(truncated)";
            arr.add(JSONUtil.createObj()
                    .set("slug", s)
                    .set("found", true)
                    .set("title", page.getTitle())
                    .set("summary", page.getSummary())
                    .set("content", content)
                    .set("truncated", truncated));
            pageService.trackReference(kbId, s);
        }
        return JSONUtil.createObj()
                .set("kbId", kbId)
                .set("requestedCount", slugList.size())
                .set("pages", arr)
                .toString();
    }

    @Tool(description = """
            Archive a wiki page so it stops showing up in list / search / related
            results, without destroying it. Use this when a page is no longer
            relevant but its history (citations, raw lineage) should stay queryable.
            System pages (overview / log) cannot be archived.
            """)
    public String wiki_archive_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Page slug to archive") String slug) {
        return setArchivedTool(agentId, slug, true, "archived");
    }

    @Tool(description = """
            Unarchive a previously archived wiki page so it shows up in default
            list / search / related results again. No-op when the page wasn't archived.
            """)
    public String wiki_unarchive_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Page slug to unarchive") String slug) {
        return setArchivedTool(agentId, slug, false, "unarchived");
    }

    private String setArchivedTool(Long agentId, String slug, boolean archive, String verb) {
        if (slug == null || slug.isBlank()) return error("slug is required");
        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");
        boolean changed;
        try {
            changed = pageService.setArchived(kbId, slug, archive);
        } catch (Exception e) {
            return error(verb + " failed: " + e.getMessage());
        }
        return JSONUtil.createObj()
                .set("ok", true)
                .set("slug", slug)
                .set("changed", changed)
                .set("message", changed ? "Page " + verb : "Page already in that state (or not found)")
                .toString();
    }

    @Tool(description = """
            Delete an AI-generated wiki page. Cannot delete manually curated pages.
            """)
    public String wiki_delete_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Page slug to delete") String slug) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: " + slug);
        }

        if ("manual".equals(page.getLastUpdatedBy())) {
            return error("Cannot delete manually curated page: " + page.getTitle() + ". Please manage via admin UI.");
        }

        // RFC-051 PR-2: refuse to delete system pages (overview/log) or any
        // user-locked page, even when the agent has tool access.
        if (WikiPageService.isProtected(page)) {
            return error("Cannot delete protected page: " + page.getTitle()
                    + (page.getLocked() != null && page.getLocked() == 1 ? " (locked)" : " (system)"));
        }

        pageService.delete(kbId, slug);
        log.info("[WikiTool] Deleted page: {} (slug={}, kbId={})", page.getTitle(), slug, kbId);

        return JSONUtil.createObj()
                .set("ok", true)
                .set("message", "Page deleted")
                .set("slug", slug)
                .set("title", page.getTitle())
                .toString();
    }

    // ==================== RFC-029: Relation tools ====================

    @Tool(description = """
            Find pages structurally related to a given page (shared sources, links,
            semantic similarity). More reliable than keyword search for discovering connected knowledge.
            """)
    public String wiki_related_pages(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Page slug") String slug,
            @ToolParam(description = "Max results (default 5, max 10)", required = false) Integer topK) {

        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");
        if (relationService == null) return error("Relation service not available");

        int k = (topK != null && topK > 0) ? Math.min(topK, 10) : 5;
        List<RelatedPageResult> results = relationService.relatedPages(kbId, slug, k);

        JSONArray arr = new JSONArray();
        for (RelatedPageResult r : results) {
            arr.add(JSONUtil.createObj()
                    .set("slug", r.slug())
                    .set("title", r.title())
                    .set("score", String.format("%.2f", r.score()))
                    .set("signals", r.signals()));
        }

        return JSONUtil.createObj()
                .set("slug", slug)
                .set("relatedCount", results.size())
                .set("pages", arr)
                .toString();
    }

    @Tool(description = """
            Explain why two wiki pages are related. Returns signal breakdown with scores.
            """)
    public String wiki_explain_relation(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "First page slug") String slugA,
            @ToolParam(description = "Second page slug") String slugB) {

        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");
        if (relationService == null) return error("Relation service not available");

        RelationExplanation ex = relationService.explain(kbId, slugA, slugB);
        if (ex.breakdown().isEmpty()) return slugA + " and " + slugB + " have no detected relation.";

        StringBuilder sb = new StringBuilder("Relation score: ")
                .append(String.format("%.2f", ex.totalScore())).append("\n");
        ex.breakdown().forEach(s -> sb.append("  ").append(s.signal())
                .append(": ").append(String.format("%.2f", s.score())).append("\n"));
        return sb.toString();
    }

    // ==================== RFC-031: Enrichment tool ====================

    @Tool(description = """
            Trigger lightweight wikilink enrichment for a specific page.
            Does NOT regenerate content — only adds [[wikilink]] cross-references.
            """)
    public String wiki_enrich_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Page slug") String slug) {

        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");
        if (jobService == null || eventPublisher == null) return error("Job service not available");

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) return error("Page not found: " + slug);

        Long rawId = 0L;
        try {
            List<Long> rawIds = objectMapper.readValue(
                    page.getSourceRawIds() != null ? page.getSourceRawIds() : "[]",
                    new TypeReference<List<Long>>() {});
            if (!rawIds.isEmpty()) rawId = rawIds.get(0);
        } catch (Exception ignored) {}

        WikiProcessingJobEntity job = jobService.createLightEnrich(kbId, rawId);
        eventPublisher.publishEvent(new WikiJobCreatedEvent(job.getId()));
        return "Wikilink enrichment queued for: " + slug;
    }

    // ==================== Transformations ====================

    @Tool(description = """
            List the transformation templates available to this agent's wiki KB.
            Each result has a name (use it with wiki_apply_transformation), a
            human title, and a description of what the prompt produces.
            """)
    public String wiki_list_transformations(
            @ToolParam(description = "Agent ID") Long agentId) {
        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");
        if (transformationService == null) return error("Transformations not available");

        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        Long wsId = (kb == null || kb.getWorkspaceId() == null) ? 1L : kb.getWorkspaceId();

        List<WikiTransformationEntity> templates = transformationService.listForKb(kbId, wsId);
        JSONArray arr = new JSONArray();
        for (WikiTransformationEntity t : templates) {
            if (Boolean.FALSE.equals(t.getEnabled())) continue;
            arr.add(JSONUtil.createObj()
                    .set("name", t.getName())
                    .set("title", t.getTitle())
                    .set("description", t.getDescription())
                    .set("applyDefault", Boolean.TRUE.equals(t.getApplyDefault())));
        }
        return JSONUtil.createObj().set("kbId", kbId).set("transformations", arr).toString();
    }

    @Tool(description = """
            Run a transformation template against one raw material and return the
            generated text. Use wiki_list_transformations first to discover names.
            The run is also persisted so the result is visible in the wiki UI.
            """)
    public String wiki_apply_transformation(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Transformation name (from wiki_list_transformations)") String name,
            @ToolParam(description = "Raw material ID to run the transformation against") Long rawId) {
        if (name == null || name.isBlank()) return error("name is required");
        if (rawId == null) return error("rawId is required");
        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");
        if (transformationService == null || transformationExecutor == null) {
            return error("Transformations not available");
        }

        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        Long wsId = (kb == null || kb.getWorkspaceId() == null) ? 1L : kb.getWorkspaceId();

        WikiTransformationEntity template = transformationService.findByName(kbId, wsId, name).orElse(null);
        if (template == null) return error("Transformation not found: " + name);

        try {
            WikiTransformationRunEntity run = transformationExecutor.runOnRawSync(template, rawId, "agent_tool");
            if (run == null) return error("Transformation is disabled: " + name);
            if ("failed".equals(run.getStatus())) {
                return error("Transformation failed: " + run.getError());
            }
            return JSONUtil.createObj()
                    .set("ok", true)
                    .set("runId", run.getId())
                    .set("transformation", template.getName())
                    .set("output", run.getOutput())
                    .toString();
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("[WikiTool] wiki_apply_transformation failed: {}", e.getMessage());
            return error("Apply failed: " + e.getMessage());
        }
    }

    @Tool(description = """
            Run a transformation template against an existing wiki page and return
            the generated text. Use this when you want to derive a new artifact
            from an existing page — e.g. "summarize the contract-review page",
            "extract action items from this meeting-notes page". The run output
            is persisted in the wiki UI; pass slug (not page id) for convenience.
            """)
    public String wiki_apply_transformation_to_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Transformation name (from wiki_list_transformations)") String name,
            @ToolParam(description = "Source wiki page slug to run the transformation against") String slug) {
        if (name == null || name.isBlank()) return error("name is required");
        if (slug == null || slug.isBlank()) return error("slug is required");
        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");
        if (transformationService == null || transformationExecutor == null) {
            return error("Transformations not available");
        }

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) return error("Page not found: " + slug);

        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        Long wsId = (kb == null || kb.getWorkspaceId() == null) ? 1L : kb.getWorkspaceId();

        WikiTransformationEntity template = transformationService.findByName(kbId, wsId, name).orElse(null);
        if (template == null) return error("Transformation not found: " + name);

        try {
            WikiTransformationRunEntity run = transformationExecutor.runOnPageSync(template, page.getId(), "agent_tool");
            if (run == null) return error("Transformation is disabled: " + name);
            if ("failed".equals(run.getStatus())) {
                return error("Transformation failed: " + run.getError());
            }
            return JSONUtil.createObj()
                    .set("ok", true)
                    .set("runId", run.getId())
                    .set("transformation", template.getName())
                    .set("inputPage", slug)
                    .set("output", run.getOutput())
                    .toString();
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("[WikiTool] wiki_apply_transformation_to_page failed: {}", e.getMessage());
            return error("Apply failed: " + e.getMessage());
        }
    }

    @Tool(description = """
            Aggregate all completed runs of a transformation template across every
            raw material in this KB into a single synthesis wiki page. Use this
            after running a template against multiple sources to get a KB-level
            unified document (e.g. one consolidated 题型库 across 5 different
            mock exam PDFs, one customer-account brief across all sources for an
            account). Idempotent — re-running upserts the same slug.
            """)
    public String wiki_aggregate_transformation(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Transformation name (from wiki_list_transformations)") String name) {
        if (name == null || name.isBlank()) return error("name is required");
        Long kbId = resolveKbId(agentId);
        if (kbId == null) return error("No wiki knowledge base found for this agent");
        if (transformationService == null || transformationAggregator == null) {
            return error("Transformations not available");
        }

        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        Long wsId = (kb == null || kb.getWorkspaceId() == null) ? 1L : kb.getWorkspaceId();

        WikiTransformationEntity template = transformationService.findByName(kbId, wsId, name).orElse(null);
        if (template == null) return error("Transformation not found: " + name);

        try {
            var res = transformationAggregator.aggregate(template, kbId, "agent_tool");
            if (res.pageId() == null) {
                return JSONUtil.createObj()
                        .set("ok", true)
                        .set("aggregated", false)
                        .set("reason", res.title())
                        .toString();
            }
            return JSONUtil.createObj()
                    .set("ok", true)
                    .set("aggregated", true)
                    .set("pageSlug", res.slug())
                    .set("pageTitle", res.title())
                    .set("sourcesUsed", res.sourcesUsed())
                    .set("created", res.created())
                    .toString();
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("[WikiTool] wiki_aggregate_transformation failed: {}", e.getMessage());
            return error("Aggregate failed: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private Long resolveKbId(Long agentId) {
        WikiKnowledgeBaseEntity kb = kbService.resolvePrimaryKb(agentId);
        return kb == null ? null : kb.getId();
    }

    private JSONArray resolveSourceFiles(String sourceRawIdsJson) {
        JSONArray result = new JSONArray();
        if (sourceRawIdsJson == null || sourceRawIdsJson.isBlank()) return result;
        try {
            List<Long> rawIds = objectMapper.readValue(sourceRawIdsJson, new TypeReference<List<Long>>() {});
            for (Long rawId : rawIds) {
                WikiRawMaterialEntity raw = rawService.getById(rawId);
                if (raw != null) {
                    result.add(JSONUtil.createObj()
                            .set("rawId", raw.getId())
                            .set("title", raw.getTitle())
                            .set("sourceType", raw.getSourceType())
                            .set("sourcePath", raw.getSourcePath()));
                }
            }
        } catch (Exception e) {
            log.warn("[WikiTool] Failed to resolve source files: {}", e.getMessage());
        }
        return result;
    }

    /**
     * RFC-032: Extract a section from markdown content by heading text.
     */
    private String extractSection(String content, String heading) {
        if (content == null) return "";
        String escaped = Pattern.quote(heading.trim());
        Pattern p = Pattern.compile(
            "(?m)^(#{1,3})\\s+" + escaped + "\\b.*?(?=^#{1,3}\\s|\\Z)",
            Pattern.DOTALL | Pattern.MULTILINE
        );
        Matcher m = p.matcher(content);
        return m.find() ? m.group().strip() : content;
    }

    /**
     * RFC-032: Truncate content with a helpful message.
     */
    private String applyMaxChars(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars)
            + "\n\n[Content truncated at " + maxChars + " chars. "
            + "Use sectionHeading param to read a specific section.]";
    }

    private String error(String message) {
        return JSONUtil.createObj().set("error", message).toString();
    }
}
