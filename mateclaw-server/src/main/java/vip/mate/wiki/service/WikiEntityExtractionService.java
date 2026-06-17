package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.dto.EntityExtractionResult;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.WikiKbConfig;
import vip.mate.wiki.job.WikiKbConfigParser;
import vip.mate.wiki.job.WikiModelRoutingService;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiEntityEntity;
import vip.mate.wiki.model.WikiEntityMentionEntity;
import vip.mate.wiki.model.WikiEntityRelationEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiEntityMapper;
import vip.mate.wiki.repository.WikiEntityMentionMapper;
import vip.mate.wiki.repository.WikiEntityRelationMapper;
import vip.mate.wiki.repository.WikiPageCitationMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts a named-entity knowledge graph from source chunks: it pulls
 * entities (person, organization, location, ...) and subject→predicate→object
 * relations out of each chunk via a structured LLM call, resolves entities to
 * canonical nodes (exact-key dedup plus optional embedding near-merge), and
 * persists nodes, mentions, and edges into the {@code mate_wiki_entity*}
 * tables.
 *
 * <p>This is an opt-in pass gated by {@link WikiKbConfig#getEntityExtractionEnabled()};
 * it runs after ingest/embedding and never blocks the page-generation pipeline.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiEntityExtractionService {

    private static final List<String> DEFAULT_ENTITY_TYPES =
            List.of("person", "organization", "location", "event", "product", "concept");

    /** Cosine threshold above which a new entity is merged into an existing same-type node. */
    private static final float MERGE_THRESHOLD = 0.92f;

    /** Max chunk characters sent to the model per call, to bound token spend. */
    private static final int MAX_CHUNK_CHARS = 6000;

    /** Evidence column is capped at 500 chars in the schema. */
    private static final int MAX_EVIDENCE = 500;

    private final WikiKnowledgeBaseService kbService;
    private final WikiChunkService chunkService;
    private final WikiEmbeddingService embeddingService;
    private final WikiModelRoutingService routingService;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    private final WikiEntityMapper entityMapper;
    private final WikiEntityMentionMapper mentionMapper;
    private final WikiEntityRelationMapper relationMapper;
    private final WikiPageCitationMapper citationMapper;

    /** Extract entities from every not-yet-processed chunk of one raw material. */
    public int extractForRaw(Long kbId, Long rawId) {
        return extract(kbId, chunkService.listByRawId(rawId), false);
    }

    /**
     * Extract entities across the whole KB.
     *
     * @param force when {@code true}, re-extract chunks that already have
     *              mentions (used for a manual full rebuild); otherwise skip
     *              chunks already processed
     */
    public int extractForKb(Long kbId, boolean force) {
        return extract(kbId, chunkService.listByKbId(kbId), force);
    }

    private int extract(Long kbId, List<WikiChunkEntity> chunks, boolean force) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null || chunks == null || chunks.isEmpty()) {
            return 0;
        }
        ChatModel chatModel = resolveChatModel(kbId);
        if (chatModel == null) {
            log.warn("[WikiEntity] No chat model available for kbId={}, skipping extraction", kbId);
            return 0;
        }

        List<String> types = resolveEntityTypes(kb);
        BeanOutputConverter<EntityExtractionResult> converter =
                new BeanOutputConverter<>(EntityExtractionResult.class);
        String systemPrompt = buildSystemPrompt(types);

        // Per-run resolution cache: type+normalizedKey → entityId. Seeded lazily
        // from the DB so entities resolve consistently within and across chunks.
        Map<String, Long> resolved = new HashMap<>();
        // Same-type existing entities with embeddings, for near-duplicate merge.
        EntityIndex index = new EntityIndex(kbId);

        for (WikiChunkEntity chunk : chunks) {
            if (chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            if (!force && hasMentions(chunk.getId())) {
                continue;
            }
            try {
                EntityExtractionResult result = callExtract(chatModel, converter, systemPrompt, chunk);
                if (result == null) {
                    continue;
                }
                persistChunk(kbId, chunk, result, resolved, index);
            } catch (Exception e) {
                log.warn("[WikiEntity] Extraction failed for chunkId={} kbId={}: {}",
                        chunk.getId(), kbId, e.getMessage());
            }
        }
        return resolved.size();
    }

    // ---- LLM call ---------------------------------------------------------

    private EntityExtractionResult callExtract(ChatModel chatModel,
                                               BeanOutputConverter<EntityExtractionResult> converter,
                                               String systemPrompt,
                                               WikiChunkEntity chunk) {
        String content = chunk.getContent();
        if (content.length() > MAX_CHUNK_CHARS) {
            content = content.substring(0, MAX_CHUNK_CHARS);
        }
        String userPrompt = "Source text:\n\"\"\"\n" + content + "\n\"\"\"\n\n" + converter.getFormat();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)));
        ChatResponse response = chatModel.call(prompt);
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return converter.convert(text);
        } catch (Exception e) {
            log.debug("[WikiEntity] Structured parse failed, skipping chunk: {}", e.getMessage());
            return null;
        }
    }

    private String buildSystemPrompt(List<String> types) {
        return "You are a knowledge-graph entity extractor. From the given source text, "
                + "extract named entities and the factual relations between them.\n"
                + "Entity types to use: " + String.join(", ", types) + ".\n"
                + "Rules:\n"
                + "- Only extract entities explicitly named in the text; do not invent any.\n"
                + "- Use the most complete surface form as the name; list shorter forms as aliases.\n"
                + "- For each relation, subject and object must both appear in the entities list.\n"
                + "- Keep predicates short and snake_case (e.g. works_for, located_in, founded).\n"
                + "- Provide a short verbatim evidence quote for each entity and relation.\n"
                + "- If nothing relevant is present, return empty lists.";
    }

    // ---- persistence ------------------------------------------------------

    private void persistChunk(Long kbId, WikiChunkEntity chunk, EntityExtractionResult result,
                              Map<String, Long> resolved, EntityIndex index) {
        Long pageId = firstCitingPage(chunk.getId());

        // Resolve each entity to a canonical id, persist its mention for this chunk.
        Map<String, Long> localByName = new HashMap<>();
        if (result.getEntities() != null) {
            for (EntityExtractionResult.ExtractedEntity e : result.getEntities()) {
                if (e == null || e.getName() == null || e.getName().isBlank()) {
                    continue;
                }
                String type = normalizeType(e.getType());
                Long entityId = resolveEntity(kbId, e, type, resolved, index);
                if (entityId == null) {
                    continue;
                }
                localByName.put(normalize(e.getName()), entityId);
                if (e.getAliases() != null) {
                    for (String alias : e.getAliases()) {
                        if (alias != null && !alias.isBlank()) {
                            localByName.put(normalize(alias), entityId);
                        }
                    }
                }
                insertMention(kbId, entityId, chunk.getId(), pageId, e.getName(), e.getEvidence());
                bumpMentionCount(entityId);
            }
        }

        // Persist relations whose endpoints both resolved.
        if (result.getRelations() != null) {
            for (EntityExtractionResult.ExtractedRelation r : result.getRelations()) {
                if (r == null || r.getSubject() == null || r.getObject() == null
                        || r.getPredicate() == null || r.getPredicate().isBlank()) {
                    continue;
                }
                Long subjectId = localByName.get(normalize(r.getSubject()));
                Long objectId = localByName.get(normalize(r.getObject()));
                if (subjectId == null || objectId == null || subjectId.equals(objectId)) {
                    continue;
                }
                upsertRelation(kbId, subjectId, objectId, normalizePredicate(r.getPredicate()),
                        r.getEvidence(), chunk.getId());
            }
        }
    }

    /**
     * Resolve an extracted entity to a canonical node id: run cache → exact
     * key match in DB → embedding near-match → create new.
     */
    private Long resolveEntity(Long kbId, EntityExtractionResult.ExtractedEntity e, String type,
                               Map<String, Long> resolved, EntityIndex index) {
        String key = normalize(e.getName());
        String cacheKey = type + "" + key;
        Long cached = resolved.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        WikiEntityEntity existing = entityMapper.selectOne(new LambdaQueryWrapper<WikiEntityEntity>()
                .eq(WikiEntityEntity::getKbId, kbId)
                .eq(WikiEntityEntity::getNormalizedKey, key)
                .eq(WikiEntityEntity::getType, type)
                .last("LIMIT 1"));
        if (existing != null) {
            resolved.put(cacheKey, existing.getId());
            return existing.getId();
        }

        // Embedding near-duplicate merge across spellings/languages.
        float[] vec = embedName(kbId, e);
        if (vec != null) {
            Long near = index.findNearest(type, vec);
            if (near != null) {
                resolved.put(cacheKey, near);
                return near;
            }
        }

        WikiEntityEntity created = new WikiEntityEntity();
        created.setKbId(kbId);
        created.setCanonicalName(e.getName().trim());
        created.setNormalizedKey(key);
        created.setType(type);
        created.setAliasesJson(writeJson(e.getAliases()));
        created.setDescription(truncate(e.getDescription(), MAX_EVIDENCE));
        created.setMentionCount(0);
        created.setSalience(BigDecimal.ZERO);
        if (vec != null) {
            created.setEmbedding(WikiEmbeddingService.floatsToBytes(vec));
        }
        entityMapper.insert(created);
        resolved.put(cacheKey, created.getId());
        index.add(type, created.getId(), vec);
        return created.getId();
    }

    private void insertMention(Long kbId, Long entityId, Long chunkId, Long pageId,
                               String surfaceForm, String evidence) {
        WikiEntityMentionEntity m = new WikiEntityMentionEntity();
        m.setKbId(kbId);
        m.setEntityId(entityId);
        m.setChunkId(chunkId);
        m.setPageId(pageId);
        m.setSurfaceForm(truncate(surfaceForm, 256));
        m.setConfidence(BigDecimal.valueOf(0.9));
        m.setEvidence(truncate(evidence, MAX_EVIDENCE));
        m.setSource("llm-extracted");
        mentionMapper.insert(m);
    }

    private void bumpMentionCount(Long entityId) {
        WikiEntityEntity e = entityMapper.selectById(entityId);
        if (e == null) {
            return;
        }
        int count = (e.getMentionCount() == null ? 0 : e.getMentionCount()) + 1;
        e.setMentionCount(count);
        // Saturating popularity score in [0,1): count / (count + 5).
        e.setSalience(BigDecimal.valueOf((double) count / (count + 5.0))
                .setScale(4, RoundingMode.HALF_UP));
        entityMapper.updateById(e);
    }

    private void upsertRelation(Long kbId, Long subjectId, Long objectId, String predicate,
                                String evidence, Long chunkId) {
        WikiEntityRelationEntity existing = relationMapper.selectOne(
                new LambdaQueryWrapper<WikiEntityRelationEntity>()
                        .eq(WikiEntityRelationEntity::getKbId, kbId)
                        .eq(WikiEntityRelationEntity::getSubjectEntityId, subjectId)
                        .eq(WikiEntityRelationEntity::getPredicate, predicate)
                        .eq(WikiEntityRelationEntity::getObjectEntityId, objectId)
                        .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        WikiEntityRelationEntity rel = new WikiEntityRelationEntity();
        rel.setKbId(kbId);
        rel.setSubjectEntityId(subjectId);
        rel.setPredicate(predicate);
        rel.setObjectEntityId(objectId);
        rel.setEvidence(truncate(evidence, MAX_EVIDENCE));
        rel.setConfidence(BigDecimal.valueOf(0.8));
        rel.setSource("llm-extracted");
        rel.setEvidenceChunkId(chunkId);
        relationMapper.insert(rel);
    }

    // ---- helpers ----------------------------------------------------------

    private boolean hasMentions(Long chunkId) {
        Long count = mentionMapper.selectCount(new LambdaQueryWrapper<WikiEntityMentionEntity>()
                .eq(WikiEntityMentionEntity::getChunkId, chunkId));
        return count != null && count > 0;
    }

    private Long firstCitingPage(Long chunkId) {
        List<Long> pages = citationMapper.listPageIdsByChunkId(chunkId);
        return (pages == null || pages.isEmpty()) ? null : pages.get(0);
    }

    private float[] embedName(Long kbId, EntityExtractionResult.ExtractedEntity e) {
        try {
            String text = e.getName() + (e.getDescription() == null ? "" : ". " + e.getDescription());
            return embeddingService.embedQuery(kbId, text);
        } catch (Exception ex) {
            return null;
        }
    }

    private ChatModel resolveChatModel(Long kbId) {
        try {
            Long modelId = routingService.selectModelId(kbId, "heavy_ingest", WikiJobStep.ENTITY_EXTRACTION);
            if (modelId != null) {
                return routingService.buildChatModel(modelId);
            }
        } catch (Exception e) {
            log.warn("[WikiEntity] Model routing failed for kbId={}, using default: {}", kbId, e.getMessage());
        }
        var def = modelConfigService.getDefaultModel();
        if (def == null) {
            return null;
        }
        return routingService.buildChatModel(def.getId());
    }

    private List<String> resolveEntityTypes(WikiKnowledgeBaseEntity kb) {
        if (kb.getConfigContent() != null) {
            WikiKbConfig config = WikiKbConfigParser.parse(objectMapper, kb.getConfigContent());
            if (config != null && config.getEntityTypes() != null && !config.getEntityTypes().isEmpty()) {
                return config.getEntityTypes();
            }
        }
        return DEFAULT_ENTITY_TYPES;
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? "other" : t;
    }

    private String normalizePredicate(String predicate) {
        String p = predicate.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        return p.length() > 64 ? p.substring(0, 64) : p;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * In-memory index of same-type entity embeddings for near-duplicate merge
     * within a single extraction run. Bounded by the KB's existing entity count.
     */
    private final class EntityIndex {
        private final Map<String, List<float[]>> vectorsByType = new HashMap<>();
        private final Map<String, List<Long>> idsByType = new HashMap<>();

        EntityIndex(Long kbId) {
            List<WikiEntityEntity> existing = entityMapper.selectList(
                    new LambdaQueryWrapper<WikiEntityEntity>()
                            .eq(WikiEntityEntity::getKbId, kbId)
                            .isNotNull(WikiEntityEntity::getEmbedding));
            for (WikiEntityEntity e : existing) {
                if (e.getEmbedding() != null) {
                    add(e.getType(), e.getId(), WikiEmbeddingService.bytesToFloats(e.getEmbedding()));
                }
            }
        }

        void add(String type, Long id, float[] vec) {
            if (vec == null) {
                return;
            }
            vectorsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(vec);
            idsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(id);
        }

        Long findNearest(String type, float[] vec) {
            List<float[]> vectors = vectorsByType.get(type);
            List<Long> ids = idsByType.get(type);
            if (vectors == null || vectors.isEmpty()) {
                return null;
            }
            float best = MERGE_THRESHOLD;
            Long bestId = null;
            for (int i = 0; i < vectors.size(); i++) {
                float sim = WikiEmbeddingService.cosine(vec, vectors.get(i));
                if (sim >= best) {
                    best = sim;
                    bestId = ids.get(i);
                }
            }
            return bestId;
        }
    }
}
