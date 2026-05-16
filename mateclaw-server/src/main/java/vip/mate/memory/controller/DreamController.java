package vip.mate.memory.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.memory.repository.DreamReportMapper;
import vip.mate.memory.repository.MemoryRecallMapper;
import vip.mate.memory.service.MorningCardService;
import vip.mate.memory.service.MemoryHilService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dream report API — provides paginated access to dream history for the Memory Timeline UI.
 *
 * @author MateClaw Team
 */
@Tag(name = "Dream Reports")
@RestController
@RequestMapping("/api/v1/memory/{agentId}/dream")
@RequiredArgsConstructor
public class DreamController {

    private final DreamReportMapper dreamReportMapper;
    private final MemoryRecallMapper recallMapper;
    private final MorningCardService morningCardService;
    private final MemoryHilService hilService;
    private final DreamEventBroadcaster eventBroadcaster;

    @Operation(summary = "List dream reports (paginated, newest first)")
    @GetMapping("/reports")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> listReports(
            @PathVariable Long agentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DreamReportEntity> pageParam = new Page<>(page, size);
        Page<DreamReportEntity> result = dreamReportMapper.selectPage(pageParam,
                new LambdaQueryWrapper<DreamReportEntity>()
                        .eq(DreamReportEntity::getAgentId, agentId)
                        .eq(DreamReportEntity::getDeleted, 0)
                        .orderByDesc(DreamReportEntity::getStartedAt));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("size", result.getSize());
        return R.ok(data);
    }

    @Operation(summary = "Get a single dream report by ID")
    @GetMapping("/reports/{reportId}")
    @RequireWorkspaceRole("member")
    public R<DreamReportEntity> getReport(
            @PathVariable Long agentId,
            @PathVariable Long reportId) {
        DreamReportEntity entity = dreamReportMapper.selectOne(
                new LambdaQueryWrapper<DreamReportEntity>()
                        .eq(DreamReportEntity::getId, reportId)
                        .eq(DreamReportEntity::getAgentId, agentId)
                        .eq(DreamReportEntity::getDeleted, 0));
        if (entity == null) {
            return R.fail("Report not found");
        }
        return R.ok(entity);
    }

    // ==================== SSE Events ====================

    @Operation(summary = "Subscribe to dream events (SSE)")
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequireWorkspaceRole("member")
    public SseEmitter subscribeDreamEvents(@PathVariable Long agentId) {
        return eventBroadcaster.register(agentId);
    }

    // ==================== Morning Card ====================

    @Operation(summary = "Get morning card for current user + agent")
    @GetMapping("/morning-card")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getMorningCard(@PathVariable Long agentId, Authentication auth) {
        Long userId = resolveUserId(auth);
        if (userId == null) return R.fail("Not authenticated");
        Map<String, Object> card = morningCardService.getCardFor(userId, agentId);
        return R.ok(card); // null = no card to show
    }

    @Operation(summary = "Mark morning card as seen")
    @PostMapping("/morning-card/seen")
    @RequireWorkspaceRole("member")
    public R<Void> markMorningCardSeen(@PathVariable Long agentId,
                                        @RequestBody Map<String, Object> body,
                                        Authentication auth) {
        Long userId = resolveUserId(auth);
        if (userId == null) return R.fail("Not authenticated");
        Long reportId = body.get("reportId") != null
                ? Long.valueOf(body.get("reportId").toString()) : null;
        morningCardService.markSeen(userId, agentId, reportId);
        return R.ok(null);
    }

    // ==================== HiL (Human-in-the-Loop) ====================

    @Operation(summary = "Confirm a memory entry (no-op acknowledgment)")
    @PostMapping("/reports/{reportId}/entries/{key}/confirm")
    @RequireWorkspaceRole("member")
    public R<Void> confirmEntry(@PathVariable Long agentId,
                                 @PathVariable Long reportId,
                                 @PathVariable String key) {
        // Confirm is a no-op in Phase 2 — just logs the action
        return R.ok(null);
    }

    @Operation(summary = "Edit a memory entry — writes back to MEMORY.md with user-edited metadata")
    @PostMapping("/reports/{reportId}/entries/{key}/edit")
    @RequireWorkspaceRole("member")
    public R<Void> editEntry(@PathVariable Long agentId,
                              @PathVariable Long reportId,
                              @PathVariable String key,
                              @RequestBody Map<String, String> body) {
        String decodedKey = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);

        if (reportId != 0L) {
            // Report-scoped edit: validate report belongs to agent AND key belongs to report
            DreamReportEntity report = dreamReportMapper.selectOne(
                    new LambdaQueryWrapper<DreamReportEntity>()
                            .eq(DreamReportEntity::getId, reportId)
                            .eq(DreamReportEntity::getAgentId, agentId)
                            .eq(DreamReportEntity::getDeleted, 0));
            if (report == null) {
                return R.fail("Report not found or does not belong to this agent");
            }
            // Key must match a recall entry that was a candidate during this dream run
            // (lastRecalledAt between report.startedAt and report.finishedAt)
            List<MemoryRecallEntity> candidates = recallMapper.selectList(
                    new LambdaQueryWrapper<MemoryRecallEntity>()
                            .eq(MemoryRecallEntity::getAgentId, agentId)
                            .ge(MemoryRecallEntity::getLastRecalledAt, report.getStartedAt())
                            .le(MemoryRecallEntity::getLastRecalledAt, report.getFinishedAt())
                            .eq(MemoryRecallEntity::getDeleted, 0));
            // Exact match on the section key (part after # in filename)
            boolean keyBelongsToReport = candidates.stream()
                    .anyMatch(c -> {
                        if (c.getFilename() == null) return false;
                        int hash = c.getFilename().indexOf('#');
                        String entryKey = hash >= 0 ? c.getFilename().substring(hash + 1) : c.getFilename();
                        return entryKey.equals(decodedKey);
                    });
            if (!keyBelongsToReport) {
                return R.fail("Entry '" + decodedKey + "' does not belong to report " + reportId);
            }
        } else {
            // Direct edit (reportId=0, from MemoryBrowser): only require section exists
            if (!hilService.sectionExists(agentId, decodedKey)) {
                return R.fail("Section '" + decodedKey + "' not found in MEMORY.md");
            }
        }

        String newContent = body.get("content");
        if (newContent == null || newContent.isBlank()) {
            return R.fail("content is required");
        }
        hilService.editMemoryEntry(agentId, decodedKey, newContent);
        return R.ok(null);
    }

    private Long resolveUserId(Authentication auth) {
        if (auth == null) return null;
        try {
            Object principal = auth.getPrincipal();
            if (principal instanceof vip.mate.auth.model.UserEntity user) {
                return user.getId();
            }
            // No stable user ID available — refuse rather than fabricate
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
