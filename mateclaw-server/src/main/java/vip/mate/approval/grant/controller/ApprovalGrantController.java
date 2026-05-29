package vip.mate.approval.grant.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.approval.grant.entity.ApprovalGrant;
import vip.mate.approval.grant.entity.ApprovalResolutionLog;
import vip.mate.approval.grant.repository.ApprovalGrantMapper;
import vip.mate.approval.grant.repository.ApprovalResolutionLogMapper;
import vip.mate.approval.grant.service.ApprovalGrantService;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.workspace.core.service.WorkspaceService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the auto-grant subsystem.
 * <p>
 * The header {@code @RequireWorkspaceRole("member")} is the minimum gate; the
 * §2.4.5 6-cell authorization matrix is enforced inside each handler:
 * <ul>
 *   <li>{@code CONVERSATION} scope — any workspace member.</li>
 *   <li>{@code USER} scope — only the actor can create a grant for themselves.</li>
 *   <li>{@code AGENT} scope with explicit {@code toolName} — agent owner or admin.</li>
 *   <li>{@code AGENT} scope with {@code toolName=null} — admin only, plus password.</li>
 *   <li>{@code WORKSPACE} scope with explicit {@code toolName} — admin only.</li>
 *   <li>{@code WORKSPACE} scope with {@code toolName=null} — admin only, plus password.</li>
 * </ul>
 *
 * <p>Snowflake id fields ({@code id} / {@code grantedBy} / {@code revokedBy} /
 * {@code scopeId}) are serialized as strings by the global Jackson config so the
 * frontend keeps them as strings end-to-end (see CLAUDE.md precision convention).
 */
@Tag(name = "自动批准策略")
@Slf4j
@RestController
@RequestMapping("/api/v1/approval")
@RequiredArgsConstructor
public class ApprovalGrantController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final ApprovalGrantService grantService;
    private final ApprovalGrantMapper grantMapper;
    private final ApprovalResolutionLogMapper resolutionMapper;
    private final AuthService authService;
    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;

    // ─── Create ─────────────────────────────────────────────────────────

    @Operation(summary = "创建自动批准策略")
    @PostMapping("/grants")
    @RequireWorkspaceRole("member")
    public R<ApprovalGrant> create(@RequestBody CreateGrantRequest body,
                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
                                   Authentication auth) {
        Long actorId = resolveUserId(auth);
        Long ws = workspaceId != null ? workspaceId : DEFAULT_WORKSPACE_ID;

        validate(body);
        enforceCreationAuthorization(body, actorId, ws);

        ApprovalGrant grant = new ApprovalGrant();
        grant.setWorkspaceId(ws);
        grant.setScopeType(body.scopeType);
        grant.setScopeId(body.scopeId);
        grant.setToolName(emptyToNull(body.toolName));
        grant.setRuleId(emptyToNull(body.ruleId));
        grant.setMaxSeverity(body.maxSeverity);
        grant.setGrantKind(body.grantKind);
        grant.setExpireAt(body.expireAt);
        grant.setGrantedBy(actorId);
        grant.setGrantedAt(LocalDateTime.now());
        grant.setRevoked(0);
        grant.setDeleted(0);
        grant.setNote(body.note);

        grantMapper.insert(grant);
        log.info("[APPROVAL] Grant created: id={} scope={}/{} tool={} rule={} ceiling={} kind={} by user={}",
                grant.getId(), grant.getScopeType(), grant.getScopeId(),
                grant.getToolName(), grant.getRuleId(), grant.getMaxSeverity(),
                grant.getGrantKind(), actorId);
        return R.ok(grant);
    }

    // ─── List ───────────────────────────────────────────────────────────

    @Operation(summary = "列出当前 workspace 的自动批准策略（分页）")
    @GetMapping("/grants")
    @RequireWorkspaceRole("member")
    public R<IPage<ApprovalGrant>> list(
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) Integer revoked,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "20") long size,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {
        Long actorId = resolveUserId(auth);
        Long ws = workspaceId != null ? workspaceId : DEFAULT_WORKSPACE_ID;

        // mine=false (看全部) 需要 admin；mine=true 任意 member 可以看自己的
        if (!mine) {
            workspaceService.requirePermission(ws, actorId, "admin");
        }

        // Bound page size so a malformed client can't blow up the UI / mapper.
        long boundedSize = Math.min(Math.max(size, 1), 200);
        long boundedPage = Math.max(page, 1);

        var wrapper = Wrappers.<ApprovalGrant>lambdaQuery()
                .eq(ApprovalGrant::getWorkspaceId, ws)
                .eq(ApprovalGrant::getDeleted, 0)
                .orderByDesc(ApprovalGrant::getGrantedAt);
        if (scopeType != null && !scopeType.isEmpty()) {
            wrapper.eq(ApprovalGrant::getScopeType, scopeType);
        }
        if (toolName != null && !toolName.isEmpty()) {
            wrapper.eq(ApprovalGrant::getToolName, toolName);
        }
        if (revoked != null) {
            wrapper.eq(ApprovalGrant::getRevoked, revoked);
        }
        if (mine) {
            wrapper.eq(ApprovalGrant::getGrantedBy, actorId);
        }
        Page<ApprovalGrant> pageObj = new Page<>(boundedPage, boundedSize);
        IPage<ApprovalGrant> result = grantMapper.selectPage(pageObj, wrapper);
        fillGranterNames(result.getRecords());
        return R.ok(result);
    }

    /**
     * Batch-loads the display name (nickname → username fallback) for every
     * unique {@code grantedBy} id on the page and writes it into the entity's
     * transient {@code grantedByName} field. One round-trip via
     * {@code selectBatchIds} rather than N queries; the field stays null when
     * the source user has since been deleted.
     */
    private void fillGranterNames(java.util.List<ApprovalGrant> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        java.util.Set<Long> userIds = new java.util.HashSet<>();
        for (ApprovalGrant g : records) {
            if (g.getGrantedBy() != null) userIds.add(g.getGrantedBy());
        }
        if (userIds.isEmpty()) return;
        java.util.Map<Long, String> idToName = userMapper.selectBatchIds(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        UserEntity::getId,
                        u -> u.getNickname() != null && !u.getNickname().isEmpty()
                                ? u.getNickname()
                                : u.getUsername(),
                        (a, b) -> a));
        for (ApprovalGrant g : records) {
            if (g.getGrantedBy() != null) {
                g.setGrantedByName(idToName.get(g.getGrantedBy()));
            }
        }
    }

    // ─── Active summary (chip "(N)") ────────────────────────────────────

    @Operation(summary = "当前 workspace 的活跃策略数量摘要")
    @GetMapping("/grants/active")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> activeSummary(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        Long ws = workspaceId != null ? workspaceId : DEFAULT_WORKSPACE_ID;
        // Cast to int: this is a per-workspace grant count, never bigger than a
        // few hundred. Returning Long here would be serialized as a JSON string
        // by the global Long→String serializer (CLAUDE.md precision convention
        // for snowflake ids), but count is not a snowflake — the frontend wants
        // a real number for the chip badge and `count > 0` checks.
        int count = (int) Math.min(grantService.countActiveInWorkspace(ws), Integer.MAX_VALUE);
        // hasWorkspaceWide: workspace + tool_name IS NULL — the dangerous one.
        Long workspaceWide = grantMapper.selectCount(
                Wrappers.<ApprovalGrant>lambdaQuery()
                        .eq(ApprovalGrant::getWorkspaceId, ws)
                        .eq(ApprovalGrant::getScopeType, ApprovalGrant.ScopeType.WORKSPACE)
                        .isNull(ApprovalGrant::getToolName)
                        .eq(ApprovalGrant::getRevoked, 0)
                        .eq(ApprovalGrant::getDeleted, 0)
                        .and(w -> w.isNull(ApprovalGrant::getExpireAt)
                                .or().gt(ApprovalGrant::getExpireAt, LocalDateTime.now())));
        Map<String, Object> out = new HashMap<>();
        out.put("count", count);
        out.put("hasWorkspaceWide", workspaceWide != null && workspaceWide > 0);
        return R.ok(out);
    }

    // ─── Revoke ─────────────────────────────────────────────────────────

    @Operation(summary = "撤销自动批准策略")
    @DeleteMapping("/grants/{id}")
    @RequireWorkspaceRole("member")
    public R<Void> revoke(@PathVariable Long id,
                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
                          Authentication auth) {
        Long actorId = resolveUserId(auth);
        Long ws = workspaceId != null ? workspaceId : DEFAULT_WORKSPACE_ID;

        ApprovalGrant existing = grantMapper.selectById(id);
        if (existing == null || (existing.getDeleted() != null && existing.getDeleted() == 1)) {
            throw new MateClawException("err.approval.grant_not_found", 404, "grant not found");
        }
        if (!existing.getWorkspaceId().equals(ws)) {
            // Cross-workspace lookup is treated as not-found to avoid leaking existence.
            throw new MateClawException("err.approval.grant_not_found", 404, "grant not found");
        }
        boolean isOwner = existing.getGrantedBy() != null && existing.getGrantedBy().equals(actorId);
        boolean isAdmin = workspaceService.hasPermission(ws, actorId, "admin");
        if (!isOwner && !isAdmin) {
            throw new MateClawException("err.approval.revoke_forbidden", 403,
                    "only the grant owner or a workspace admin can revoke");
        }
        grantService.revoke(id, actorId);
        log.info("[APPROVAL] Grant revoked: id={} by user={} (owner={}, admin={})",
                id, actorId, isOwner, isAdmin);
        return R.ok();
    }

    // ─── Resolutions read surface ───────────────────────────────────────

    @Operation(summary = "查询审批最终决策日志（按 grantId 或 conversationId 过滤）")
    @GetMapping("/resolutions")
    @RequireWorkspaceRole("member")
    public R<List<ApprovalResolutionLog>> listResolutions(
            @RequestParam(required = false) Long grantId,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false, defaultValue = "100") int limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {
        Long actorId = resolveUserId(auth);
        Long ws = workspaceId != null ? workspaceId : DEFAULT_WORKSPACE_ID;
        int cappedLimit = Math.min(Math.max(limit, 1), 500);

        // grantId queries are admin-only; conversationId queries (member view) just
        // filter by membership of the workspace.
        if (grantId != null) {
            workspaceService.requirePermission(ws, actorId, "admin");
        }

        var wrapper = Wrappers.<ApprovalResolutionLog>lambdaQuery()
                .eq(ApprovalResolutionLog::getDeleted, 0)
                .eq(ApprovalResolutionLog::getWorkspaceId, ws)
                .orderByDesc(ApprovalResolutionLog::getCreateTime)
                .last("LIMIT " + cappedLimit);
        if (grantId != null) {
            wrapper.eq(ApprovalResolutionLog::getGrantId, grantId);
        }
        if (conversationId != null && !conversationId.isEmpty()) {
            wrapper.eq(ApprovalResolutionLog::getConversationId, conversationId);
        }
        return R.ok(resolutionMapper.selectList(wrapper));
    }

    // ─── Authorization matrix ───────────────────────────────────────────

    /**
     * Enforces the §2.4.5 6-cell matrix. Throws {@link MateClawException} with
     * an HTTP 403 status when the actor is not permitted to create this scope.
     * Password second-factor is checked for the two cells that require it.
     */
    private void enforceCreationAuthorization(CreateGrantRequest body, Long actorId, Long workspaceId) {
        String scope = body.scopeType;
        boolean toolNull = body.toolName == null || body.toolName.isEmpty();
        boolean isAdmin = workspaceService.hasPermission(workspaceId, actorId, "admin");

        switch (scope) {
            case ApprovalGrant.ScopeType.CONVERSATION -> {
                // Any member; nothing extra.
            }
            case ApprovalGrant.ScopeType.USER -> {
                if (body.scopeId == null || !body.scopeId.equals(String.valueOf(actorId))) {
                    throw new MateClawException("err.approval.user_scope_self_only", 403,
                            "USER-scope grants can only target the requesting user");
                }
            }
            case ApprovalGrant.ScopeType.AGENT -> {
                if (toolNull) {
                    requireAdminPlusPassword(isAdmin, body.password, actorId);
                } else if (!isAdmin) {
                    // We don't currently model an "agent owner" surface here, so admin is the safe default.
                    // (Refining this to support agent owner is a v1.1 follow-up.)
                    workspaceService.requirePermission(workspaceId, actorId, "admin");
                }
            }
            case ApprovalGrant.ScopeType.WORKSPACE -> {
                workspaceService.requirePermission(workspaceId, actorId, "admin");
                if (toolNull) {
                    requireAdminPlusPassword(true, body.password, actorId);
                }
            }
            default -> throw new MateClawException("err.approval.invalid_scope", 400,
                    "unknown scope_type: " + scope);
        }
    }

    private void requireAdminPlusPassword(boolean isAdmin, String rawPassword, Long actorId) {
        if (!isAdmin) {
            throw new MateClawException("err.approval.admin_required", 403, "admin role required");
        }
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new MateClawException("err.approval.password_required", 403,
                    "this scope requires password re-confirmation");
        }
        authService.verifyCurrentUserPassword(actorId, rawPassword);
    }

    // ─── Validation ─────────────────────────────────────────────────────

    private static void validate(CreateGrantRequest body) {
        if (body.scopeType == null || body.scopeType.isEmpty()) {
            throw new MateClawException("err.approval.scope_type_required", 400, "scope_type is required");
        }
        if (body.scopeId == null || body.scopeId.isEmpty()) {
            throw new MateClawException("err.approval.scope_id_required", 400, "scope_id is required");
        }
        if (body.maxSeverity == null
                || !(body.maxSeverity.equals("LOW") || body.maxSeverity.equals("MEDIUM") || body.maxSeverity.equals("HIGH"))) {
            // CRITICAL is explicitly rejected so it can never be auto-approvable; the resolver
            // enforces the same gate at runtime as a defense in depth.
            throw new MateClawException("err.approval.invalid_severity", 400,
                    "max_severity must be LOW | MEDIUM | HIGH (CRITICAL is not auto-approvable)");
        }
        if (body.grantKind == null
                || !(body.grantKind.equals("ALWAYS")
                        || body.grantKind.equals("UNTIL_TIMESTAMP")
                        || body.grantKind.equals("UNTIL_CONVERSATION_END"))) {
            throw new MateClawException("err.approval.invalid_grant_kind", 400,
                    "grant_kind must be ALWAYS | UNTIL_TIMESTAMP | UNTIL_CONVERSATION_END");
        }
        if ("UNTIL_TIMESTAMP".equals(body.grantKind) && body.expireAt == null) {
            throw new MateClawException("err.approval.expire_at_required", 400,
                    "expire_at is required when grant_kind = UNTIL_TIMESTAMP");
        }
        if ("UNTIL_CONVERSATION_END".equals(body.grantKind)
                && !ApprovalGrant.ScopeType.CONVERSATION.equals(body.scopeType)) {
            throw new MateClawException("err.approval.kind_scope_mismatch", 400,
                    "UNTIL_CONVERSATION_END requires scope_type = CONVERSATION");
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private Long resolveUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new MateClawException("err.auth.unauthenticated", 401, "未登录");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", 404, "用户不存在");
        }
        return user.getId();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // ─── DTO ────────────────────────────────────────────────────────────

    /**
     * Request body for {@link #create}. Snowflake ids ({@code scopeId}) are
     * received as strings to preserve precision through JS; the global Jackson
     * coercion accepts numeric JSON too, so existing tools that send numbers
     * still work.
     */
    public static class CreateGrantRequest {
        public String scopeType;
        public String scopeId;
        public String toolName;
        public String ruleId;
        public String maxSeverity;
        public String grantKind;
        public LocalDateTime expireAt;
        public String note;
        /** Required only for {@code WORKSPACE + tool_name=null} and {@code AGENT + tool_name=null}. */
        public String password;
    }
}
