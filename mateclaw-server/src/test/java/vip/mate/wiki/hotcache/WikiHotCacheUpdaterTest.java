package vip.mate.wiki.hotcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.wiki.job.WikiModelRoutingService;
import vip.mate.wiki.metrics.WikiMetrics;
import vip.mate.wiki.model.WikiHotCacheEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiHotCacheMapper;
import vip.mate.wiki.service.WikiPageService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiHotCacheUpdaterTest {

    private WikiHotCacheService cacheService;
    private WikiHotCacheMapper mapper;
    private HotCacheRebuildPromptBuilder promptBuilder;
    private HotCacheProperties props;
    private WikiModelRoutingService modelRoutingService;
    private ModelConfigService modelConfigService;
    private AgentGraphBuilder agentGraphBuilder;
    private FeatureFlagService featureFlagService;
    private WikiMetrics metrics;
    private WikiPageService pageService;
    private ChatModel chatModel;

    private WikiHotCacheUpdater updater;

    @BeforeEach
    void setUp() {
        cacheService = mock(WikiHotCacheService.class);
        mapper = mock(WikiHotCacheMapper.class);
        promptBuilder = mock(HotCacheRebuildPromptBuilder.class);
        props = new HotCacheProperties();
        modelRoutingService = mock(WikiModelRoutingService.class);
        modelConfigService = mock(ModelConfigService.class);
        agentGraphBuilder = mock(AgentGraphBuilder.class);
        featureFlagService = mock(FeatureFlagService.class);
        metrics = mock(WikiMetrics.class);
        pageService = mock(WikiPageService.class);
        chatModel = mock(ChatModel.class);

        // Default: flag on, model resolution OK, prompts return stable strings
        when(featureFlagService.isEnabledForKb(eq("wiki.hot_cache.enabled"), anyLong())).thenReturn(true);
        when(modelRoutingService.selectModelId(anyLong(), anyString(), any())).thenReturn(42L);
        ModelConfigEntity modelCfg = new ModelConfigEntity();
        modelCfg.setId(42L);
        when(modelConfigService.getModel(42L)).thenReturn(modelCfg);
        when(agentGraphBuilder.buildRuntimeChatModel(any(), any())).thenReturn(chatModel);
        when(promptBuilder.buildSystem()).thenReturn("system-prompt");
        when(promptBuilder.buildUser(any(), any(), any(), any())).thenReturn("user-prompt");

        updater = new WikiHotCacheUpdater(cacheService, mapper, promptBuilder, props,
                modelRoutingService, modelConfigService, agentGraphBuilder, featureFlagService,
                metrics, pageService);
    }

    private static WikiPageEntity page(Long id) {
        WikiPageEntity p = new WikiPageEntity();
        p.setId(id);
        p.setSlug("p" + id);
        p.setTitle("Page " + id);
        return p;
    }

    private void stubLlm(String body) {
        Generation g = new Generation(new AssistantMessage(body));
        ChatResponse resp = new ChatResponse(List.of(g));
        when(chatModel.call(any(Prompt.class))).thenReturn(resp);
    }

    private void stubRecentActivity() {
        when(pageService.findRecentCreated(anyLong(), any(), anyInt())).thenReturn(List.of(page(1L)));
        when(pageService.findRecentUpdated(anyLong(), any(), anyInt())).thenReturn(List.of(page(2L)));
        when(pageService.getBySlug(anyLong(), anyString())).thenReturn(null);
    }

    @Test
    @DisplayName("flag off → returns silently, no LLM call, no DB write")
    void flagOff() {
        when(featureFlagService.isEnabledForKb(eq("wiki.hot_cache.enabled"), anyLong())).thenReturn(false);
        updater.rebuild(7L, HotCacheUpdateReason.MANUAL);

        verify(chatModel, never()).call(any(Prompt.class));
        verify(mapper, never()).insert(any(WikiHotCacheEntity.class));
        verify(mapper, never()).updateById(any(WikiHotCacheEntity.class));
    }

    @Test
    @DisplayName("no recent activity → skip rebuild, clear started_at marker if present")
    void noRecentActivity_skips() {
        when(pageService.findRecentCreated(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(pageService.findRecentUpdated(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(pageService.getBySlug(anyLong(), anyString())).thenReturn(null);

        WikiHotCacheEntity existing = new WikiHotCacheEntity();
        existing.setId(99L);
        existing.setKbId(7L);
        existing.setLastRebuildStartedAt(java.time.LocalDateTime.now());
        when(cacheService.findByKb(7L)).thenReturn(Optional.of(existing));

        updater.rebuild(7L, HotCacheUpdateReason.MANUAL);

        verify(chatModel, never()).call(any(Prompt.class));
        // started_at cleared via updateById on the same row
        ArgumentCaptor<WikiHotCacheEntity> captor = ArgumentCaptor.forClass(WikiHotCacheEntity.class);
        verify(mapper, times(2)).updateById(captor.capture());
        // First call (markRebuildStarted) sets started_at; second (clearRebuildMarker) nulls it.
        assertThat(captor.getAllValues().get(1).getLastRebuildStartedAt()).isNull();
    }

    @Test
    @DisplayName("no chat model resolvable → records error, no LLM call, no body write")
    void noChatModel() {
        stubRecentActivity();
        when(modelConfigService.getModel(anyLong())).thenReturn(null);
        when(cacheService.findByKb(7L)).thenReturn(Optional.empty());

        updater.rebuild(7L, HotCacheUpdateReason.MANUAL);

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("happy path: LLM returns body → row inserted with content, hash, reason")
    void happyPath_insert() {
        stubRecentActivity();
        stubLlm("## Last Updated\nfresh snapshot");
        when(cacheService.findByKb(7L)).thenReturn(Optional.empty());

        updater.rebuild(7L, HotCacheUpdateReason.MANUAL);

        ArgumentCaptor<WikiHotCacheEntity> captor = ArgumentCaptor.forClass(WikiHotCacheEntity.class);
        verify(mapper).insert(captor.capture());
        WikiHotCacheEntity inserted = captor.getValue();
        assertThat(inserted.getKbId()).isEqualTo(7L);
        assertThat(inserted.getContent()).contains("fresh snapshot");
        assertThat(inserted.getContentHash()).hasSize(64);
        assertThat(inserted.getUpdateReason()).isEqualTo("MANUAL");
        assertThat(inserted.getRebuildCount()).isEqualTo(1L);
        assertThat(inserted.getLastRebuildError()).isNull();
        verify(metrics).recordCompileStage(eq("hot-cache-rebuild"), eq(7L), any());
    }

    @Test
    @DisplayName("body unchanged: row updated but content/hash/count unchanged, reason refreshed")
    void unchangedBody_skipsContentWrite() {
        stubRecentActivity();
        stubLlm("## Last Updated\nidentical body");

        // Pre-existing row with the same hash as we'd compute
        WikiHotCacheEntity existing = new WikiHotCacheEntity();
        existing.setId(99L);
        existing.setKbId(7L);
        existing.setContent("## Last Updated\nidentical body");
        existing.setContentHash(sha256("## Last Updated\nidentical body"));
        existing.setRebuildCount(5L);
        // findByKb is called multiple times in the path; same Optional value works
        when(cacheService.findByKb(7L)).thenReturn(Optional.of(existing));

        updater.rebuild(7L, HotCacheUpdateReason.COMPILE_DONE);

        // The final updateById in persistRebuild leaves content + hash + count untouched
        ArgumentCaptor<WikiHotCacheEntity> captor = ArgumentCaptor.forClass(WikiHotCacheEntity.class);
        verify(mapper, times(2)).updateById(captor.capture());
        WikiHotCacheEntity finalState = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(finalState.getRebuildCount()).isEqualTo(5L);
        assertThat(finalState.getContent()).isEqualTo("## Last Updated\nidentical body");
        assertThat(finalState.getUpdateReason()).isEqualTo("COMPILE_DONE");
    }

    @Test
    @DisplayName("LLM returns blank body → recorded as failure, no body write")
    void blankResponse_recordsFailure() {
        stubRecentActivity();
        stubLlm("   ");
        WikiHotCacheEntity existing = new WikiHotCacheEntity();
        existing.setId(99L);
        existing.setKbId(7L);
        when(cacheService.findByKb(7L)).thenReturn(Optional.of(existing));

        updater.rebuild(7L, HotCacheUpdateReason.MANUAL);

        ArgumentCaptor<WikiHotCacheEntity> captor = ArgumentCaptor.forClass(WikiHotCacheEntity.class);
        verify(mapper, times(2)).updateById(captor.capture());
        WikiHotCacheEntity finalState = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(finalState.getLastRebuildError()).isEqualTo("LLM returned empty body");
    }

    @Test
    @DisplayName("LLM call throws → recorded as failure, exception swallowed")
    void llmException_swallowed() {
        stubRecentActivity();
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("model timeout"));
        WikiHotCacheEntity existing = new WikiHotCacheEntity();
        existing.setId(99L);
        existing.setKbId(7L);
        when(cacheService.findByKb(7L)).thenReturn(Optional.of(existing));

        // Must NOT throw
        updater.rebuild(7L, HotCacheUpdateReason.MANUAL);

        ArgumentCaptor<WikiHotCacheEntity> captor = ArgumentCaptor.forClass(WikiHotCacheEntity.class);
        verify(mapper, times(2)).updateById(captor.capture());
        WikiHotCacheEntity finalState = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(finalState.getLastRebuildError()).contains("model timeout");
    }

    @Test
    @DisplayName("oversize LLM body is truncated to maxChars")
    void truncatesOversizeBody() {
        stubRecentActivity();
        String huge = "z".repeat(props.getMaxChars() + 500);
        stubLlm(huge);
        when(cacheService.findByKb(7L)).thenReturn(Optional.empty());

        updater.rebuild(7L, HotCacheUpdateReason.MANUAL);

        ArgumentCaptor<WikiHotCacheEntity> captor = ArgumentCaptor.forClass(WikiHotCacheEntity.class);
        verify(mapper).insert(captor.capture());
        WikiHotCacheEntity row = captor.getValue();
        assertThat(row.getContent()).hasSize(props.getMaxChars());
        assertThat(row.getContent()).endsWith("…");
    }

    @Test
    @DisplayName("null kbId is a no-op")
    void nullKbId() {
        updater.rebuild(null, HotCacheUpdateReason.MANUAL);
        verify(featureFlagService, never()).isEnabledForKb(anyString(), anyLong());
    }

    private static String sha256(String s) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "no-hash";
        }
    }
}
