package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.sse.WikiProgressBus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the structured error-code path added so the frontend can localize
 * Wiki processing failures instead of echoing raw exception text:
 * <ul>
 *   <li>{@link WikiProcessingService#classifyErrorCode} maps exceptions to the
 *       stable vocabulary, and</li>
 *   <li>a real failure propagates that code into both the persisted row
 *       (4-arg {@code updateProcessingStatus}) and the {@code RAW_FAILED}
 *       SSE payload.</li>
 * </ul>
 */
class WikiProcessingServiceErrorCodeTest {

    private WikiKnowledgeBaseService kbService;
    private WikiRawMaterialService rawService;
    private WikiChunkService chunkService;
    private WikiProgressBus progressBus;
    private WikiProcessingService service;

    private static final Long KB_ID = 7L;
    private static final Long RAW_ID = 99L;

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        rawService = mock(WikiRawMaterialService.class);
        chunkService = mock(WikiChunkService.class);
        progressBus = mock(WikiProgressBus.class);
        ObjectMapper om = new ObjectMapper();
        service = new WikiProcessingService(
                kbService, rawService, mock(WikiPageService.class), chunkService,
                mock(WikiEmbeddingService.class), new WikiLinkService(om),
                new WikiProperties(), mock(ModelConfigService.class),
                mock(AgentGraphBuilder.class), om, progressBus,
                mock(WikiCitationService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(WikiEntityExtractionService.class));
    }

    @Test
    @DisplayName("classifyErrorCode maps the stable failure vocabulary")
    void classifyErrorCode_mapsVocabulary() {
        assertEquals("AUTH_ERROR", service.classifyErrorCode(new RuntimeException("401 Unauthorized")));
        assertEquals("AUTH_ERROR", service.classifyErrorCode(new RuntimeException("invalid api key")));
        assertEquals("BILLING", service.classifyErrorCode(new RuntimeException("insufficient_quota")));
        assertEquals("MODEL_NOT_FOUND", service.classifyErrorCode(new RuntimeException("model not found")));
        assertEquals("RATE_LIMIT", service.classifyErrorCode(new RuntimeException("429 too many requests")));
        assertEquals("TIMEOUT", service.classifyErrorCode(new RuntimeException("Read timed out")));
        assertEquals("SERVER_ERROR", service.classifyErrorCode(new RuntimeException("503 Service Unavailable")));
        assertEquals("CONTENT_FILTER", service.classifyErrorCode(new RuntimeException("data_inspection_failed")));
        assertEquals("UNKNOWN", service.classifyErrorCode(new RuntimeException("something odd")));
        // Unwraps nested causes.
        assertEquals("AUTH_ERROR",
                service.classifyErrorCode(new RuntimeException("wrap", new IllegalStateException("403 forbidden"))));
    }

    @Test
    @DisplayName("lazy failure persists the classified code and includes it in RAW_FAILED")
    void lazyFailure_propagatesErrorCode() {
        WikiRawMaterialEntity raw = new WikiRawMaterialEntity();
        raw.setId(RAW_ID);
        raw.setKbId(KB_ID);
        raw.setProcessingStatus("pending");
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(KB_ID);
        kb.setConfigContent("{\"ingestMode\":\"lazy\"}");

        when(rawService.claimForProcessing(RAW_ID)).thenReturn(true);
        when(rawService.getById(RAW_ID)).thenReturn(raw);
        when(rawService.getTextContent(raw)).thenReturn("Some real document text. ".repeat(20));
        when(kbService.getById(KB_ID)).thenReturn(kb);
        // Chunk persistence blows up with an auth-shaped error.
        doThrow(new RuntimeException("401 Unauthorized"))
                .when(chunkService).persistChunks(eq(KB_ID), eq(RAW_ID), any(), any());

        service.processRawMaterial(RAW_ID);

        verify(rawService).updateProcessingStatus(eq(RAW_ID), eq("failed"), eq("AUTH_ERROR"), eq("401 Unauthorized"));
        verify(progressBus).broadcast(eq(KB_ID), eq(WikiProgressBus.EVENT_RAW_FAILED),
                argThat((Map<String, Object> m) -> "AUTH_ERROR".equals(m.get("errorCode"))
                        && "401 Unauthorized".equals(m.get("error"))));
    }
}
