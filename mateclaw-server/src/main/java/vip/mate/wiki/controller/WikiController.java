package vip.mate.wiki.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.web.Utf8SseEmitter;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.service.WikiDirectoryScanService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiLintJobService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiProcessingService;
import vip.mate.wiki.service.WikiRawMaterialService;
import vip.mate.wiki.sse.WikiProgressBus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiki 知识库接口
 *
 * @author MateClaw Team
 */
@Slf4j
@Tag(name = "Wiki 知识库")
@RestController
@RequestMapping("/api/v1/wiki")
@RequiredArgsConstructor
public class WikiController {

    private final WikiKnowledgeBaseService kbService;
    private final WikiRawMaterialService rawService;
    private final WikiPageService pageService;
    private final WikiProcessingService processingService;
    private final WikiDirectoryScanService scanService;
    private final WikiLintJobService lintJobService;
    private final WikiProperties properties;
    private final WikiProgressBus progressBus;
    private final AuditEventService auditEventService;

    // ==================== Knowledge Base ====================

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取所有知识库")
    @GetMapping("/knowledge-bases")
    public R<List<WikiKnowledgeBaseEntity>> listKBs(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(withLivePageCount(kbService.listByWorkspace(wsId)));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "列出可绑定到指定 Agent 的知识库")
    @GetMapping("/knowledge-bases/bindable")
    public R<List<WikiKnowledgeBaseEntity>> listBindableKBs(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(withLivePageCount(kbService.listByWorkspace(wsId)));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取知识库详情")
    @GetMapping("/knowledge-bases/{id}")
    public R<WikiKnowledgeBaseEntity> getKB(@PathVariable Long id,
                                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        WikiKnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return R.fail(404, "Knowledge base not found");
        return R.ok(withLivePageCount(kb));
    }

    /**
     * Overlay the live page count onto knowledge bases before returning them.
     * The {@code pageCount} column is denormalized and only refreshed by the
     * processing pipeline, so system-page generation (overview/log) and other
     * out-of-band mutations leave it stale. Recomputing on read keeps the count
     * the UI shows consistent with the page list.
     */
    private List<WikiKnowledgeBaseEntity> withLivePageCount(List<WikiKnowledgeBaseEntity> kbs) {
        kbs.forEach(this::withLivePageCount);
        return kbs;
    }

    private WikiKnowledgeBaseEntity withLivePageCount(WikiKnowledgeBaseEntity kb) {
        if (kb != null && kb.getId() != null) {
            kb.setPageCount(pageService.countByKbId(kb.getId()));
        }
        return kb;
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "按 Agent 获取知识库")
    @GetMapping("/knowledge-bases/agent/{agentId}")
    public R<List<WikiKnowledgeBaseEntity>> listKBsByAgent(@PathVariable Long agentId,
                                                            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        // 按 agent 查询后，过滤出属于当前 workspace 的知识库
        List<WikiKnowledgeBaseEntity> kbs = kbService.listByAgentId(agentId);
        return R.ok(withLivePageCount(kbs.stream()
                .filter(kb -> kb.getWorkspaceId() == null || kb.getWorkspaceId().equals(wsId))
                .collect(java.util.stream.Collectors.toList())));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "创建知识库")
    @PostMapping("/knowledge-bases")
    public R<WikiKnowledgeBaseEntity> createKB(@RequestBody Map<String, Object> body,
                                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        Long agentId = body.get("agentId") != null ? Long.valueOf(body.get("agentId").toString()) : null;
        long wsId = workspaceId != null ? workspaceId : 1L;
        WikiKnowledgeBaseEntity kb = kbService.create(name, description, agentId, wsId);
        return R.ok(kb);
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "更新知识库")
    @PutMapping("/knowledge-bases/{id}")
    public R<WikiKnowledgeBaseEntity> updateKB(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        kbService.update(id, name, description);
        // RFC Embedding UI: 允许通过此接口绑定 / 解绑 embedding 模型
        if (body.containsKey("embeddingModelId")) {
            Object v = body.get("embeddingModelId");
            Long embeddingModelId = null;
            if (v != null && !v.toString().isBlank()) {
                embeddingModelId = Long.valueOf(v.toString());
            }
            kbService.updateEmbeddingModelId(id, embeddingModelId);
        }
        return R.ok(kbService.getById(id));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "删除知识库")
    @DeleteMapping("/knowledge-bases/{id}")
    public R<Void> deleteKB(@PathVariable Long id,
                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        WikiKnowledgeBaseService.CascadeDeleteResult result = kbService.delete(id);
        String detail = String.format(
                "{\"rawMaterialCount\":%d,\"pageCount\":%d,\"chunkCount\":%d,\"citationCount\":%d,\"processingJobCount\":%d}",
                result.rawMaterialCount(), result.pageCount(), result.chunkCount(),
                result.citationCount(), result.processingJobCount());
        auditEventService.record("DELETE", "WIKI_KB", String.valueOf(id), result.kbName(), detail);
        return R.ok();
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取知识库配置")
    @GetMapping("/knowledge-bases/{id}/config")
    public R<Map<String, String>> getConfig(@PathVariable Long id,
                                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        WikiKnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return R.fail(404, "Knowledge base not found");
        return R.ok(Map.of("content", kb.getConfigContent() != null ? kb.getConfigContent() : ""));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "更新知识库配置")
    @PutMapping("/knowledge-bases/{id}/config")
    public R<Void> updateConfig(@PathVariable Long id, @RequestBody Map<String, String> body,
                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        kbService.updateConfig(id, body.get("content"));
        return R.ok();
    }

    // ==================== Directory Scan ====================

    @RequireWorkspaceRole("member")
    @Operation(summary = "设置知识库关联目录")
    @PutMapping("/knowledge-bases/{id}/source-directory")
    public R<Void> setSourceDirectory(@PathVariable Long id, @RequestBody Map<String, String> body,
                                       @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        String path = body.get("path");
        kbService.updateSourceDirectory(id, path);
        return R.ok();
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "扫描关联目录导入文件")
    @PostMapping("/knowledge-bases/{id}/scan")
    public R<Map<String, Object>> scanDirectory(@PathVariable Long id,
                                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        WikiDirectoryScanService.ScanResult result = scanService.scan(id);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scanned", result.scanned());
        response.put("added", result.added());
        response.put("skipped", result.skipped());
        response.put("errors", result.errors());
        return R.ok(response);
    }

    // ==================== Raw Materials ====================

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取原始材料列表（含每条材料生成的页面数）")
    @GetMapping("/knowledge-bases/{kbId}/raw")
    public R<List<Map<String, Object>>> listRaw(@PathVariable Long kbId,
                                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        List<WikiRawMaterialEntity> raws = rawService.listByKbId(kbId);
        List<Map<String, Object>> result = new java.util.ArrayList<>(raws.size());
        for (WikiRawMaterialEntity raw : raws) {
            Map<String, Object> item = new LinkedHashMap<>();
            // Serialize all entity fields via Jackson-friendly approach
            item.put("id", raw.getId());
            item.put("kbId", raw.getKbId());
            item.put("title", raw.getTitle());
            item.put("sourceType", raw.getSourceType());
            item.put("processingStatus", raw.getProcessingStatus());
            item.put("errorMessage", raw.getErrorMessage());
            item.put("progressPhase", raw.getProgressPhase());
            item.put("progressDone", raw.getProgressDone());
            item.put("progressTotal", raw.getProgressTotal());
            item.put("contentHash", raw.getContentHash());
            item.put("createTime", raw.getCreateTime());
            item.put("updateTime", raw.getUpdateTime());
            // Enriched field: page count derived from this raw material
            item.put("pageCount", pageService.countBySourceRawId(kbId, raw.getId()));
            result.add(item);
        }
        return R.ok(result);
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "添加文本材料")
    @PostMapping("/knowledge-bases/{kbId}/raw/text")
    public R<WikiRawMaterialEntity> addRawText(@PathVariable Long kbId, @RequestBody Map<String, String> body,
                                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        String title = body.get("title");
        String content = body.get("content");
        return R.ok(rawService.addText(kbId, title, content));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "上传文件材料")
    @PostMapping("/knowledge-bases/{kbId}/raw/upload")
    public R<WikiRawMaterialEntity> uploadRaw(@PathVariable Long kbId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) throws IOException {
        verifyKBWorkspace(kbId, workspaceId);
        String originalName = file.getOriginalFilename();
        String extension = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase()
                : "txt";

        // Resolve source type from extension. Image extensions route to the
        // vision-in pipeline at extraction time; Office / PDF / HTML extensions
        // are staged on disk and extracted by DocumentExtractTool; plain-text
        // formats (incl. CSV) are stored directly. Unknown extensions fall back
        // to text so the upload never hard-fails.
        String sourceType = switch (extension) {
            case "pdf" -> "pdf";
            case "docx", "doc" -> "docx";
            case "xlsx", "xls" -> "xlsx";
            case "pptx", "ppt" -> "pptx";
            case "html", "htm" -> "html";
            case "txt", "md", "csv" -> "text";
            case "png", "jpg", "jpeg", "webp", "gif", "bmp", "tiff", "tif" -> "image";
            default -> "text";
        };

        if ("text".equals(sourceType)) {
            // Text files can be stored directly without staging to disk.
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            return R.ok(rawService.addText(kbId, originalName, content));
        } else {
            // Binary files are staged under an absolute path so Tomcat temp
            // directory resolution does not affect later processing.
            Path uploadDir = Paths.get(properties.getUploadDir()).toAbsolutePath().normalize();
            Files.createDirectories(uploadDir);
            Path targetPath = uploadDir.resolve(System.currentTimeMillis() + "_" + originalName);
            file.transferTo(targetPath);
            return R.ok(rawService.addFile(kbId, originalName, sourceType,
                    file.getContentType(),
                    targetPath.toString(), file.getSize()));
        }
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "删除原始材料")
    @DeleteMapping("/knowledge-bases/{kbId}/raw/{rawId}")
    public R<Void> deleteRaw(@PathVariable Long kbId, @PathVariable Long rawId,
                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null || !kbId.equals(raw.getKbId())) {
            return R.fail(404, "Raw material not found in this knowledge base");
        }
        rawService.delete(rawId);
        kbService.decrementRawCount(kbId);
        return R.ok();
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "重新处理原始材料（force=true 时绕过 content_hash 短路）")
    @PostMapping("/knowledge-bases/{kbId}/raw/{rawId}/reprocess")
    public R<Void> reprocessRaw(@PathVariable Long kbId, @PathVariable Long rawId,
                                 @RequestParam(value = "force", defaultValue = "false") boolean force,
                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null || !kbId.equals(raw.getKbId())) {
            return R.fail(404, "Raw material not found in this knowledge base");
        }
        // Force reprocessing by clearing the hash used to skip unchanged inputs.
        if (force) {
            rawService.setLastProcessedHash(rawId, null);
        }
        rawService.reprocess(rawId);
        return R.ok();
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "请求取消正在进行的处理（仅在 processing 状态有效）")
    @PostMapping("/knowledge-bases/{kbId}/raw/{rawId}/cancel")
    public R<Void> cancelRaw(@PathVariable Long kbId, @PathVariable Long rawId,
                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null || !kbId.equals(raw.getKbId())) {
            return R.fail(404, "Raw material not found in this knowledge base");
        }
        // requestCancel is idempotent: a no-op when the row is not processing,
        // so repeated clicks (or a click after the run already finished) are
        // safe and do not surface an error to the user.
        rawService.requestCancel(rawId);
        return R.ok();
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "下载原始材料")
    @GetMapping("/knowledge-bases/{kbId}/raw/{rawId}/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadRaw(
            @PathVariable Long kbId,
            @PathVariable Long rawId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) throws IOException {
        verifyKBWorkspace(kbId, workspaceId);
        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null || !kbId.equals(raw.getKbId())) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }

        String rawTitle = raw.getTitle();
        String filename = (rawTitle != null && !rawTitle.isBlank())
                ? rawTitle : ("source-" + rawId);

        org.springframework.core.io.Resource resource;
        long contentLength;
        org.springframework.http.MediaType mediaType;
        String sourceType = raw.getSourceType();

        if ("text".equals(sourceType)) {
            // Text materials live in the DB column — re-encode the stored content as bytes.
            String content = raw.getOriginalContent();
            if (content == null) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            resource = new org.springframework.core.io.ByteArrayResource(bytes);
            contentLength = bytes.length;
            mediaType = org.springframework.http.MediaType.parseMediaType("text/plain;charset=UTF-8");
            // Manually-pasted text rows often have no extension on the title — give the
            // download a sane suffix so the OS knows what to do with it.
            if (!filename.contains(".")) filename = filename + ".txt";
        } else {
            // Binary materials live on disk — sandbox to the configured upload dir so
            // a tampered source_path can't escape and serve arbitrary files.
            String sourcePath = raw.getSourcePath();
            if (sourcePath == null || sourcePath.isBlank()) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            Path path = Paths.get(sourcePath).toAbsolutePath().normalize();
            Path uploadDir = Paths.get(properties.getUploadDir()).toAbsolutePath().normalize();
            if (!path.startsWith(uploadDir)) {
                log.warn("[Wiki] Download rejected: rawId={} path={} outside uploadDir={}",
                        rawId, path, uploadDir);
                return org.springframework.http.ResponseEntity
                        .status(org.springframework.http.HttpStatus.FORBIDDEN).build();
            }
            if (!Files.isRegularFile(path)) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            resource = new org.springframework.core.io.FileSystemResource(path);
            contentLength = Files.size(path);
            mediaType = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        }

        // RFC 5987 — provide both ASCII-safe filename= (for old browsers) and
        // UTF-8 filename*= so non-ASCII titles (e.g. 中医诊断学.docx) survive intact.
        String asciiFallback = filename.replaceAll("[^\\x20-\\x7E]", "_")
                .replace("\"", "_").replace("\\", "_");
        String encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String contentDisposition = "attachment; filename=\"" + asciiFallback
                + "\"; filename*=UTF-8''" + encoded;

        return org.springframework.http.ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(resource);
    }

    // ==================== Wiki Pages ====================

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取 Wiki 页面列表（可按原始材料过滤）")
    @GetMapping("/knowledge-bases/{kbId}/pages")
    public R<List<WikiPageEntity>> listPages(@PathVariable Long kbId,
                                              @RequestParam(required = false) Long rawId,
                                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        if (rawId != null) return R.ok(pageService.listBySourceRawId(kbId, rawId));
        return R.ok(pageService.listByKbId(kbId));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取 Wiki 页面内容")
    @GetMapping("/knowledge-bases/{kbId}/pages/{slug}")
    public R<WikiPageEntity> getPage(@PathVariable Long kbId, @PathVariable String slug,
                                      @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) return R.fail(404, "Page not found");
        return R.ok(page);
    }

    /**
     * Lightweight wikilink resolution index.
     * <p>
     * The viewer's wikilink resolver needs a {slug, title, archived} list that
     * (1) is not constrained by the user's selected raw-material filter, and
     * (2) is not paginated. The general page list endpoint above is filtered
     * by rawId and may scope down based on UI state, so this is a separate,
     * minimal endpoint dedicated to the resolver.
     * <p>
     * Archived pages are excluded by default. Pass {@code includeArchived=true}
     * to retrieve archived rows as well (useful when the renderer needs to mark
     * existing links to archived targets as such instead of treating them as
     * broken links).
     */
    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取 Wiki 页面引用索引（slug/title/archived，供 wikilink 解析）")
    @GetMapping("/knowledge-bases/{kbId}/pages/refs")
    public R<Map<String, Object>> listPageRefs(
            @PathVariable Long kbId,
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        List<WikiPageService.PageRef> items = pageService.listAllRefs(kbId, includeArchived);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kbId", kbId);
        body.put("items", items);
        return R.ok(body);
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "手动编辑 Wiki 页面")
    @PutMapping("/knowledge-bases/{kbId}/pages/{slug}")
    public R<WikiPageEntity> updatePage(@PathVariable Long kbId, @PathVariable String slug,
                                         @RequestBody Map<String, String> body,
                                         @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return R.ok(pageService.updatePageManually(kbId, slug, body.get("content"), body.get("summary")));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "删除 Wiki 页面")
    @DeleteMapping("/knowledge-bases/{kbId}/pages/{slug}")
    public R<Void> deletePage(@PathVariable Long kbId, @PathVariable String slug,
                               @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        pageService.delete(kbId, slug);
        kbService.setPageCount(kbId, pageService.countByKbId(kbId));
        return R.ok();
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "批量删除 Wiki 页面")
    @DeleteMapping("/knowledge-bases/{kbId}/pages/batch")
    public R<Integer> batchDeletePages(@PathVariable Long kbId,
                                        @RequestBody List<String> slugs,
                                        @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        int deleted = pageService.batchDelete(kbId, slugs);
        kbService.setPageCount(kbId, pageService.countByKbId(kbId));
        return R.ok(deleted);
    }

    /**
     * Cross-KB page lookup by title or slug, scoped to the requesting user's
     * workspace. Used by the global wikilink click delegator: when a user
     * clicks a {@code [[Title]]} reference inside a chat message, the
     * frontend has no idea which KB the wiki tool read from, so this
     * endpoint searches every KB visible to the user and returns the
     * candidates.
     * <p>
     * Lookup precedence:
     * <ul>
     *   <li>If {@code slug} is provided, match against {@code page.slug}
     *       (case-insensitive exact).</li>
     *   <li>Else if {@code title} is provided, match against
     *       {@code page.title} (case-insensitive exact, trimmed).</li>
     * </ul>
     * Returns {@code []} if neither parameter is supplied or no match is
     * found in any visible KB.
     */
    @RequireWorkspaceRole("viewer")
    @Operation(summary = "跨 KB 按 title 或 slug 查找页面（chat 端 wikilink 跳转用）")
    @GetMapping("/pages/lookup")
    public R<List<Map<String, Object>>> lookupPages(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String slug,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        List<Map<String, Object>> matches = new java.util.ArrayList<>();
        if ((title == null || title.isBlank()) && (slug == null || slug.isBlank())) {
            return R.ok(matches);
        }
        String slugLower = slug != null ? slug.trim().toLowerCase(java.util.Locale.ROOT) : null;
        String titleLower = title != null ? title.trim().toLowerCase(java.util.Locale.ROOT) : null;

        for (WikiKnowledgeBaseEntity kb : kbService.listByWorkspace(wsId)) {
            // listSummaries excludes archived; that's what we want for the
            // chat-click navigation contract (clicking a [[link]] should
            // take the user to an active page, not a tombstone).
            for (WikiPageEntity p : pageService.listSummaries(kb.getId())) {
                boolean hit = false;
                if (slugLower != null && p.getSlug() != null
                        && p.getSlug().toLowerCase(java.util.Locale.ROOT).equals(slugLower)) {
                    hit = true;
                } else if (titleLower != null && p.getTitle() != null
                        && p.getTitle().trim().toLowerCase(java.util.Locale.ROOT).equals(titleLower)) {
                    hit = true;
                }
                if (!hit) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kbId", String.valueOf(kb.getId()));
                row.put("kbName", kb.getName());
                row.put("slug", p.getSlug());
                row.put("title", p.getTitle());
                row.put("archived", false);
                matches.add(row);
            }
        }
        return R.ok(matches);
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取反向链接")
    @GetMapping("/knowledge-bases/{kbId}/pages/{slug}/backlinks")
    public R<List<WikiPageEntity>> getBacklinks(@PathVariable Long kbId, @PathVariable String slug,
                                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return R.ok(pageService.getBacklinks(kbId, slug));
    }

    /**
     * Rename a page within a KB. The old slug is no longer reachable after
     * this call; every wikilink in the KB that pointed at it is rewritten
     * to the new slug in the same transaction. Aliases ({@code [[oldSlug|x]]})
     * are preserved by carrying the alias text over to the new target.
     */
    @RequireWorkspaceRole("admin")
    @Operation(summary = "重命名 Wiki 页面，并级联更新所有引用方")
    @PostMapping("/knowledge-bases/{kbId}/pages/{slug}/rename")
    public R<Map<String, Object>> renamePage(
            @PathVariable Long kbId,
            @PathVariable String slug,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        String newSlug = body == null ? null : body.get("newSlug");
        WikiPageEntity renamed;
        try {
            renamed = pageService.rename(kbId, slug, newSlug);
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            return R.fail(409, e.getMessage());
        }
        if (renamed == null) return R.fail(404, "Page not found");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("oldSlug", slug);
        out.put("newSlug", renamed.getSlug());
        out.put("pageId", String.valueOf(renamed.getId()));
        return R.ok(out);
    }

    // ==================== Wikilink lint (broken-link scan) ====================

    /**
     * Start a KB-wide broken-link scan. Job-based async: returns immediately
     * with a {@code {jobId, status, startedAt}} envelope; the real work runs
     * on a single-threaded background executor and writes per-page results
     * back to {@code mate_wiki_page.broken_links}. Idempotent under in-flight
     * load — repeated POSTs while a scan is queued or running return the
     * existing job rather than queueing duplicates.
     */
    @RequireWorkspaceRole("member")
    @Operation(summary = "启动 Wiki 死链扫描 job（异步）")
    @PostMapping("/knowledge-bases/{kbId}/lint/broken-links")
    public R<Map<String, Object>> startBrokenLinksScan(
            @PathVariable Long kbId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiLintJobService.LintJob job = lintJobService.startOrGetRunning(kbId);
        return R.ok(jobEnvelope(job));
    }

    /**
     * Read the most recent completed scan result for {@code kbId}. Aggregated
     * from persisted {@code broken_links} fields, so it survives a server
     * restart that drops the in-memory job state.
     */
    @RequireWorkspaceRole("viewer")
    @Operation(summary = "读取最近一次死链扫描的聚合结果")
    @GetMapping("/knowledge-bases/{kbId}/lint/broken-links")
    public R<Map<String, Object>> getBrokenLinksReport(
            @PathVariable Long kbId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiLintJobService.Aggregate agg = lintJobService.aggregate(kbId);
        if (agg == null) {
            return R.fail(404, "no scan yet, POST to start one");
        }
        WikiLintJobService.LintJob latest = lintJobService.getLatestJob(kbId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kbId", agg.kbId());
        body.put("jobId", latest != null ? latest.jobId() : null);
        body.put("completedAt", agg.completedAt());
        body.put("totalPages", agg.totalPages());
        body.put("pagesWithBrokenLinks", agg.pagesWithBrokenLinks());
        body.put("totalBrokenRefs", agg.totalBrokenRefs());
        body.put("pages", agg.pages());
        return R.ok(body);
    }

    /**
     * Optional job-status endpoint. Not strictly needed for the v1 UX
     * (the frontend can poll the aggregate endpoint and watch
     * {@code completedAt}), but useful for debugging and future progress
     * reporting.
     */
    @RequireWorkspaceRole("viewer")
    @Operation(summary = "查询 Wiki 死链扫描 job 状态")
    @GetMapping("/knowledge-bases/{kbId}/lint/broken-links/jobs/{jobId}")
    public R<Map<String, Object>> getBrokenLinksJob(
            @PathVariable Long kbId,
            @PathVariable String jobId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiLintJobService.LintJob job = lintJobService.getJob(jobId);
        if (job == null || !job.kbId().equals(kbId)) {
            return R.fail(404, "job not found");
        }
        return R.ok(jobEnvelope(job));
    }

    private Map<String, Object> jobEnvelope(WikiLintJobService.LintJob job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.jobId());
        body.put("kbId", job.kbId());
        body.put("status", job.status().name().toLowerCase());
        body.put("startedAt", job.startedAt());
        body.put("completedAt", job.completedAt());
        body.put("totalPages", job.totalPages());
        body.put("pagesWithBrokenLinks", job.pagesWithBrokenLinks());
        body.put("totalBrokenRefs", job.totalBrokenRefs());
        if (job.errorMessage() != null) body.put("errorMessage", job.errorMessage());
        return body;
    }

    // RFC-051 PR-7 follow-up: archive surfaces. Default-list is filtered, so the UI
    // needs a dedicated endpoint to enumerate archived pages and a way to flip the
    // flag via REST (the agent tools wiki_archive_page / wiki_unarchive_page already
    // exist, but the admin UI shouldn't have to go through agent plumbing).

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "列出知识库中所有 archived=1 的页面（不含 content）")
    @GetMapping("/knowledge-bases/{kbId}/pages/archived")
    public R<List<WikiPageEntity>> listArchivedPages(@PathVariable Long kbId,
                                                      @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return R.ok(pageService.listArchivedByKbId(kbId));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "归档单个页面（软归档；可恢复）")
    @PostMapping("/knowledge-bases/{kbId}/pages/{slug}/archive")
    public R<Map<String, Object>> archivePage(@PathVariable Long kbId, @PathVariable String slug,
                                               @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        boolean changed = pageService.setArchived(kbId, slug, true);
        return R.ok(Map.of("slug", slug, "archived", true, "changed", changed));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "取消归档")
    @PostMapping("/knowledge-bases/{kbId}/pages/{slug}/unarchive")
    public R<Map<String, Object>> unarchivePage(@PathVariable Long kbId, @PathVariable String slug,
                                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        boolean changed = pageService.setArchived(kbId, slug, false);
        return R.ok(Map.of("slug", slug, "archived", false, "changed", changed));
    }

    // ==================== Processing ====================

    @RequireWorkspaceRole("member")
    @Operation(summary = "触发知识库处理（异步）；force=true 时清空所有 last_processed_hash 并重新入队全部材料")
    @PostMapping("/knowledge-bases/{kbId}/process")
    public R<Map<String, Object>> processKB(@PathVariable Long kbId,
                                             @RequestParam(value = "force", defaultValue = "false") boolean force,
                                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        int queued = processingService.processKB(kbId, force);
        return R.ok(Map.of("queued", queued, "force", force));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取处理状态")
    @GetMapping("/knowledge-bases/{kbId}/processing-status")
    public R<Map<String, Object>> getProcessingStatus(@PathVariable Long kbId,
                                                       @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) return R.fail(404, "Knowledge base not found");

        List<WikiRawMaterialEntity> rawList = rawService.listByKbId(kbId);
        long pending = rawList.stream().filter(r -> "pending".equals(r.getProcessingStatus())).count();
        long processing = rawList.stream().filter(r -> "processing".equals(r.getProcessingStatus())).count();
        long completed = rawList.stream().filter(r -> "completed".equals(r.getProcessingStatus())).count();
        long partial = rawList.stream().filter(r -> "partial".equals(r.getProcessingStatus())).count();
        long failed = rawList.stream().filter(r -> "failed".equals(r.getProcessingStatus())).count();
        long cancelled = rawList.stream().filter(r -> "cancelled".equals(r.getProcessingStatus())).count();

        // Derive totalPages from the real `mate_wiki_page` table rather than
        // `kb.pageCount`, which can lag behind if a processing run aborts
        // between page creation and the page-count refresh. Using the live
        // count keeps the UI honest even when the bookkeeping field is stale.
        int realPageCount = pageService.countByKbId(kbId);
        // Self-heal: if the stored pageCount drifted from the real count,
        // quietly fix it so downstream callers reading `kb.pageCount` see
        // the truth too. This is the cheapest place to repair without
        // disrupting the in-flight processing path.
        if (kb.getPageCount() == null || kb.getPageCount() != realPageCount) {
            try {
                kbService.setPageCount(kbId, realPageCount);
            } catch (Exception ignore) {
                // Self-heal is best-effort; never let it fail the status read.
            }
        }

        // KB-level status field reflects whether the heavy pipeline is still
        // running; once it flips back to "active" no raw material is actually
        // mid-processing, regardless of any row whose `processing_status`
        // didn't get its terminal-state update (a known failure mode in
        // long-running ingest paths). Override the per-raw count so the UI
        // doesn't show "processing" forever after the KB itself is idle.
        boolean kbIdle = !"processing".equals(kb.getStatus());
        long effectiveProcessing = kbIdle ? 0 : processing;
        long inferredCompleted = kbIdle ? (completed + (realPageCount > 0 ? processing : 0)) : completed;

        // Per-raw progress snapshot — lets callers distinguish "LLM still
        // working through phase-b 4 of 10 pages" from "thread is wedged".
        // Without this the polling client sees `processing: 1` for the entire
        // multi-minute pipeline and can't tell whether to wait or alert.
        // `staleSeconds` is the gap since the raw's last bookkeeping update;
        // a freshly-progressing pipeline updates progressDone every minute or
        // two, so a gap > 600s suggests a real stall worth investigating.
        long nowMs = System.currentTimeMillis();
        java.util.List<Map<String, Object>> rawProgress = new java.util.ArrayList<>(rawList.size());
        for (WikiRawMaterialEntity r : rawList) {
            long staleSeconds = -1;
            if (r.getUpdateTime() != null) {
                long updatedMs = r.getUpdateTime()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
                staleSeconds = (nowMs - updatedMs) / 1000L;
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("rawId", r.getId());
            row.put("title", r.getTitle());
            row.put("status", r.getProcessingStatus());
            row.put("phase", r.getProgressPhase());
            row.put("done", r.getProgressDone() == null ? 0 : r.getProgressDone());
            row.put("total", r.getProgressTotal() == null ? 0 : r.getProgressTotal());
            row.put("staleSeconds", staleSeconds);
            rawProgress.add(row);
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", kb.getStatus());
        body.put("pending", pending);
        body.put("processing", effectiveProcessing);
        body.put("completed", inferredCompleted);
        body.put("partial", partial);
        body.put("failed", failed);
        body.put("cancelled", cancelled);
        body.put("totalRaw", rawList.size());
        body.put("totalPages", realPageCount);
        body.put("rawProgress", rawProgress);
        return R.ok(body);
    }

    /**
     * RFC-012 M3：订阅指定 KB 的处理进度 SSE 流。
     * <p>
     * 客户端通过 {@code new EventSource('/api/v1/wiki/knowledge-bases/{kbId}/progress')} 订阅，
     * 然后按事件名监听：
     * <ul>
     *   <li>{@code raw.started}    — 某个 raw material 进入处理</li>
     *   <li>{@code route.done}     — phase A 完成、phase B 启动（此时 total 已确定）</li>
     *   <li>{@code chunk.done}     — phase B 单页落地（带 done/total）</li>
     *   <li>{@code raw.completed}  — raw material 处理完成（终态：completed/partial）</li>
     *   <li>{@code raw.failed}     — raw material 处理失败</li>
     * </ul>
     * <p>
     * SSE 是 best-effort：服务端断线、客户端断线、代理切流都可能丢事件，
     * 因此前端仍需保留 60s 兜底轮询 {@code GET .../processing-status} 作为真源。
     * <p>
     * Emitter 默认 30 分钟超时，足以覆盖最长的 raw 处理时间；超时后客户端
     * 自动重连（EventSource 默认行为）。
     */
    @RequireWorkspaceRole("viewer")
    @Operation(summary = "订阅处理进度 SSE")
    @GetMapping(value = "/knowledge-bases/{kbId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeProgress(@PathVariable Long kbId,
                                         @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        // RFC-058 PR-1: Utf8SseEmitter 显式 charset=UTF-8，防止中文 SSE 乱码
        SseEmitter emitter = new Utf8SseEmitter(30L * 60 * 1000); // 30min
        progressBus.subscribe(kbId, emitter);

        emitter.onCompletion(() -> {
            progressBus.unsubscribe(kbId, emitter);
            log.debug("[Wiki SSE] emitter completed: kbId={}", kbId);
        });
        emitter.onTimeout(() -> {
            progressBus.unsubscribe(kbId, emitter);
            try { emitter.complete(); } catch (Exception ignore) { /* best-effort */ }
            log.debug("[Wiki SSE] emitter timeout: kbId={}", kbId);
        });
        emitter.onError(e -> {
            progressBus.unsubscribe(kbId, emitter);
            log.debug("[Wiki SSE] emitter error: kbId={}, cause={}", kbId, e.getMessage());
        });

        // 立即发一个 hello 事件，确认连接已建立
        try {
            emitter.send(SseEmitter.event().name(WikiProgressBus.EVENT_HEARTBEAT)
                    .data("{\"ts\":" + System.currentTimeMillis() + ",\"hello\":true}"));
        } catch (Exception e) {
            log.debug("[Wiki SSE] initial heartbeat send failed: {}", e.getMessage());
        }

        return emitter;
    }

    // ==================== Workspace Verification ====================

    private void verifyKBWorkspace(Long kbId, Long headerWorkspaceId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            throw new MateClawException(404, "Knowledge base not found");
        }
        long wsId = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (kb.getWorkspaceId() != null && !kb.getWorkspaceId().equals(wsId)) {
            throw new MateClawException("err.common.wrong_workspace", 403, "资源不属于当前工作区");
        }
    }
}
