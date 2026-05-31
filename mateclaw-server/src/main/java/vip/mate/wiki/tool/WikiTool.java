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
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.approval.ApprovalWorkflowService;
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
 * Every tool resolves its target KB through a small precedence ladder:
 * an explicit {@code kbId} from {@code wiki_list_kbs} wins outright, then
 * an explicit {@code kbName} (with fail-closed "ambiguous"/"not visible"
 * errors when needed), and only when both are absent does the tool fall
 * back to the agent's primary KB. The {@code kbId} surface is public so
 * the LLM can disambiguate duplicate-named KBs — see
 * {@link #wiki_list_kbs} and {@code WikiKnowledgeBaseService.findVisibleById}
 * for the visibility gate.
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

    /**
     * Optional approval workflow. When present, an {@code APPROVAL_REQUIRED}
     * write records a real pending approval in the operator inbox (keyed to the
     * current conversation via {@link ChatOriginHolder}), so the operation is
     * visible and auditable rather than silently blocked. Absent in lightweight
     * contexts (tests, headless tools) — the write still fails closed.
     */
    @Autowired(required = false)
    private ApprovalWorkflowService approvalWorkflowService;

    /**
     * Per-agent pageType permission gate. Mandatory: this is a security control,
     * so it is a required constructor dependency rather than an optional bean —
     * a missing gate must fail loudly at startup, never silently fail open.
     */
    private final WikiPageTypePermissionService pageTypePermissionService;

    public WikiTool(WikiPageService pageService,
                     WikiKnowledgeBaseService kbService,
                     WikiRawMaterialService rawService,
                     HybridRetriever hybridRetriever,
                     ObjectMapper objectMapper,
                     WikiPageTypePermissionService pageTypePermissionService) {
        this.pageService = pageService;
        this.kbService = kbService;
        this.rawService = rawService;
        this.hybridRetriever = hybridRetriever;
        this.objectMapper = objectMapper;
        this.pageTypePermissionService = pageTypePermissionService;
    }

    // ==================== Knowledge-base discovery ====================

    @Tool(description = """
            List every knowledge base visible to this agent — both KBs explicitly
            bound to the agent and shared workspace-level KBs.

            Every other wiki tool (read / list / search / semantic search / …)
            accepts two OPTIONAL routing arguments. Use them in this order:
              1. `kbName` — readable name from the output below. Easiest.
              2. `kbId`   — numeric id from the output below. Use this when
                            two KBs share the same name and `kbName` returns
                            an "Ambiguous kbName" error.
            Omit both and the tool falls back to the agent's primary KB,
            which is fine when the agent only reaches one KB.

            Output fields per KB:
              - kbId         — string-encoded numeric id (use as `kbId` param)
              - name         — copy verbatim into `kbName` param
              - description  — operator-supplied summary
              - pageCount    — number of pages currently in the KB
              - isPrimary    — true for the KB used when both routing
                               arguments are omitted
              - boundToAgent — true if the KB is explicitly bound to this agent
            """)
    public String wiki_list_kbs(
            @ToolParam(description = "Agent ID") Long agentId) {
        List<WikiKnowledgeBaseEntity> kbs = kbService.listByAgentId(agentId);
        WikiKnowledgeBaseEntity primary = kbService.resolvePrimaryKb(agentId);
        Long primaryId = primary == null ? null : primary.getId();

        JSONArray arr = new JSONArray();
        for (WikiKnowledgeBaseEntity kb : kbs) {
            // kbId is rendered as a STRING per the workspace-wide Snowflake-
            // precision rule: a 19-digit id round-tripped through a JSON
            // number loses its last 2-3 digits whenever it touches a JS
            // runtime. The LLM hands the value back to us through a Java
            // Long @ToolParam, which is precision-safe, so the lossy hop
            // is purely defensive.
            arr.add(JSONUtil.createObj()
                    .set("kbId", String.valueOf(kb.getId()))
                    .set("name", kb.getName())
                    .set("description", kb.getDescription())
                    .set("pageCount", kb.getPageCount() == null ? 0 : kb.getPageCount())
                    .set("isPrimary", kb.getId().equals(primaryId))
                    .set("boundToAgent", kb.getAgentId() != null));
        }
        return JSONUtil.createObj()
                .set("kbCount", kbs.size())
                .set("primary", primary == null ? null : primary.getName())
                .set("kbs", arr)
                .toString();
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
            @ToolParam(description = "Section heading to extract (null = all sections)", required = false) String sectionHeading,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: " + slug);
        }
        if (!canRead(pageTypeAccess(agentId, kbId), page)) {
            // Page type not readable by this agent — do not leak its existence.
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
            @ToolParam(description = "Title keyword filter (optional)", required = false) String query,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

        WikiPageTypePermissionService.Access access = pageTypeAccess(agentId, kbId);
        List<WikiPageLite> pages;
        if (query != null && !query.isBlank()) {
            List<Long> ids = pageService.searchPages(kbId, query).stream()
                    .filter(p -> !"system".equals(p.getPageType()))
                    .filter(p -> canRead(access, p))
                    .map(WikiPageEntity::getId).limit(30).toList();
            if (ids.isEmpty()) {
                pages = List.of();
            } else {
                pages = pageService.listSummaries(kbId).stream()
                        .filter(p -> !"system".equals(p.getPageType()))
                        .filter(p -> canRead(access, p))
                        .filter(p -> ids.stream().anyMatch(id -> Objects.equals(id, p.getId())))
                        .map(p -> new WikiPageLite(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getPageType()))
                        .toList();
            }
        } else {
            // RFC-051 PR-2: hide system pages (overview / log) from default listings.
            // Agents can still wiki_read_page("overview") explicitly.
            pages = pageService.listSummaries(kbId).stream()
                    .filter(p -> !"system".equals(p.getPageType()))
                    .filter(p -> canRead(access, p))
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
            @ToolParam(description = "Max results (default 5, max 20)", required = false) Integer topK,
            @ToolParam(description = "Knowledge layer filter: fact | experience | all (default all). 'fact' = factual pages (and unlayered pages); 'experience' = synthesis/analysis pages.", required = false) String layer,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        if (query == null || query.isBlank()) {
            return error("query is required");
        }

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

        int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;
        List<PageSearchResult> results = hybridRetriever.search(kbId, query, mode, k);

        // Drop hits this agent may not read (by page type) or that fall outside
        // the requested knowledge layer. Both need the page, so fetch it once.
        WikiPageTypePermissionService.Access access = pageTypeAccess(agentId, kbId);
        boolean layerFilter = layer != null && !layer.isBlank() && !"all".equalsIgnoreCase(layer.trim());
        if (access != null || layerFilter) {
            final Long resolvedKbId = kbId;
            final String layerWanted = layer;
            results = results.stream()
                    .filter(r -> {
                        WikiPageEntity p = pageService.getBySlug(resolvedKbId, r.slug());
                        return canRead(access, p) && matchesLayer(p == null ? null : p.getKnowledgeLayer(), layerWanted);
                    })
                    .toList();
        }

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
            @ToolParam(description = "Max results (default 5)", required = false) Integer topK,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        if (query == null || query.isBlank()) {
            return error("query is required");
        }

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

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
            @ToolParam(description = "Page slug") String slug,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: " + slug);
        }
        if (!canRead(pageTypeAccess(agentId, kbId), page)) {
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
            @ToolParam(description = "Page content (Markdown)") String content,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        if (title == null || title.isBlank()) {
            return error("title is required");
        }
        if (content == null || content.isBlank()) {
            return error("content is required");
        }

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

        // wiki_create_page does not take an explicit pageType, so creation is
        // governed by the agent's wildcard ('*') write rule for this KB.
        String createErr = checkWrite(agentId, kbId, null,
                WikiPageTypePermissionService.WriteOp.CREATE);
        if (createErr != null) {
            return createErr;
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
            @ToolParam(description = "Max evidence chunks (default 8, max 20)", required = false) Integer maxEvidenceChunks,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        if (topic == null || topic.isBlank()) {
            return error("topic is required");
        }
        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();
        if (compileService == null) return error("Compile service not available");

        String compileErr = checkWrite(agentId, kbId, null,
                WikiPageTypePermissionService.WriteOp.CREATE);
        if (compileErr != null) {
            return compileErr;
        }

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
            @ToolParam(description = "Max chars returned per page (default 2000, max 8000)", required = false) Integer maxCharsPerPage,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        if (slugs == null || slugs.isBlank()) return error("slugs is required");
        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

        int cap = (maxCharsPerPage == null || maxCharsPerPage <= 0) ? 2000 : Math.min(8000, maxCharsPerPage);
        List<String> slugList = Arrays.stream(slugs.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).limit(10).toList();
        if (slugList.isEmpty()) return error("No valid slugs supplied");

        WikiPageTypePermissionService.Access access = pageTypeAccess(agentId, kbId);
        JSONArray arr = new JSONArray();
        for (String s : slugList) {
            WikiPageEntity page = pageService.getBySlug(kbId, s);
            if (page == null || !canRead(access, page)) {
                // Unreadable pageType is reported as not-found, same as a missing
                // slug, so the agent cannot probe for hidden pages by slug.
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
            @ToolParam(description = "Page slug to archive") String slug,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {
        return setArchivedTool(agentId, slug, true, "archived", kbName, kbId);
    }

    @Tool(description = """
            Unarchive a previously archived wiki page so it shows up in default
            list / search / related results again. No-op when the page wasn't archived.
            """)
    public String wiki_unarchive_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Page slug to unarchive") String slug,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {
        return setArchivedTool(agentId, slug, false, "unarchived", kbName, kbId);
    }

    private String setArchivedTool(Long agentId, String slug, boolean archive, String verb, String kbName, Long kbId) {
        if (slug == null || slug.isBlank()) return error("slug is required");
        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();
        // Archiving toggles visibility — gate it as an update, and hide pages
        // whose type the agent cannot read.
        WikiPageEntity target = pageService.getBySlug(kbId, slug);
        if (target != null) {
            if (!canRead(pageTypeAccess(agentId, kbId), target)) {
                return error("Page not found: " + slug);
            }
            String writeErr = checkWrite(agentId, kbId, target.getPageType(),
                    WikiPageTypePermissionService.WriteOp.UPDATE);
            if (writeErr != null) {
                return writeErr;
            }
        }
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
            @ToolParam(description = "Page slug to delete") String slug,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: " + slug);
        }
        // Unreadable page types must not even be discoverable as delete targets.
        if (!canRead(pageTypeAccess(agentId, kbId), page)) {
            return error("Page not found: " + slug);
        }
        String writeErr = checkWrite(agentId, kbId, page.getPageType(),
                WikiPageTypePermissionService.WriteOp.DELETE);
        if (writeErr != null) {
            return writeErr;
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
            @ToolParam(description = "Max results (default 5, max 10)", required = false) Integer topK,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();
        if (relationService == null) return error("Relation service not available");

        int k = (topK != null && topK > 0) ? Math.min(topK, 10) : 5;
        List<RelatedPageResult> results = relationService.relatedPages(kbId, slug, k);

        WikiPageTypePermissionService.Access access = pageTypeAccess(agentId, kbId);
        JSONArray arr = new JSONArray();
        for (RelatedPageResult r : results) {
            if (access != null && !canRead(access, pageService.getBySlug(kbId, r.slug()))) {
                continue;
            }
            arr.add(JSONUtil.createObj()
                    .set("slug", r.slug())
                    .set("title", r.title())
                    .set("score", String.format("%.2f", r.score()))
                    .set("signals", r.signals()));
        }

        return JSONUtil.createObj()
                .set("slug", slug)
                .set("relatedCount", arr.size())
                .set("pages", arr)
                .toString();
    }

    @Tool(description = """
            Explain why two wiki pages are related. Returns signal breakdown with scores.
            """)
    public String wiki_explain_relation(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "First page slug") String slugA,
            @ToolParam(description = "Second page slug") String slugB,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();
        if (relationService == null) return error("Relation service not available");

        WikiPageTypePermissionService.Access relAccess = pageTypeAccess(agentId, kbId);
        if (relAccess != null
                && (!canRead(relAccess, pageService.getBySlug(kbId, slugA))
                    || !canRead(relAccess, pageService.getBySlug(kbId, slugB)))) {
            return slugA + " and " + slugB + " have no detected relation.";
        }

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
            @ToolParam(description = "Page slug") String slug,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();
        if (jobService == null || eventPublisher == null) return error("Job service not available");

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) return error("Page not found: " + slug);
        if (!canRead(pageTypeAccess(agentId, kbId), page)) {
            return error("Page not found: " + slug);
        }
        String enrichErr = checkWrite(agentId, kbId, page.getPageType(),
                WikiPageTypePermissionService.WriteOp.UPDATE);
        if (enrichErr != null) {
            return enrichErr;
        }

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

    // ==================== Page update / stale review ====================

    @Tool(description = """
            Update an existing wiki page's Markdown body IN PLACE, by slug. This
            preserves the page's identity, slug, backlinks and version history.
            Use this to revise or extend a page — do NOT delete and recreate it
            (that drops links and can leave duplicate pages behind). The summary
            is re-derived from the new content unless you pass one explicitly.
            """)
    public String wiki_update_page(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Slug of the page to update (from wiki_list_pages / wiki_read_page)") String slug,
            @ToolParam(description = "New full Markdown content for the page body") String content,
            @ToolParam(description = "New one-line summary (optional; omit to auto-derive from content)", required = false) String summary,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }
        if (content == null || content.isBlank()) {
            return error("content is required");
        }
        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: '" + slug + "'. Use wiki_list_pages to find the right slug.");
        }
        String writeErr = checkWrite(agentId, kbId, page.getPageType(),
                WikiPageTypePermissionService.WriteOp.UPDATE);
        if (writeErr != null) return writeErr;

        // summary == null → service re-derives it from the new content.
        WikiPageEntity updated = pageService.updatePageManually(
                kbId, slug, content, (summary == null || summary.isBlank()) ? null : summary);
        return JSONUtil.createObj()
                .set("ok", true)
                .set("slug", updated.getSlug())
                .set("title", updated.getTitle())
                .set("version", updated.getVersion())
                .set("message", "Page updated in place (slug and backlinks preserved).")
                .toString();
    }

    @Tool(description = """
            List wiki pages currently marked STALE (needing review) in a knowledge
            base. A page goes stale when a fact page it depends on was updated, so
            its synthesis/analysis content may now be out of date. Returns each
            stale page's title, slug, pageType, knowledge layer and the reason it
            was flagged. Use this to find what to re-check or re-summarize before
            relying on experience/analysis pages.
            """)
    public String wiki_stale_pages(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {

        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();

        WikiPageTypePermissionService.Access access = pageTypeAccess(agentId, kbId);
        JSONArray arr = new JSONArray();
        for (WikiPageEntity p : pageService.listByKbId(kbId)) {
            if (p.getStale() == null || p.getStale() == 0) continue;
            if (p.getArchived() != null && p.getArchived() == 1) continue;
            if (!canRead(access, p)) continue; // honour pageType read permissions
            arr.add(JSONUtil.createObj()
                    .set("slug", p.getSlug())
                    .set("title", p.getTitle())
                    .set("pageType", p.getPageType())
                    .set("knowledgeLayer", p.getKnowledgeLayer())
                    .set("staleReason", p.getStaleReasonJson() == null ? "" : p.getStaleReasonJson()));
        }
        return JSONUtil.createObj()
                .set("kbId", String.valueOf(kbId))
                .set("staleCount", arr.size())
                .set("pages", arr)
                .toString();
    }

    // ==================== Transformations ====================

    @Tool(description = """
            List the transformation templates available to this agent's wiki KB.
            Each result has a name (use it with wiki_apply_transformation), a
            human title, and a description of what the prompt produces.
            """)
    public String wiki_list_transformations(
            @ToolParam(description = "Agent ID") Long agentId,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {
        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();
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
            @ToolParam(description = "Raw material ID to run the transformation against") Long rawId,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {
        if (name == null || name.isBlank()) return error("name is required");
        if (rawId == null) return error("rawId is required");
        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();
        if (transformationService == null || transformationExecutor == null) {
            return error("Transformations not available");
        }

        // A transformation persists a synthesis run/page — gate as a create.
        String txErr = checkWrite(agentId, kbId, null,
                WikiPageTypePermissionService.WriteOp.CREATE);
        if (txErr != null) {
            return txErr;
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
            @ToolParam(description = "Source wiki page slug to run the transformation against") String slug,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {
        if (name == null || name.isBlank()) return error("name is required");
        if (slug == null || slug.isBlank()) return error("slug is required");
        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();
        if (transformationService == null || transformationExecutor == null) {
            return error("Transformations not available");
        }

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) return error("Page not found: " + slug);
        if (!canRead(pageTypeAccess(agentId, kbId), page)) {
            return error("Page not found: " + slug);
        }
        // Reads the source page and persists a derived run — gate as a create.
        String txErr = checkWrite(agentId, kbId, null,
                WikiPageTypePermissionService.WriteOp.CREATE);
        if (txErr != null) {
            return txErr;
        }

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
            @ToolParam(description = "Transformation name (from wiki_list_transformations)") String name,
            @ToolParam(description = "Target knowledge base name (from wiki_list_kbs). Omit to use the agent's primary KB; switch to `kbId` when two KBs share the name.", required = false) String kbName,
            @ToolParam(description = "Numeric KB id from wiki_list_kbs. Use when `kbName` returns an ambiguous-name error.", required = false) Long kbId) {
        if (name == null || name.isBlank()) return error("name is required");
        KbResolution kbRes = resolveKb(agentId, kbName, kbId);
        if (kbRes.hasError()) return kbRes.errorJson();
        kbId = kbRes.kbId();
        if (transformationService == null || transformationAggregator == null) {
            return error("Transformations not available");
        }

        // Aggregation upserts a synthesis page — gate as a create.
        String aggErr = checkWrite(agentId, kbId, null,
                WikiPageTypePermissionService.WriteOp.CREATE);
        if (aggErr != null) {
            return aggErr;
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
        return resolveKbId(agentId, null, null);
    }

    /**
     * Outcome of resolving a KB for a tool call. Exactly one of
     * {@code kbId} / {@code errorJson} is non-null:
     * <ul>
     *   <li>{@code kbId} present → caller proceeds with that KB.</li>
     *   <li>{@code errorJson} present → caller returns it as-is so the LLM
     *       sees an unambiguous error pointing at the next action
     *       (call {@code wiki_list_kbs} / pick a different name / pass
     *       {@code kbId}).</li>
     * </ul>
     */
    private record KbResolution(Long kbId, String errorJson) {
        static KbResolution ok(Long id) { return new KbResolution(id, null); }
        static KbResolution err(String json) { return new KbResolution(null, json); }
        boolean hasError() { return errorJson != null; }
    }

    /**
     * Resolve the agent's pageType read/write permission view for a KB once,
     * so a tool call can filter a whole result set without re-querying. Never
     * null — the service is a mandatory dependency, so there is no fail-open
     * path; an agent with no rows still resolves to the KB's default policy.
     */
    private WikiPageTypePermissionService.Access pageTypeAccess(Long agentId, Long kbId) {
        return pageTypePermissionService.resolve(agentId, kbId);
    }

    /** Whether the resolved access permits reading {@code page}. Null-safe. */
    private boolean canRead(WikiPageTypePermissionService.Access access, WikiPageEntity page) {
        return access == null || page == null || access.canRead(page.getPageType());
    }

    /** Whether the resolved access permits reading a page of {@code pageType}. Null-safe. */
    private boolean canRead(WikiPageTypePermissionService.Access access, String pageType) {
        return access == null || access.canRead(pageType);
    }

    /**
     * Whether a page's knowledge layer matches a retrieval filter. A null/blank
     * or {@code all} filter matches everything; an unlayered page counts as
     * {@code fact} (the RFC default), so {@code layer=fact} includes legacy
     * pages while {@code layer=experience} returns only experience pages.
     */
    static boolean matchesLayer(String pageLayer, String filter) {
        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter.trim())) {
            return true;
        }
        String effective = (pageLayer == null || pageLayer.isBlank()) ? "fact" : pageLayer.trim().toLowerCase();
        return effective.equals(filter.trim().toLowerCase());
    }

    /**
     * Gate a write/mutate operation by pageType permission. Returns an error
     * JSON string to short-circuit the tool when the write is not permitted, or
     * {@code null} when it may proceed. Null-safe: when the permission service
     * is absent, every write is allowed (pre-permission behaviour).
     *
     * <p>{@code APPROVAL_REQUIRED} records a pending approval in the operator
     * inbox (when {@link #approvalWorkflowService} is wired) so the request is
     * visible and auditable, then fails closed for this turn — the write is not
     * performed inline. The approve-then-replay path is driven from the inbox,
     * not from inside the tool body, which cannot pause and resume itself.
     */
    private String checkWrite(Long agentId, Long kbId, String pageType,
                              WikiPageTypePermissionService.WriteOp op) {
        WikiPageTypePermissionService.WriteDecision decision =
                pageTypePermissionService.resolveWrite(agentId, kbId, pageType, op);
        String typeLabel = (pageType == null || pageType.isBlank()) ? "(default)" : pageType;
        return switch (decision) {
            case ALLOW -> null;
            case DENY -> {
                log.info("[WikiTool] write denied by pageType permission: agent={} kb={} type={} op={}",
                        agentId, kbId, pageType, op);
                yield error("Not permitted: this agent may not " + op.name().toLowerCase()
                        + " '" + typeLabel + "' pages in this knowledge base.");
            }
            case APPROVAL_REQUIRED -> {
                boolean recorded = recordPendingApproval(agentId, kbId, pageType, op);
                log.info("[WikiTool] write requires approval: agent={} kb={} type={} op={} recorded={}",
                        agentId, kbId, pageType, op, recorded);
                yield error("Approval required: " + op.name().toLowerCase() + " of '" + typeLabel
                        + "' pages in this knowledge base needs administrator approval. "
                        + (recorded
                                ? "A pending approval was created for an administrator to review. "
                                : "")
                        + "The operation was NOT performed.");
            }
        };
    }

    /**
     * Record a pending approval for an {@code APPROVAL_REQUIRED} wiki write,
     * keyed to the current conversation via {@link ChatOriginHolder}. Best-effort:
     * returns false (and never throws) when the workflow bean is absent or there
     * is no conversation context, so the caller can still fail closed cleanly.
     */
    private boolean recordPendingApproval(Long agentId, Long kbId, String pageType,
                                          WikiPageTypePermissionService.WriteOp op) {
        if (approvalWorkflowService == null) {
            return false;
        }
        ChatOrigin origin = ChatOriginHolder.get();
        String conversationId = origin == null ? null : origin.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return false; // no conversation to attach the approval to
        }
        try {
            String typeLabel = (pageType == null || pageType.isBlank()) ? "(default)" : pageType;
            String args = objectMapper.createObjectNode()
                    .put("kbId", String.valueOf(kbId))
                    .put("pageType", typeLabel)
                    .put("op", op.name())
                    .toString();
            String reason = "Wiki " + op.name().toLowerCase() + " of '" + typeLabel
                    + "' pages requires administrator approval.";
            String userId = origin.requesterId();
            approvalWorkflowService.createPending(
                    conversationId,
                    (userId == null || userId.isBlank()) ? null : userId,
                    "wiki_" + op.name().toLowerCase() + "_page",
                    args,
                    reason,
                    /* toolCallPayload */ null,
                    /* siblingToolCalls */ null,
                    agentId == null ? null : String.valueOf(agentId));
            return true;
        } catch (Exception e) {
            log.warn("[WikiTool] failed to record pending approval: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Single helper every wiki tool uses. Caller passes the agent id and at
     * most one of {@code kbId} / {@code kbName}; the helper decides which
     * KB the operation runs against and emits a uniform error when no
     * unambiguous target can be picked.
     *
     * <p>Resolution rules (in order):
     * <ol>
     *   <li>{@code kbId} non-null → resolve only via
     *       {@link WikiKnowledgeBaseService#findVisibleById}. Out-of-visibility
     *       ids fail closed — no silent fallback to the primary or to a
     *       same-name shared KB.</li>
     *   <li>{@code kbName} non-blank → look up every visible KB with that
     *       name. Single match → use it. Zero match → fail closed pointing
     *       at {@code wiki_list_kbs}. Multiple matches → fail closed with
     *       the list of candidate {@code kbId}s, telling the LLM to retry
     *       with {@code kbId}.</li>
     *   <li>Both blank → fall back to
     *       {@link WikiKnowledgeBaseService#resolvePrimaryKb} so single-KB
     *       agents keep their old zero-config behaviour.</li>
     * </ol>
     */
    private KbResolution resolveKb(Long agentId, String kbName, Long kbId) {
        // Treat kbId<=0 as "not supplied". Spring AI's @Tool JSON-schema
        // generator doesn't carry the "optional, may be absent" semantic
        // through to the LLM in the way Java would expect a nullable Long,
        // so the model frequently fills unused numeric optionals with 0
        // ("openai-chatgpt" was observed doing this on every wiki call).
        // Real Snowflake ids are always 19-digit positive longs, so
        // {0, negative} can be safely treated as the empty case.
        if (kbId != null && kbId > 0L) {
            WikiKnowledgeBaseEntity byId = kbService.findVisibleById(agentId, kbId);
            if (byId == null) {
                return KbResolution.err(error(
                        "Knowledge base id=" + kbId + " not visible to this agent. "
                        + "Use wiki_list_kbs to see available KBs."));
            }
            return KbResolution.ok(byId.getId());
        }
        if (kbName != null && !kbName.isBlank()) {
            List<WikiKnowledgeBaseEntity> matches = kbService.findAllByName(agentId, kbName);
            if (matches.isEmpty()) {
                return KbResolution.err(error(
                        "Knowledge base '" + kbName + "' not visible to this agent. "
                        + "Use wiki_list_kbs to see available KBs."));
            }
            if (matches.size() > 1) {
                // Duplicate names exist (no DB unique constraint). The LLM
                // cannot disambiguate from kbName alone — surface every
                // candidate's id (as String per the Snowflake-precision
                // contract) and demand a kbId retry.
                JSONArray candidates = new JSONArray();
                for (WikiKnowledgeBaseEntity kb : matches) {
                    candidates.add(JSONUtil.createObj()
                            .set("kbId", String.valueOf(kb.getId()))
                            .set("name", kb.getName())
                            .set("description", kb.getDescription())
                            .set("boundToAgent", kb.getAgentId() != null));
                }
                JSONObject obj = JSONUtil.createObj()
                        .set("error", "Ambiguous kbName '" + kbName + "' — "
                                + matches.size() + " visible KBs share this name. "
                                + "Retry with `kbId` from the candidates list below.")
                        .set("candidates", candidates);
                return KbResolution.err(obj.toString());
            }
            return KbResolution.ok(matches.get(0).getId());
        }
        WikiKnowledgeBaseEntity primary = kbService.resolvePrimaryKb(agentId);
        if (primary == null) {
            return KbResolution.err(error("No wiki knowledge base found for this agent"));
        }
        return KbResolution.ok(primary.getId());
    }

    /**
     * Legacy 2-arg routing kept for the tool methods that haven't been
     * widened to accept {@code kbId} yet. Always returns null when the
     * resolution would have surfaced an error — callers turn that into a
     * {@link #noKbError(String)} message.
     */
    private Long resolveKbId(Long agentId, String kbName) {
        return resolveKbId(agentId, kbName, null);
    }

    private Long resolveKbId(Long agentId, String kbName, Long kbId) {
        KbResolution res = resolveKb(agentId, kbName, kbId);
        return res.hasError() ? null : res.kbId();
    }

    /**
     * Standardised "couldn't resolve KB" error. When the caller passed a
     * non-blank {@code kbName} that didn't match, the message points them at
     * {@code wiki_list_kbs} so the LLM has a clear next step.
     *
     * <p>NOTE: ambiguous-kbName errors are emitted directly by
     * {@link #resolveKb} so the LLM also gets the candidate list, not just
     * a flat string. This helper handles the simpler "not visible" case.
     */
    private String noKbError(String kbName) {
        if (kbName != null && !kbName.isBlank()) {
            return error("Knowledge base '" + kbName
                    + "' not visible to this agent. Use wiki_list_kbs to see available KBs.");
        }
        return error("No wiki knowledge base found for this agent");
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
