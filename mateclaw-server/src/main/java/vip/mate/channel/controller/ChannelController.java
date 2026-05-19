package vip.mate.channel.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.verifier.ChannelVerifierRegistry;
import vip.mate.channel.verifier.VerificationRequest;
import vip.mate.channel.verifier.VerificationResult;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 渠道管理接口
 * <p>
 * 提供渠道的 CRUD、启用/禁用（联动 ChannelManager 生命周期）、状态查询等能力。
 * 对应前端 Channel 管理页面。
 *
 * @author MateClaw Team
 */
@Slf4j
@Tag(name = "渠道管理")
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;
    private final ChannelManager channelManager;
    private final AuditEventService auditEventService;
    private final ChannelVerifierRegistry verifierRegistry;
    private final ObjectMapper objectMapper;

    @RequireWorkspaceRole("admin")
    @Operation(summary = "获取渠道列表")
    @GetMapping
    public R<List<ChannelEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(channelService.listChannelsByWorkspace(wsId));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "按类型获取渠道列表")
    @GetMapping("/type/{channelType}")
    public R<List<ChannelEntity>> listByType(@PathVariable String channelType,
                                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(channelService.listChannelsByTypeAndWorkspace(channelType, wsId));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "获取渠道详情")
    @GetMapping("/{id}")
    public R<ChannelEntity> get(@PathVariable Long id,
                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity channel = channelService.getChannel(id);
        verifyResourceWorkspace(channel.getWorkspaceId(), workspaceId);
        return R.ok(channel);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "创建渠道")
    @PostMapping
    public R<ChannelEntity> create(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody ChannelEntity channel) {
        channel.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
        ChannelEntity created = channelService.createChannel(channel);
        // 创建后如果渠道已启用，自动启动（与 toggle 行为对齐）
        if (Boolean.TRUE.equals(created.getEnabled())) {
            channelManager.startChannel(created);
        }
        auditEventService.record("CREATE", "CHANNEL", String.valueOf(created.getId()), created.getName(), null);
        return R.ok(created);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "更新渠道")
    @PutMapping("/{id}")
    public R<ChannelEntity> update(@PathVariable Long id, @RequestBody ChannelEntity channel,
                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity existing = channelService.getChannel(id);
        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);
        channel.setId(id);
        channel.setWorkspaceId(existing.getWorkspaceId());
        ChannelEntity updated = channelService.updateChannel(channel);
        channelManager.restartChannel(id);
        auditEventService.record("UPDATE", "CHANNEL", String.valueOf(id), updated.getName(), null);
        return R.ok(updated);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "删除渠道")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity channel = channelService.getChannel(id);
        verifyResourceWorkspace(channel.getWorkspaceId(), workspaceId);
        channelManager.stopChannel(id);
        channelService.deleteChannel(id);
        auditEventService.record("DELETE", "CHANNEL", String.valueOf(id), channel.getName(), null);
        return R.ok();
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "启用/禁用渠道")
    @PutMapping("/{id}/toggle")
    public R<ChannelEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled,
                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity existing = channelService.getChannel(id);
        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);
        ChannelEntity channel = channelService.toggleChannel(id, enabled);
        // 联动 ChannelManager：启用时启动，禁用时停止
        if (enabled) {
            channelManager.startChannel(channel);
        } else {
            channelManager.stopChannel(id);
        }
        auditEventService.record(enabled ? "ENABLE" : "DISABLE", "CHANNEL", String.valueOf(id), channel.getName(), null);
        return R.ok(channel);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "获取渠道运行状态（全局系统视图，仅管理员可见）")
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        return R.ok(channelManager.getStatus());
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "获取指定渠道的实时健康状态（真连接状态，前端绿点应该绑这个）")
    @GetMapping("/{id}/health")
    public R<Map<String, Object>> health(@PathVariable Long id,
                                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity channel = channelService.getChannel(id);
        verifyResourceWorkspace(channel.getWorkspaceId(), workspaceId);
        return R.ok(channelManager.getAdapter(id)
                .map(adapter -> adapter.health().toMap())
                .orElseGet(() -> {
                    // Adapter not in active map: either disabled, never started,
                    // or still booting. Surface as OUT_OF_SERVICE so the frontend
                    // dot stays gray instead of red.
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("channelType", channel.getChannelType());
                    body.put("channelId", id);
                    body.put("status", "OUT_OF_SERVICE");
                    body.put("detail", Boolean.TRUE.equals(channel.getEnabled())
                            ? "channel enabled but adapter not active" : "channel disabled");
                    return body;
                }));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "批量获取所有渠道健康状态")
    @GetMapping("/health")
    public R<List<Map<String, Object>>> healthAll(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long ws = workspaceId != null ? workspaceId : 1L;
        return R.ok(channelService.listChannelsByWorkspace(ws).stream()
                .map(c -> {
                    Map<String, Object> body = channelManager.getAdapter(c.getId())
                            .map(a -> a.health().toMap())
                            .orElseGet(() -> {
                                Map<String, Object> m = new java.util.LinkedHashMap<>();
                                m.put("channelType", c.getChannelType());
                                m.put("channelId", c.getId());
                                m.put("status", "OUT_OF_SERVICE");
                                m.put("detail", Boolean.TRUE.equals(c.getEnabled())
                                        ? "channel enabled but adapter not active" : "channel disabled");
                                return m;
                            });
                    body.put("name", c.getName());
                    body.put("enabled", Boolean.TRUE.equals(c.getEnabled()));
                    body.put("identity", parseIdentity(c.getIdentityJson()));
                    return body;
                })
                .toList());
    }

    /**
     * Parse identity_json into a map for the list-page card. Returns an
     * empty map for legacy rows that have not been re-verified yet, so the
     * frontend can render the type-level description as a fallback.
     */
    private Map<String, Object> parseIdentity(String identityJson) {
        if (identityJson == null || identityJson.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(identityJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("identity_json parse failed (treating as empty): {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "Pre-flight: validate draft channel config without persisting")
    @PostMapping("/preflight")
    public R<VerificationResult> preflight(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody PreflightRequest body) {
        long ws = workspaceId != null ? workspaceId : 1L;
        Map<String, Object> config = parseConfigJson(body.configJson());
        return verifierRegistry.find(body.channelType())
                .map(v -> R.ok(v.verify(new VerificationRequest(body.channelType(), config, ws))))
                .orElseGet(() -> R.ok(VerificationResult.skipped(
                        "No verifier registered for channel type '" + body.channelType()
                                + "' — skipping live check.")));
    }

    private Map<String, Object> parseConfigJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("preflight: invalid configJson, treating as empty: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Wizard Step 2 payload — channel type + draft configJson, no entity yet. */
    public record PreflightRequest(String channelType, String configJson) {}

    private void verifyResourceWorkspace(Long resourceWorkspaceId, Long headerWorkspaceId) {
        long requestedWs = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (resourceWorkspaceId != null && !resourceWorkspaceId.equals(requestedWs)) {
            throw new MateClawException("err.common.wrong_workspace", 403, "资源不属于当前工作区");
        }
    }
}
