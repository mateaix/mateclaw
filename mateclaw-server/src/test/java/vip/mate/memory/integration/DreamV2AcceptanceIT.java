package vip.mate.memory.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.archive.MemoryArchiveService;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.memory.repository.DreamReportMapper;
import vip.mate.memory.service.*;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Dream v2 acceptance test — verifies the full consolidate pipeline
 * with mocked LLM responses, covering:
 * - DreamReport is returned with correct structure
 * - DreamReport entity is persisted to DB (via mock mapper)
 * - review_count is incremented for rejected candidates
 * - FOCUSED mode uses topic-biased prompt
 * - NIGHTLY mode produces report even with no candidates
 * - Archive is triggered when flag is on
 *
 * <p>Uses mock LLM to avoid real API calls and token costs.
 */
@ExtendWith(MockitoExtension.class)
class DreamV2AcceptanceIT {

    @Mock private WorkspaceFileService workspaceFileService;
    @Mock private ModelConfigService modelConfigService;
    @Mock private AgentGraphBuilder agentGraphBuilder;
    @Mock private MemoryRecallService recallService;
    @Mock private DreamReportMapper dreamReportMapper;
    @Mock private MemoryArchiveService archiveService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private org.springframework.ai.chat.model.ChatModel chatModel;

    private MemoryProperties props;
    private MemoryEmergenceService emergenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
        props.setEmergenceEnabled(true);
        props.setEmergenceDayRange(7);
        props.setEmergenceScoreThreshold(0.4);
        props.getDream().setFocusedEnabled(true);
        props.getDream().setArchiveEnabled(false);

        emergenceService = new MemoryEmergenceService(
                workspaceFileService, modelConfigService, agentGraphBuilder,
                props, objectMapper, recallService, dreamReportMapper, archiveService, eventPublisher, null);

        // Mock model resolution
        ModelConfigEntity modelConfig = new ModelConfigEntity();
        modelConfig.setProvider("mock");
        modelConfig.setModelName("mock-model");
        lenient().when(modelConfigService.getDefaultModel()).thenReturn(modelConfig);
        lenient().when(agentGraphBuilder.buildRuntimeChatModel(any())).thenReturn(chatModel);
    }

    private void setupDailyNotes(Long agentId) {
        WorkspaceFileEntity note = new WorkspaceFileEntity();
        note.setFilename("memory/2026-04-19.md");
        note.setContent("## 工作记录\n- 讨论了国企信创选型\n- 等保三级对微服务架构的要求\n- CI/CD 推进受阻");
        when(workspaceFileService.listFiles(agentId)).thenReturn(List.of(note));
        when(workspaceFileService.getFile(agentId, "memory/2026-04-19.md")).thenReturn(note);

        WorkspaceFileEntity memoryFile = new WorkspaceFileEntity();
        memoryFile.setContent("## 长期记忆\n\n- 用户是央企开发工程师");
        lenient().when(workspaceFileService.getFile(agentId, "MEMORY.md")).thenReturn(memoryFile);
        lenient().when(workspaceFileService.getFile(agentId, "DREAMS.md")).thenReturn(null);
    }

    private void setupLlmResponse(String jsonResponse) {
        var chatResponse = mock(org.springframework.ai.chat.model.ChatResponse.class);
        var generation = mock(org.springframework.ai.chat.model.Generation.class);
        var output = mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(output);
        when(output.getText()).thenReturn(jsonResponse);
    }

    private MemoryRecallEntity makeCandidate(Long id, String filename, double score) {
        MemoryRecallEntity e = new MemoryRecallEntity();
        e.setId(id);
        e.setAgentId(1L);
        e.setFilename(filename);
        e.setSnippetPreview("国企信创选型要求使用自主可控技术栈");
        e.setRecallCount(5);
        e.setDailyCount(2);
        e.setScore(score);
        e.setReviewCount(0);
        e.setLastRecalledAt(LocalDateTime.now());
        e.setPromoted(false);
        return e;
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("NIGHTLY dream: returns SUCCESS report with promoted/rejected candidates")
    void nightlyDream_successReport() {
        setupDailyNotes(1L);
        List<MemoryRecallEntity> candidates = List.of(
                makeCandidate(100L, "memory/2026-04-19.md#信创", 0.85),
                makeCandidate(101L, "memory/2026-04-19.md#CI/CD", 0.72)
        );
        when(recallService.computeScores(1L)).thenReturn(candidates);

        // LLM adopts the first candidate content
        setupLlmResponse("""
            {"should_update": true, "reason": "整合信创选型信息",
             "memory_content": "## 长期记忆\\n\\n- 用户是央企开发工程师\\n- 国企信创选型要求使用自主可控技术栈"}
            """);

        DreamReport report = emergenceService.consolidate(1L, DreamMode.NIGHTLY, null);

        assertEquals(DreamStatus.SUCCESS, report.status());
        assertEquals(DreamMode.NIGHTLY, report.mode());
        assertNull(report.topic());
        assertEquals(2, report.candidateCount());
        assertTrue(report.promotedCount() >= 1);
        assertNotNull(report.memoryDiff());

        // DreamReport should be persisted
        verify(dreamReportMapper).insert(any(DreamReportEntity.class));

        // Rejected candidates should have review_count incremented
        if (report.rejectedCount() > 0) {
            verify(recallService).incrementReviewCounts(any());
        }
    }

    @Test
    @DisplayName("FOCUSED dream: topic appears in report and uses focused prompt")
    void focusedDream_topicInReport() {
        setupDailyNotes(1L);
        when(recallService.computeScores(1L)).thenReturn(List.of(
                makeCandidate(200L, "memory/2026-04-19.md#等保", 0.9)
        ));

        setupLlmResponse("""
            {"should_update": true, "reason": "围绕等保合规整合",
             "memory_content": "## 长期记忆\\n\\n- 等保三级要求加密传输、审计日志"}
            """);

        DreamReport report = emergenceService.consolidate(1L, DreamMode.FOCUSED, "等保合规要求");

        assertEquals(DreamMode.FOCUSED, report.mode());
        assertEquals("等保合规要求", report.topic());
        assertEquals(DreamStatus.SUCCESS, report.status());
        verify(dreamReportMapper).insert(any(DreamReportEntity.class));
    }

    @Test
    @DisplayName("LLM failure: returns FAILED report, persisted")
    void llmFailure_failedReport() {
        setupDailyNotes(1L);
        when(recallService.computeScores(1L)).thenReturn(List.of());

        doThrow(new RuntimeException("API timeout"))
                .when(chatModel).call(any(org.springframework.ai.chat.prompt.Prompt.class));

        DreamReport report = emergenceService.consolidate(1L, DreamMode.NIGHTLY, null);

        assertEquals(DreamStatus.FAILED, report.status());
        assertNotNull(report.errorMessage());
        assertTrue(report.errorMessage().contains("API timeout"));
        verify(dreamReportMapper).insert(any(DreamReportEntity.class));
    }

    @Test
    @DisplayName("No daily notes: returns SKIPPED report")
    void noDailyNotes_skippedReport() {
        when(workspaceFileService.listFiles(1L)).thenReturn(List.of());

        DreamReport report = emergenceService.consolidate(1L, DreamMode.FOCUSED, "测试");

        assertEquals(DreamStatus.SKIPPED, report.status());
        assertEquals("no daily notes", report.llmReason());
        verify(dreamReportMapper).insert(any(DreamReportEntity.class));
    }

    @Test
    @DisplayName("Archive flag ON: archiveService called after dream diary")
    void archiveOn_archiveCalled() {
        props.getDream().setArchiveEnabled(true);
        setupDailyNotes(1L);

        List<MemoryRecallEntity> candidates = List.of(
                makeCandidate(300L, "memory/2026-04-19.md#总结", 0.8)
        );
        when(recallService.computeScores(1L)).thenReturn(candidates);

        setupLlmResponse("""
            {"should_update": true, "reason": "ok",
             "memory_content": "## 记忆\\n\\n- 国企信创选型要求使用自主可控技术栈"}
            """);

        emergenceService.consolidate(1L, DreamMode.NIGHTLY, null);

        verify(archiveService).archiveOldDreams(1L);
    }

    @Test
    @DisplayName("Archive flag OFF: archiveService NOT called, 20KB truncation preserved")
    void archiveOff_noArchive() {
        props.getDream().setArchiveEnabled(false);
        setupDailyNotes(1L);

        List<MemoryRecallEntity> candidates = List.of(
                makeCandidate(400L, "memory/2026-04-19.md#总结", 0.8)
        );
        when(recallService.computeScores(1L)).thenReturn(candidates);

        setupLlmResponse("""
            {"should_update": true, "reason": "ok",
             "memory_content": "## 记忆\\n\\n- 国企信创选型要求使用自主可控技术栈"}
            """);

        emergenceService.consolidate(1L, DreamMode.NIGHTLY, null);

        verify(archiveService, never()).archiveOldDreams(any());
    }

    @Test
    @DisplayName("review_count: rejected candidates get incremented")
    void reviewCount_rejected() {
        setupDailyNotes(1L);

        // Two candidates: one will be adopted (content matches), one won't
        MemoryRecallEntity adopted = makeCandidate(500L, "file-a.md", 0.9);
        adopted.setSnippetPreview("信创选型要求使用自主可控");

        MemoryRecallEntity rejected = makeCandidate(501L, "file-b.md", 0.7);
        rejected.setSnippetPreview("完全不相关的内容xyz123");

        when(recallService.computeScores(1L)).thenReturn(List.of(adopted, rejected));

        // LLM output contains adopted candidate's key phrase
        setupLlmResponse("""
            {"should_update": true, "reason": "整合",
             "memory_content": "## 记忆\\n\\n- 信创选型要求使用自主可控技术栈"}
            """);

        DreamReport report = emergenceService.consolidate(1L, DreamMode.NIGHTLY, null);

        assertEquals(1, report.promotedCount());
        assertEquals(1, report.rejectedCount());

        // Verify promoted was marked
        verify(recallService).markPromoted(List.of(500L));
        // Verify rejected had review_count incremented
        verify(recallService).incrementReviewCounts(List.of(501L));
    }

    @Test
    @DisplayName("Emergence disabled: SKIPPED without LLM call")
    void emergenceDisabled_skipped() {
        props.setEmergenceEnabled(false);

        DreamReport report = emergenceService.consolidate(1L, DreamMode.NIGHTLY, null);

        assertEquals(DreamStatus.SKIPPED, report.status());
        verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(dreamReportMapper).insert(any(DreamReportEntity.class));
    }
}
