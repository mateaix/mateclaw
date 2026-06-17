package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.job.WikiJobStep;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the entity-extraction runtime logic against a mocked model + DB:
 * the structured LLM output is parsed, entities are de-duplicated across
 * chunks via the run cache, mentions are written per occurrence, and a
 * resolved relation triple is persisted. This exercises the real
 * {@link WikiEntityExtractionService} control flow, not just a stubbed call.
 */
class WikiEntityExtractionServiceTest {

    private static final Long KB_ID = 1L;
    private static final Long RAW_ID = 100L;

    private static final String LLM_JSON = """
            {
              "entities": [
                {"name": "Alice", "type": "person", "aliases": [], "description": "An engineer", "evidence": "Alice works at Acme"},
                {"name": "Acme", "type": "organization", "aliases": [], "description": "A company", "evidence": "Acme Corp"}
              ],
              "relations": [
                {"subject": "Alice", "predicate": "works for", "object": "Acme", "evidence": "Alice works at Acme"}
              ]
            }
            """;

    private WikiKnowledgeBaseService kbService;
    private WikiChunkService chunkService;
    private WikiEmbeddingService embeddingService;
    private WikiModelRoutingService routingService;
    private ModelConfigService modelConfigService;
    private WikiEntityMapper entityMapper;
    private WikiEntityMentionMapper mentionMapper;
    private WikiEntityRelationMapper relationMapper;
    private WikiPageCitationMapper citationMapper;

    private WikiEntityExtractionService service;

    /** Simulated entity store so selectById sees what insert assigned. */
    private final Map<Long, WikiEntityEntity> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        chunkService = mock(WikiChunkService.class);
        embeddingService = mock(WikiEmbeddingService.class);
        routingService = mock(WikiModelRoutingService.class);
        modelConfigService = mock(ModelConfigService.class);
        entityMapper = mock(WikiEntityMapper.class);
        mentionMapper = mock(WikiEntityMentionMapper.class);
        relationMapper = mock(WikiEntityRelationMapper.class);
        citationMapper = mock(WikiPageCitationMapper.class);

        service = new WikiEntityExtractionService(
                kbService, chunkService, embeddingService, routingService,
                modelConfigService, new ObjectMapper(),
                entityMapper, mentionMapper, relationMapper, citationMapper);

        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(KB_ID);
        when(kbService.getById(KB_ID)).thenReturn(kb);

        // Model routing → a mock ChatModel that always returns the canned JSON.
        ChatModel canned = cannedModel(LLM_JSON);
        when(routingService.selectModelId(eq(KB_ID), any(), eq(WikiJobStep.ENTITY_EXTRACTION)))
                .thenReturn(7L);
        when(routingService.buildChatModel(7L)).thenReturn(canned);

        // No prior entities (with embeddings), no embedding vectors → exercise
        // the exact-key dedup path (no embedding merge).
        when(entityMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(entityMapper.selectOne(any())).thenReturn(null);
        when(embeddingService.embedQuery(anyLong(), any())).thenReturn(null);

        // No existing mentions → every chunk gets processed.
        when(mentionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(citationMapper.listPageIdsByChunkId(anyLong())).thenReturn(Collections.emptyList());
        when(relationMapper.selectOne(any())).thenReturn(null);

        // insert assigns a snowflake-like id and records the row so selectById works.
        AtomicLong seq = new AtomicLong(1000L);
        when(entityMapper.insert(any(WikiEntityEntity.class))).thenAnswer(inv -> {
            WikiEntityEntity e = inv.getArgument(0);
            e.setId(seq.getAndIncrement());
            store.put(e.getId(), e);
            return 1;
        });
        when(entityMapper.selectById(anyLong())).thenAnswer(inv -> store.get(inv.getArgument(0)));
    }

    @Test
    @DisplayName("extractForRaw: dedups entities across chunks, writes mentions and a relation")
    void extractForRaw_buildsGraph() {
        when(chunkService.listByRawId(RAW_ID)).thenReturn(List.of(
                chunk(1L, "Alice works at Acme."),
                chunk(2L, "Acme promoted Alice.")));

        int touched = service.extractForRaw(KB_ID, RAW_ID);

        // Two distinct canonical entities despite two chunks naming them.
        assertEquals(2, touched, "should resolve exactly two canonical entities");
        verify(entityMapper, times(2)).insert(any(WikiEntityEntity.class));

        // One mention per (entity, chunk) occurrence → 2 entities * 2 chunks.
        verify(mentionMapper, times(4)).insert(any(WikiEntityMentionEntity.class));

        // The works_for triple is persisted once per chunk it appears in.
        verify(relationMapper, times(2)).insert(any(WikiEntityRelationEntity.class));
    }

    @Test
    @DisplayName("extractForRaw: skips chunks that already have mentions")
    void extractForRaw_skipsProcessedChunks() {
        when(chunkService.listByRawId(RAW_ID)).thenReturn(List.of(chunk(1L, "Alice works at Acme.")));
        when(mentionMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        int touched = service.extractForRaw(KB_ID, RAW_ID);

        assertEquals(0, touched);
        verify(entityMapper, times(0)).insert(any(WikiEntityEntity.class));
    }

    private WikiChunkEntity chunk(Long id, String content) {
        WikiChunkEntity c = new WikiChunkEntity();
        c.setId(id);
        c.setKbId(KB_ID);
        c.setRawId(RAW_ID);
        c.setContent(content);
        return c;
    }

    private ChatModel cannedModel(String body) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage(body),
                        ChatGenerationMetadata.builder().finishReason("STOP").build()))));
        return model;
    }
}
