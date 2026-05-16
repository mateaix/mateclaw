package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.sse.WikiProgressBus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RFC-051 PR-1b: lazy ingest short-circuits the heavy pipeline.
 * <p>
 * These tests pin the lazy branch's contract so later PRs (1c preprocessor,
 * structured output, etc.) can't accidentally re-enable LLM calls for a
 * lazy-configured KB.
 */
class WikiProcessingServiceLazyTest {

    private WikiKnowledgeBaseService kbService;
    private WikiRawMaterialService rawService;
    private WikiPageService pageService;
    private WikiChunkService chunkService;
    private WikiEmbeddingService embeddingService;
    private WikiProperties properties;
    private ModelConfigService modelConfigService;
    private AgentGraphBuilder agentGraphBuilder;
    private WikiProgressBus progressBus;
    private WikiCitationService citationService;

    private WikiProcessingService service;

    private static final Long KB_ID = 42L;
    private static final Long RAW_ID = 4479907L;

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        rawService = mock(WikiRawMaterialService.class);
        pageService = mock(WikiPageService.class);
        chunkService = mock(WikiChunkService.class);
        embeddingService = mock(WikiEmbeddingService.class);
        properties = new WikiProperties();
        modelConfigService = mock(ModelConfigService.class);
        agentGraphBuilder = mock(AgentGraphBuilder.class);
        progressBus = mock(WikiProgressBus.class);
        citationService = mock(WikiCitationService.class);

        service = new WikiProcessingService(
                kbService, rawService, pageService, chunkService, embeddingService,
                properties, modelConfigService, agentGraphBuilder, new ObjectMapper(),
                progressBus, citationService,
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private WikiRawMaterialEntity raw() {
        WikiRawMaterialEntity r = new WikiRawMaterialEntity();
        r.setId(RAW_ID);
        r.setKbId(KB_ID);
        r.setContentHash("abc123");
        r.setProcessingStatus("pending");
        return r;
    }

    private WikiKnowledgeBaseEntity kb(String ingestMode) {
        WikiKnowledgeBaseEntity k = new WikiKnowledgeBaseEntity();
        k.setId(KB_ID);
        if (ingestMode != null) {
            k.setConfigContent("{\"ingestMode\":\"" + ingestMode + "\"}");
        }
        return k;
    }

    private WikiKnowledgeBaseEntity markdownKbWithFrontmatter(String ingestMode) {
        WikiKnowledgeBaseEntity k = new WikiKnowledgeBaseEntity();
        k.setId(KB_ID);
        k.setConfigContent("""
                ---
                ingestMode: %s
                ---
                # Wiki Processing Rules

                Keep pages concise.
                """.formatted(ingestMode));
        return k;
    }

    @Test
    @DisplayName("lazy mode: skips LLM pipeline, persists chunks, marks completed with 0 pages")
    void lazyMode_noLlmCalls() throws InterruptedException {
        WikiRawMaterialEntity rawEntity = raw();
        WikiKnowledgeBaseEntity kbEntity = kb("lazy");

        when(rawService.claimForProcessing(RAW_ID)).thenReturn(true);
        when(rawService.getById(RAW_ID)).thenReturn(rawEntity);
        when(rawService.getTextContent(rawEntity)).thenReturn("Some document text for lazy ingest. ".repeat(20));
        when(kbService.getById(KB_ID)).thenReturn(kbEntity);
        when(pageService.countByKbId(KB_ID)).thenReturn(0);

        // Latch so we can deterministically wait for the async embedding submission.
        CountDownLatch embedCalled = new CountDownLatch(1);
        when(embeddingService.embedMissingChunks(KB_ID)).thenAnswer(inv -> {
            embedCalled.countDown();
            return 0;
        });

        service.processRawMaterial(RAW_ID);

        // Chunk persistence happened via the legacy (no-metadata) overload.
        verify(chunkService, times(1)).persistChunks(eq(KB_ID), eq(RAW_ID), anyList(), anyList());
        // No LLM-backed APIs touched.
        verify(pageService, never()).deleteExclusiveBySourceRawId(anyLong(), anyLong());

        // Async embedding fired.
        assertTrue(embedCalled.await(5, TimeUnit.SECONDS), "embedMissingChunks should have been invoked");

        // Raw marked completed with lastProcessedHash recorded.
        verify(rawService).updateProcessingStatus(eq(RAW_ID), eq("completed"), eq(null));
        verify(rawService).setLastProcessedHash(eq(RAW_ID), eq("abc123"));

        // Terminal progress = done.
        ArgumentCaptor<String> phaseCaptor = ArgumentCaptor.forClass(String.class);
        verify(rawService, atLeastOnce()).updateProgress(eq(RAW_ID), phaseCaptor.capture(), anyInt(), anyInt());
        assertTrue(phaseCaptor.getAllValues().contains("done"),
                "progress phase must eventually be 'done'. Got: " + phaseCaptor.getAllValues());

        // RAW_COMPLETED broadcast with totalPages=0.
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(progressBus).broadcast(eq(KB_ID), eq(WikiProgressBus.EVENT_RAW_COMPLETED), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("completed", payload.get("status"));
        assertEquals(0, payload.get("totalPages"));

        // KB back to active.
        verify(kbService).updateStatus(KB_ID, "active");
    }

    @Test
    @DisplayName("lazy mode from markdown frontmatter: skips LLM pipeline")
    void markdownFrontmatterLazyMode_noLlmCalls() throws InterruptedException {
        WikiRawMaterialEntity rawEntity = raw();
        WikiKnowledgeBaseEntity kbEntity = markdownKbWithFrontmatter("lazy");

        when(rawService.claimForProcessing(RAW_ID)).thenReturn(true);
        when(rawService.getById(RAW_ID)).thenReturn(rawEntity);
        when(rawService.getTextContent(rawEntity)).thenReturn("Some document text for lazy ingest. ".repeat(20));
        when(kbService.getById(KB_ID)).thenReturn(kbEntity);
        when(pageService.countByKbId(KB_ID)).thenReturn(0);

        CountDownLatch embedCalled = new CountDownLatch(1);
        when(embeddingService.embedMissingChunks(KB_ID)).thenAnswer(inv -> {
            embedCalled.countDown();
            return 0;
        });

        service.processRawMaterial(RAW_ID);

        verify(chunkService, times(1)).persistChunks(eq(KB_ID), eq(RAW_ID), anyList(), anyList());
        verify(pageService, never()).deleteExclusiveBySourceRawId(anyLong(), anyLong());
        assertTrue(embedCalled.await(5, TimeUnit.SECONDS), "embedMissingChunks should have been invoked");
        verify(progressBus).broadcast(eq(KB_ID),
                eq(WikiProgressBus.EVENT_RAW_STARTED),
                argThat((Map<String, Object> m) -> "lazy".equals(m.get("phase"))));
    }

    @Test
    @DisplayName("lazy mode with blank text: marks failed, no chunks persisted")
    void lazyMode_blankText_failsCleanly() {
        WikiRawMaterialEntity rawEntity = raw();
        WikiKnowledgeBaseEntity kbEntity = kb("lazy");

        when(rawService.claimForProcessing(RAW_ID)).thenReturn(true);
        when(rawService.getById(RAW_ID)).thenReturn(rawEntity);
        when(rawService.getTextContent(rawEntity)).thenReturn("");
        when(kbService.getById(KB_ID)).thenReturn(kbEntity);

        service.processRawMaterial(RAW_ID);

        verify(chunkService, never()).persistChunks(anyLong(), anyLong(), anyList(), anyList());
        verify(rawService).updateProcessingStatus(eq(RAW_ID), eq("failed"), eq("No text content available"));
        verify(progressBus).broadcast(eq(KB_ID), eq(WikiProgressBus.EVENT_RAW_FAILED), any());
    }

    @Test
    @DisplayName("lazy mode: aborts cleanly when raw is deleted between extract and persist")
    void lazyMode_rawDeletedDuringExtraction() {
        WikiRawMaterialEntity rawEntity = raw();
        WikiKnowledgeBaseEntity kbEntity = kb("lazy");

        when(rawService.claimForProcessing(RAW_ID)).thenReturn(true);
        // First call (top-of-method): row still there.
        // Second call (post-extract abort check inside processLazyIngest): row gone (user deleted).
        when(rawService.getById(RAW_ID)).thenReturn(rawEntity, (WikiRawMaterialEntity) null);
        when(rawService.getTextContent(rawEntity)).thenReturn("Some text.");
        when(kbService.getById(KB_ID)).thenReturn(kbEntity);

        service.processRawMaterial(RAW_ID);

        // Critical: the LLM-touching boundaries (chunk persist + embed schedule) never ran.
        verify(chunkService, never()).persistChunks(anyLong(), anyLong(), anyList(), anyList());
        verify(embeddingService, never()).embedMissingChunks(anyLong());
        // KB returned to active so it doesn't sit in "processing" forever.
        verify(kbService).updateStatus(KB_ID, "active");
    }

    @Test
    @DisplayName("ingestMode=null: falls through to eager branch (heavy pipeline entered)")
    void nullMode_doesNotShortCircuit() {
        WikiRawMaterialEntity rawEntity = raw();
        // No configContent means ingestMode is null → must NOT take lazy branch.
        WikiKnowledgeBaseEntity kbEntity = kb(null);

        when(rawService.claimForProcessing(RAW_ID)).thenReturn(true);
        when(rawService.getById(RAW_ID)).thenReturn(rawEntity);
        when(kbService.getById(KB_ID)).thenReturn(kbEntity);
        // Force eager to bail fast without real LLM calls.
        when(rawService.getTextContent(rawEntity)).thenReturn(null);

        service.processRawMaterial(RAW_ID);

        // Eager's blank-text path does NOT hit setLastProcessedHash. Lazy path would either
        // succeed or call the failed broadcast with different payload — so the key tell for
        // "did we enter the lazy helper?" is the RAW_STARTED phase=lazy broadcast. Verify it
        // was NOT called, which proves we fell through to the eager branch.
        verify(progressBus, never()).broadcast(eq(KB_ID),
                eq(WikiProgressBus.EVENT_RAW_STARTED),
                argThat((Map<String, Object> m) -> "lazy".equals(m.get("phase"))));
    }

    // --- typed Mockito helpers for List<T> args (keeps generic-arg verify calls clean) ---
    @SuppressWarnings("unchecked")
    private static <T> List<T> anyList() {
        return any(List.class);
    }
    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
