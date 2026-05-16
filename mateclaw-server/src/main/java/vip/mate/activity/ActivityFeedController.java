package vip.mate.activity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.audit.model.AuditEventEntity;
import vip.mate.audit.repository.AuditEventMapper;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * RFC-090 §4.5 / §7 — unified Activity feed.
 *
 * <p>Merges three sources into one chronologically-ordered stream:
 * <ul>
 *   <li>{@code audit_event} — CRUD-style events on agents / channels /
 *       skills / wiki / workspace (the existing audit log)</li>
 *   <li>{@code tool_approval} — approval requests + their resolution
 *       (granted / denied / expired). Ties tool gating decisions
 *       directly to the audit timeline.</li>
 *   <li>Successful tool calls — RFC §4.5 mentions these, but the
 *       runtime doesn't yet persist a row per successful call.
 *       Returning an empty bucket keeps the API contract stable so
 *       the UI can light up automatically once a future commit adds
 *       persistence.</li>
 * </ul>
 *
 * <p>Pagination is best-effort: each source is paged from index 0
 * up to {@code size * 2}, then the merged list is trimmed and offset
 * in-memory. For workspaces with >>1k events / day a follow-up should
 * push merging into SQL; this is good enough for v1.
 */
@Tag(name = "Activity Feed (RFC-090)")
@RestController
@RequestMapping("/api/v1/activity")
@RequiredArgsConstructor
public class ActivityFeedController {

    private final AuditEventService auditEventService;
    private final AuditEventMapper auditEventMapper;
    private final ToolApprovalMapper toolApprovalMapper;

    /**
     * RFC-090 §4.5 — paginated activity feed.
     *
     * <p>Pagination strategy:
     * <ul>
     *   <li><b>Single-source filter</b> (source=audit | approval) →
     *       direct {@code BaseMapper.selectPage(...)} on the matching
     *       table. Both total and records are SQL-accurate.</li>
     *   <li><b>Combined feed</b> (source unset) → fetch
     *       {@code page*size} rows from each side, merge by time-desc,
     *       slice to the requested window. {@code total} is the sum
     *       of {@code selectCount} across both tables — exact for
     *       count, best-effort for time-merge ordering at very deep
     *       page numbers (the merge buffer is bounded but typical
     *       use stays within a few hundred rows).</li>
     * </ul>
     *
     * <p>Caps: {@code size} clamped to [1, 200]; {@code page} ≥ 1.
     */
    @Operation(summary = "Unified activity feed (audit + approval + tool calls)")
    @GetMapping("/feed")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> feed(
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size <= 0) size = 20;
        if (size > 200) size = 200;
        if (page <= 0) page = 1;

        boolean wantAudit = source == null || source.isBlank() || "audit".equalsIgnoreCase(source);
        boolean wantApproval = source == null || source.isBlank() || "approval".equalsIgnoreCase(source);

        // ───── Single-source path: direct SQL pagination ─────
        if (wantAudit && !wantApproval) {
            return R.ok(pageAuditOnly(workspaceId, page, size));
        }
        if (wantApproval && !wantAudit) {
            return R.ok(pageApprovalOnly(page, size));
        }

        // ───── Combined path: per-source paginate + merge ─────
        // Fetch page*size from each side so the merged window contains
        // the requested slice even in the worst case where one source
        // dominates the timeline. This is wasteful at very deep pages
        // but bounded — a follow-up can push merging into SQL via a
        // UNION ALL view if event volume gets into 10k+/day territory.
        int bufferSize = Math.max(size * page, 50);

        LambdaQueryWrapper<AuditEventEntity> auditQ = new LambdaQueryWrapper<AuditEventEntity>()
                .orderByDesc(AuditEventEntity::getCreateTime);
        if (workspaceId != null) auditQ.eq(AuditEventEntity::getWorkspaceId, workspaceId);
        IPage<AuditEventEntity> auditPage = auditEventMapper.selectPage(new Page<>(1, bufferSize), auditQ);

        LambdaQueryWrapper<ToolApprovalEntity> approvalQ = new LambdaQueryWrapper<ToolApprovalEntity>()
                .orderByDesc(ToolApprovalEntity::getCreatedAt);
        IPage<ToolApprovalEntity> approvalPage = toolApprovalMapper.selectPage(new Page<>(1, bufferSize), approvalQ);

        List<ActivityRow> rows = new ArrayList<>();
        for (AuditEventEntity ev : auditPage.getRecords()) rows.add(fromAuditEvent(ev));
        for (ToolApprovalEntity ap : approvalPage.getRecords()) rows.add(fromApproval(ap));
        rows.sort(Comparator.comparing(ActivityRow::time, Comparator.nullsLast(Comparator.reverseOrder())));

        long total = auditPage.getTotal() + approvalPage.getTotal();
        int from = Math.min((page - 1) * size, rows.size());
        int to = Math.min(from + size, rows.size());
        List<ActivityRow> sliced = rows.subList(from, to);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("page", page);
        resp.put("size", size);
        resp.put("total", total);
        resp.put("records", sliced);
        return R.ok(resp);
    }

    /** Pure SQL pagination on the audit_event table; total + records both
     *  come from the underlying {@link Page} object. */
    private Map<String, Object> pageAuditOnly(Long workspaceId, int page, int size) {
        LambdaQueryWrapper<AuditEventEntity> q = new LambdaQueryWrapper<AuditEventEntity>()
                .orderByDesc(AuditEventEntity::getCreateTime);
        if (workspaceId != null) q.eq(AuditEventEntity::getWorkspaceId, workspaceId);
        IPage<AuditEventEntity> p = auditEventMapper.selectPage(new Page<>(page, size), q);
        List<ActivityRow> records = new ArrayList<>(p.getRecords().size());
        for (AuditEventEntity ev : p.getRecords()) records.add(fromAuditEvent(ev));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("page", page);
        resp.put("size", size);
        resp.put("total", p.getTotal());
        resp.put("records", records);
        return resp;
    }

    /** Pure SQL pagination on the tool_approval table. */
    private Map<String, Object> pageApprovalOnly(int page, int size) {
        LambdaQueryWrapper<ToolApprovalEntity> q = new LambdaQueryWrapper<ToolApprovalEntity>()
                .orderByDesc(ToolApprovalEntity::getCreatedAt);
        IPage<ToolApprovalEntity> p = toolApprovalMapper.selectPage(new Page<>(page, size), q);
        List<ActivityRow> records = new ArrayList<>(p.getRecords().size());
        for (ToolApprovalEntity ap : p.getRecords()) records.add(fromApproval(ap));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("page", page);
        resp.put("size", size);
        resp.put("total", p.getTotal());
        resp.put("records", records);
        return resp;
    }

    private ActivityRow fromAuditEvent(AuditEventEntity ev) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("detailJson", ev.getDetailJson());
        detail.put("userAgent", ev.getUserAgent());
        detail.put("workspaceId", ev.getWorkspaceId());
        return new ActivityRow(
                "audit-" + ev.getId(),
                "audit",
                ev.getCreateTime(),
                ev.getUsername(),
                ev.getAction(),
                ev.getResourceType(),
                ev.getResourceName() != null ? ev.getResourceName() : ev.getResourceId(),
                ev.getIpAddress(),
                detail);
    }

    private ActivityRow fromApproval(ToolApprovalEntity ap) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("toolArguments", ap.getToolArguments());
        detail.put("summary", ap.getSummary());
        detail.put("maxSeverity", ap.getMaxSeverity());
        detail.put("status", ap.getStatus());
        detail.put("resolvedAt", ap.getResolvedAt());
        // Map approval status onto an audit-style action so the UI's
        // existing action coloring (CREATE / DELETE / etc.) keeps
        // working without a special case.
        String action = "APPROVAL_" + (ap.getStatus() == null ? "PENDING" : ap.getStatus().toUpperCase());
        return new ActivityRow(
                "approval-" + ap.getId(),
                "approval",
                ap.getCreatedAt(),
                ap.getResolvedBy() != null ? ap.getResolvedBy() : ap.getRequesterName(),
                action,
                "TOOL_APPROVAL",
                ap.getToolName(),
                null,
                detail);
    }

    /**
     * Wire-format row. Public record so Jackson serializes it directly
     * without needing a separate DTO.
     */
    public record ActivityRow(
            String id,
            String source,
            LocalDateTime time,
            String username,
            String action,
            String resourceType,
            String resourceName,
            String ipAddress,
            Map<String, Object> detail
    ) {}
}
