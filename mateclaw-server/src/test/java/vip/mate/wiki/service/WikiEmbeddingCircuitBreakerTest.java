package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import vip.mate.system.repository.SystemSettingMapper;
import vip.mate.llm.embedding.EmbeddingModelFactory;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.job.WikiEmbeddingProviderFailingException;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the embedding circuit-breaker: a misconfigured / unavailable
 * provider trips the circuit after N consecutive failed batches instead
 * of churning silently through every pending chunk in the KB.
 */
class WikiEmbeddingCircuitBreakerTest {

    private static final Long KB_ID = 7L;

    private WikiChunkMapper chunkMapper;
    private WikiProperties properties;
    private WikiEmbeddingService service;
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(WikiChunkMapper.class);
        properties = new WikiProperties();
        properties.setEmbeddingBatchSize(1); // one chunk per batch keeps the math obvious
        properties.setEmbeddingMaxChars(6000);

        EmbeddingModelFactory factory = mock(EmbeddingModelFactory.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
        SystemSettingMapper systemSettingMapper = mock(SystemSettingMapper.class);
        ModelProviderService modelProviderService = mock(ModelProviderService.class);
        embeddingModel = mock(EmbeddingModel.class);

        WikiRawMaterialMapper rawMaterialMapper = mock(WikiRawMaterialMapper.class);
        WikiPageMapper pageMapper = mock(WikiPageMapper.class);
        WikiEmbeddingInputBuilder inputBuilder = new WikiEmbeddingInputBuilder();
        service = spy(new WikiEmbeddingService(chunkMapper, pageMapper, rawMaterialMapper, properties, factory,
                modelConfigService, kbService, systemSettingMapper, modelProviderService, inputBuilder));
        // Bypass model resolution; the test only cares about the loop's behaviour
        // when the model itself misbehaves.
        doReturn(new WikiEmbeddingService.Resolved(embeddingModel, "test-model"))
                .when(service).resolveForKb(KB_ID);
    }

    private List<WikiChunkEntity> shortChunks(int n) {
        List<WikiChunkEntity> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            WikiChunkEntity c = new WikiChunkEntity();
            c.setId((long) (1000 + i));
            c.setKbId(KB_ID);
            c.setContent("chunk " + i);
            out.add(c);
        }
        return out;
    }

    @Test
    @DisplayName("Circuit trips after threshold consecutive batch failures and aborts the pass")
    void circuit_trips_on_consistent_failures() {
        properties.setEmbeddingConsecutiveFailureThreshold(3);
        when(chunkMapper.selectList(any())).thenReturn(shortChunks(20));

        // Every batch call throws → the embedShortBatch helper logs and returns 0
        // for every batch, driving the consecutive-failure counter past threshold.
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("401 Unauthorized"));

        WikiEmbeddingProviderFailingException ex = assertThrows(
                WikiEmbeddingProviderFailingException.class,
                () -> service.embedMissingChunks(KB_ID));

        assertEquals(3, ex.getConsecutiveFailures());
        assertTrue(ex.getRemainingChunks() > 0,
                "remaining chunks should reflect what we skipped");
        assertTrue(ex.getMessage().contains("test-model"));
        // Crucially: only 3 batches were attempted, not all 20 — the circuit aborted.
        verify(embeddingModel, times(3)).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("Successful batches reset the counter; one healthy call clears prior failures")
    void counter_resets_on_success() {
        properties.setEmbeddingConsecutiveFailureThreshold(3);
        when(chunkMapper.selectList(any())).thenReturn(shortChunks(6));

        AtomicInteger callCount = new AtomicInteger(0);
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenAnswer(inv -> {
                    int call = callCount.incrementAndGet();
                    // Pattern: fail, fail, succeed, fail, fail, fail
                    // After the success the counter resets, so the next two failures
                    // alone should not trip the circuit. The third post-success
                    // failure (= 3 consecutive) does.
                    if (call == 3) {
                        return successResponse(1);
                    }
                    throw new RuntimeException("rate limited");
                });

        WikiEmbeddingProviderFailingException ex = assertThrows(
                WikiEmbeddingProviderFailingException.class,
                () -> service.embedMissingChunks(KB_ID));

        assertEquals(3, ex.getConsecutiveFailures());
        // Calls: fail, fail, succeed, fail, fail, fail (6 calls total)
        verify(embeddingModel, times(6)).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("All batches succeed — pass completes normally without circuit interference")
    void healthy_pass_does_not_trip() {
        properties.setEmbeddingConsecutiveFailureThreshold(3);
        when(chunkMapper.selectList(any())).thenReturn(shortChunks(5));
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(successResponse(1));

        int embedded = service.embedMissingChunks(KB_ID);

        assertEquals(5, embedded);
        verify(embeddingModel, times(5)).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("Threshold of 1 trips on the very first failure")
    void threshold_one_trips_immediately() {
        properties.setEmbeddingConsecutiveFailureThreshold(1);
        when(chunkMapper.selectList(any())).thenReturn(shortChunks(50));
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("401"));

        assertThrows(WikiEmbeddingProviderFailingException.class,
                () -> service.embedMissingChunks(KB_ID));

        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("Failures below threshold do not throw — pass completes with partial results")
    void below_threshold_does_not_throw() {
        properties.setEmbeddingConsecutiveFailureThreshold(5);
        when(chunkMapper.selectList(any())).thenReturn(shortChunks(4));
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("rate limited"));

        // 4 consecutive failures < threshold of 5 → loop completes normally,
        // returning 0 embedded but without throwing the circuit exception.
        int embedded = service.embedMissingChunks(KB_ID);
        assertEquals(0, embedded);
    }

    private static EmbeddingResponse successResponse(int items) {
        List<Embedding> results = new ArrayList<>(items);
        for (int i = 0; i < items; i++) {
            results.add(new Embedding(new float[]{0.1f, 0.2f, 0.3f}, i));
        }
        return new EmbeddingResponse(results);
    }
}
