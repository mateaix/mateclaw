package vip.mate.kbopen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.kbopen.dto.KbOpenApiDtos;
import vip.mate.kbopen.dto.KbOpenApiDtos.*;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiEntityEntity;
import vip.mate.wiki.model.WikiEntityMentionEntity;
import vip.mate.wiki.model.WikiEntityRelationEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiEntityMapper;
import vip.mate.wiki.repository.WikiEntityMentionMapper;
import vip.mate.wiki.repository.WikiEntityRelationMapper;
import vip.mate.wiki.service.WikiPageService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Assembly layer for KB Open API endpoints that require multi-table joins
 * or aggregation logic not covered by a single existing service method.
 *
 * <p>A6 constraint: returns pure DTOs, never touches HttpServletRequest/R<T>.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbOpenApiService {

    private final WikiPageService pageService;
    private final WikiEntityMapper entityMapper;
    private final WikiEntityRelationMapper relationMapper;
    private final WikiEntityMentionMapper mentionMapper;
    private final WikiChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    // ── Page card assembly ────────────────────────────────────────────────

    /**
     * Assemble a PageCard from a WikiPageEntity, honoring the mode parameter.
     *
     * @param page   the resolved page entity
     * @param mode   summary (default) / full / section:{heading}
     * @param fields optional comma-separated field filter (only for summary mode)
     */
    public PageCard assembleCard(WikiPageEntity page, String mode, String fields) {
        String content = resolveContent(page, mode);

        Map<String, Object> metadata = parseMetadata(page.getMetadataJson());
        if (fields != null && !fields.isBlank() && "summary".equals(mode)) {
            Set<String> wanted = Arrays.stream(fields.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            metadata = metadata.entrySet().stream()
                    .filter(e -> wanted.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        return new PageCard(
                page.getSlug(),
                page.getTitle(),
                page.getPageType(),
                page.getKnowledgeLayer(),
                page.getTitle(),
                page.getSummary(),
                metadata.isEmpty() ? null : metadata,
                content,
                buildSourceRef(page),
                page.getVersion(),
                page.getUpdateTime()
        );
    }

    private String resolveContent(WikiPageEntity page, String mode) {
        if (mode == null || mode.isBlank() || "summary".equals(mode)) {
            return null; // summary mode: no content, caller uses summary+fields
        }
        if ("full".equals(mode)) {
            return page.getContent();
        }
        if (mode.startsWith("section:")) {
            String heading = mode.substring("section:".length()).trim();
            return extractSection(page.getContent(), heading);
        }
        return null;
    }

    /** Extract the content under a markdown heading (## or ###). */
    private String extractSection(String content, String heading) {
        if (content == null || heading == null) return null;
        String[] lines = content.split("\n");
        int start = -1;
        int headingLevel = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                String text = trimmed.substring(level).trim();
                if (start == -1 && text.equalsIgnoreCase(heading)) {
                    start = i;
                    headingLevel = level;
                    continue;
                }
                if (start != -1 && level <= headingLevel) {
                    // next heading at same or higher level → end of section
                    return joinLines(lines, start, i);
                }
            }
        }
        return start != -1 ? joinLines(lines, start, lines.length) : null;
    }

    private String joinLines(String[] lines, int from, int to) {
        return String.join("\n", Arrays.copyOfRange(lines, from, to)).trim();
    }

    private SourceRef buildSourceRef(WikiPageEntity page) {
        Set<Long> rawIds = parseRawIds(page.getSourceRawIds());
        if (rawIds.isEmpty()) return null;
        // rawTitles are in sourceEntries (JSON array of {rawId, rawTitle}) if available
        List<String> titles = parseSourceTitles(page.getSourceEntries());
        return new SourceRef(rawIds, titles);
    }

    // ── Traverse ──────────────────────────────────────────────────────────

    /**
     * Resolve the primary entity for a page (salience-highest mention) and
     * traverse the entity relation graph.
     */
    public TraverseResult traverse(Long kbId, String slug, String relation,
                                   int depth, String direction, int limit) {
        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            throw new MateClawException(404, "Page not found: " + slug);
        }

        // Resolve primary entity: the salience-highest entity mentioned by this page
        Long primaryEntityId = resolvePrimaryEntity(page.getId());
        if (primaryEntityId == null) {
            // No entity mentions → return empty graph with root as page-level info
            return new TraverseResult(
                    new TraverseNode(null, page.getTitle(),
                            page.getPageType(), slug),
                    List.of(), List.of());
        }

        WikiEntityEntity root = entityMapper.selectById(primaryEntityId);
        TraverseNode rootNode = new TraverseNode(
                root.getId(), root.getCanonicalName(), root.getType(), slug);

        // BFS traversal
        Set<Long> visited = new LinkedHashSet<>();
        visited.add(primaryEntityId);
        List<TraverseEdge> allEdges = new ArrayList<>();
        Set<Long> neighborIds = new LinkedHashSet<>();

        collectEdges(kbId, primaryEntityId, relation, direction, limit, allEdges, neighborIds, visited);

        if (depth >= 2) {
            // Second hop: traverse each first-hop neighbor
            for (Long neighborId : new ArrayList<>(neighborIds)) {
                if (visited.size() > limit * 3) break; // explosion guard
                collectEdges(kbId, neighborId, relation, direction, limit, allEdges, neighborIds, visited);
            }
        }

        // Assemble neighbor nodes
        neighborIds.remove(primaryEntityId);
        List<TraverseNode> nodes = new ArrayList<>();
        if (!neighborIds.isEmpty()) {
            for (WikiEntityEntity e : entityMapper.selectBatchIds(neighborIds)) {
                String entitySlug = resolveSlugForEntity(e.getId());
                nodes.add(new TraverseNode(e.getId(), e.getCanonicalName(), e.getType(), entitySlug));
            }
        }

        return new TraverseResult(rootNode, allEdges, nodes);
    }

    private void collectEdges(Long kbId, Long entityId, String relation,
                              String direction, int limit,
                              List<TraverseEdge> out, Set<Long> neighborIds, Set<Long> visited) {
        LambdaQueryWrapper<WikiEntityRelationEntity> q = new LambdaQueryWrapper<WikiEntityRelationEntity>()
                .eq(WikiEntityRelationEntity::getKbId, kbId);

        boolean outgoing = !"incoming".equals(direction);
        boolean incoming = !"outgoing".equals(direction);
        if (outgoing && incoming) {
            q.and(w -> w.eq(WikiEntityRelationEntity::getSubjectEntityId, entityId)
                    .or().eq(WikiEntityRelationEntity::getObjectEntityId, entityId));
        } else if (outgoing) {
            q.eq(WikiEntityRelationEntity::getSubjectEntityId, entityId);
        } else {
            q.eq(WikiEntityRelationEntity::getObjectEntityId, entityId);
        }

        if (relation != null && !relation.isBlank()) {
            q.like(WikiEntityRelationEntity::getPredicate, relation);
        }
        q.last("LIMIT " + Math.max(1, Math.min(limit, 50)));

        for (WikiEntityRelationEntity r : relationMapper.selectList(q)) {
            TraverseEdge edge = new TraverseEdge(
                    r.getPredicate(),
                    r.getSubjectEntityId(),
                    r.getObjectEntityId(),
                    null, null, // names filled by caller via batch lookup
                    r.getEvidence(),
                    r.getConfidence() != null ? r.getConfidence().doubleValue() : null,
                    resolveSourceHandle(r.getEvidenceChunkId())
            );
            out.add(edge);
            if (!r.getSubjectEntityId().equals(entityId)) neighborIds.add(r.getSubjectEntityId());
            if (!r.getObjectEntityId().equals(entityId)) neighborIds.add(r.getObjectEntityId());
            visited.add(r.getSubjectEntityId());
            visited.add(r.getObjectEntityId());
        }
    }

    private Long resolvePrimaryEntity(Long pageId) {
        List<WikiEntityMentionEntity> mentions = mentionMapper.selectList(
                new LambdaQueryWrapper<WikiEntityMentionEntity>()
                        .eq(WikiEntityMentionEntity::getPageId, pageId)
                        .orderByDesc(WikiEntityMentionEntity::getConfidence)
                        .last("LIMIT 1"));
        return mentions.isEmpty() ? null : mentions.get(0).getEntityId();
    }

    private String resolveSlugForEntity(Long entityId) {
        List<WikiEntityMentionEntity> mentions = mentionMapper.selectList(
                new LambdaQueryWrapper<WikiEntityMentionEntity>()
                        .eq(WikiEntityMentionEntity::getEntityId, entityId)
                        .isNotNull(WikiEntityMentionEntity::getPageId)
                        .last("LIMIT 1"));
        if (mentions.isEmpty()) return null;
        WikiPageEntity page = pageService.getById(mentions.get(0).getPageId());
        return page != null ? page.getSlug() : null;
    }

    private String resolveSourceHandle(Long chunkId) {
        if (chunkId == null) return null;
        // Find the first page that cites this chunk
        List<WikiEntityMentionEntity> mentions = mentionMapper.selectList(
                new LambdaQueryWrapper<WikiEntityMentionEntity>()
                        .eq(WikiEntityMentionEntity::getChunkId, chunkId)
                        .isNotNull(WikiEntityMentionEntity::getPageId)
                        .last("LIMIT 1"));
        if (mentions.isEmpty()) return null;
        return "p:" + mentions.get(0).getPageId();
    }

    // ── JSON parsing helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Set<Long> parseRawIds(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            List<Integer> ids = objectMapper.readValue(json, new TypeReference<List<Integer>>() {});
            return ids.stream().map(Integer::longValue).collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            return Set.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseSourceTitles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> entries = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return entries.stream()
                    .map(e -> String.valueOf(e.getOrDefault("rawTitle", "")))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }
}
