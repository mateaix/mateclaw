package vip.mate.memory.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.*;
import vip.mate.memory.scheduler.DreamingScheduler;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆管理接口
 * <p>
 * 提供记忆整合的手动触发和状态查询。
 *
 * @author MateClaw Team
 */
@Tag(name = "记忆管理")
@Slf4j
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryEmergenceService emergenceService;
    private final MemorySummarizationService summarizationService;
    private final MemoryRecallService recallService;
    private final MemoryProperties memoryProperties;
    private final DreamingScheduler dreamingScheduler;
    private final WorkspaceFileService workspaceFileService;

    @Operation(summary = "手动触发记忆整合（daily notes → MEMORY.md，NIGHTLY 模式）")
    @PostMapping("/{agentId}/emergence")
    @RequireWorkspaceRole("member")
    public R<DreamReport> triggerEmergence(@PathVariable Long agentId) {
        try {
            DreamReport report = emergenceService.consolidate(agentId, DreamMode.NIGHTLY, null);
            return R.ok(report);
        } catch (Exception e) {
            log.error("[Memory] Manual emergence failed for agent={}: {}", agentId, e.getMessage(), e);
            return R.fail("记忆整合失败: " + e.getMessage());
        }
    }

    @Operation(summary = "Focused Dream — 围绕指定主题触发记忆整合")
    @PostMapping("/{agentId}/dreaming/focused")
    @RequireWorkspaceRole("member")
    public R<DreamReport> triggerFocusedDream(@PathVariable Long agentId,
                                              @RequestBody Map<String, String> body) {
        if (!memoryProperties.getDream().isFocusedEnabled()) {
            return R.fail(410, "Focused dream is disabled");
        }
        String topic = body != null ? body.get("topic") : null;
        if (topic == null || topic.isBlank()) {
            return R.fail("topic is required");
        }
        try {
            DreamReport report = emergenceService.consolidate(agentId, DreamMode.FOCUSED, topic);
            return R.ok(report);
        } catch (Exception e) {
            log.error("[Memory] Focused dream failed for agent={}: {}", agentId, e.getMessage(), e);
            return R.fail("Focused dream failed: " + e.getMessage());
        }
    }

    @Operation(summary = "手动触发对话记忆提取")
    @PostMapping("/{agentId}/summarize/{conversationId}")
    @RequireWorkspaceRole("member")
    public R<Map<String, String>> triggerSummarize(
            @PathVariable Long agentId,
            @PathVariable String conversationId) {
        try {
            summarizationService.analyzeAndUpdateMemory(agentId, conversationId);
            return R.ok(Map.of("status", "completed"));
        } catch (Exception e) {
            log.error("[Memory] Manual summarization failed for agent={}, conv={}: {}",
                    agentId, conversationId, e.getMessage(), e);
            return R.fail("记忆提取失败: " + e.getMessage());
        }
    }

    // ==================== Dreaming 状态 API ====================

    @Operation(summary = "查询 Dreaming 状态（配置、统计、上次运行时间）")
    @GetMapping("/{agentId}/dreaming/status")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getDreamingStatus(@PathVariable Long agentId) {
        Map<String, Object> status = recallService.getDreamingStatus(agentId);
        status.put("lastRunTime", dreamingScheduler.getLastRunTime());
        return R.ok(status);
    }

    @Operation(summary = "查询召回候选列表（含评分详情）")
    @GetMapping("/{agentId}/dreaming/candidates")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> getDreamingCandidates(@PathVariable Long agentId) {
        return R.ok(recallService.listCandidatesWithDetails(agentId));
    }

    @Operation(summary = "查询 DREAMS.md 整合日记")
    @GetMapping("/{agentId}/dreaming/dreams")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getDreams(@PathVariable Long agentId) {
        WorkspaceFileEntity file = workspaceFileService.getFile(agentId, "DREAMS.md");
        Map<String, Object> result = new LinkedHashMap<>();
        if (file != null && file.getContent() != null) {
            result.put("content", file.getContent());
            result.put("updateTime", file.getUpdateTime());
        } else {
            result.put("content", null);
            result.put("message", "尚未生成 DREAMS.md（需先运行一次 emergence）");
        }
        return R.ok(result);
    }
}
