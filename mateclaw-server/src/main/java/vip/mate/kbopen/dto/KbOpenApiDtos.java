package vip.mate.kbopen.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit response DTOs for the KB Open API.
 *
 * <p>A5 constraint: the open API <strong>never</strong> serializes raw
 * entities (WikiPageEntity, etc.) — every endpoint returns an explicit DTO
 * to prevent accidental field leakage (IDOR / internal column exposure).
 *
 * <p>All DTOs are records for immutability and clean JSON serialization.
 */
public final class KbOpenApiDtos {

    private KbOpenApiDtos() {}

    /** GET /pages/{slug} — entity card / page detail. */
    public record PageCard(
            String slug,
            String canonicalName,
            String pageType,
            String knowledgeLayer,
            String title,
            String summary,
            Map<String, Object> fields,
            String content,
            SourceRef source,
            Integer version,
            LocalDateTime updatedAt
    ) {}

    /** GET /pages/{slug}/trace — provenance chain. */
    public record TraceResult(
            String slug,
            String pageType,
            String knowledgeLayer,
            List<SourceGroup> sources,
            LocalDateTime extractedAt,
            Integer pageVersion
    ) {}

    public record SourceGroup(
            Long rawId,
            String rawTitle,
            List<CitationDetail> citations
    ) {}

    public record CitationDetail(
            Long chunkId,
            String snippet,
            Double confidence,
            Integer pageNumber
    ) {}

    /** GET /taxonomy — type/scope enumeration map. */
    public record TaxonomyResult(
            List<TypeCount> pageTypes,
            List<TypeCount> entityTypes,
            List<TypeCount> relationTypes
    ) {}

    public record TypeCount(String type, int count) {}

    /** GET /stats — KB metadata. */
    public record KbStats(
            Long kbId,
            String name,
            int pageCount,
            int rawCount,
            int chunkCount,
            int embeddedChunks,
            int pagesWithLinks,
            LocalDateTime lastIngest,
            String embeddingModel
    ) {}

    /** GET /whats-new — freshness/change query. */
    public record WhatsNewResult(
            Long kbId,
            LocalDateTime since,
            List<ChangedPage> changedPages,
            List<ChangedPage> stalePages
    ) {}

    public record ChangedPage(
            String slug,
            String title,
            String knowledgeLayer,
            LocalDateTime updatedAt,
            String staleReason
    ) {}

    /** POST /pages/{slug}/traverse — entity relation subgraph. */
    public record TraverseResult(
            TraverseNode root,
            List<TraverseEdge> edges,
            List<TraverseNode> nodes
    ) {}

    public record TraverseNode(
            Long entityId,
            String name,
            String type,
            String slug
    ) {}

    public record TraverseEdge(
            String predicate,
            Long fromId,
            Long toId,
            String fromName,
            String toName,
            String evidence,
            Double confidence,
            String sourceHandle
    ) {}

    /** GET /pages — page list item (lightweight). */
    public record PageListItem(
            String slug,
            String title,
            String summary,
            String pageType,
            String knowledgeLayer
    ) {}

    public record PageList(
            Long kbId,
            int count,
            List<PageListItem> pages
    ) {}

    /** Shared source reference. */
    public record SourceRef(
            Set<Long> rawIds,
            List<String> rawTitles
    ) {}
}
