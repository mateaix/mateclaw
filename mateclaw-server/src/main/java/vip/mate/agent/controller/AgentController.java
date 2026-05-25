package vip.mate.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.channel.web.Utf8SseEmitter;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentState;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.vo.AgentCapabilitiesVO;
import vip.mate.audit.service.AuditEventService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.workspace.core.service.WorkspaceService;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 管理接口
 *
 * @author MateClaw Team
 */
@Tag(name = "Agent管理")
@Slf4j
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final AuditEventService auditEventService;
    private final AuthService authService;
    private final WorkspaceService workspaceService;
    private final ModelConfigService modelConfigService;
    private final ModelCapabilityService modelCapabilityService;
    private final SystemSettingService systemSettingService;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    @Operation(summary = "获取Agent列表")
    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<AgentEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(value = "enabled", required = false) Boolean enabled) {
        // 无 header 时强制使用默认 workspace，不返回全局数据
        long wsId = workspaceId != null ? workspaceId : 1L;
        // enabled=true: chat selectors hide disabled agents.
        // enabled=null: admin management page sees enabled + disabled.
        return R.ok(agentService.listAgentsByWorkspace(wsId, enabled));
    }

    @Operation(summary = "获取Agent详情")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("viewer")
    public R<AgentEntity> get(@PathVariable Long id,
                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent.getWorkspaceId(), workspaceId);
        return R.ok(agent);
    }

    @Operation(summary = "获取Agent当前能力（modality 集合 + sidecar 配置），用于聊天页提示条")
    @GetMapping("/{id}/capabilities")
    @RequireWorkspaceRole("viewer")
    public R<AgentCapabilitiesVO> capabilities(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent.getWorkspaceId(), workspaceId);

        ModelConfigEntity primary;
        try {
            primary = modelConfigService.resolveModel(agent.getModelName());
        } catch (Exception e) {
            // No default model configured yet — return a capabilities snapshot that
            // tells the UI "we can't say anything about this agent's modalities".
            return R.ok(AgentCapabilitiesVO.builder()
                    .agentId(id)
                    .modelName("")
                    .providerId("")
                    .modalities(List.of())
                    .build());
        }
        java.util.Set<ModelCapabilityService.Modality> modalities =
                modelCapabilityService.resolve(primary.getModelName(), primary.getModalities());

        SystemSettingsDTO settings = systemSettingService.getSettings();
        Long visionId = settings.getDefaultVisionModelId();
        Long videoId = settings.getDefaultVideoModelId();

        return R.ok(AgentCapabilitiesVO.builder()
                .agentId(id)
                .modelName(primary.getModelName())
                .providerId(primary.getProvider())
                .modalities(modalities.stream().map(Enum::name).toList())
                .defaultVisionModelId(visionId)
                .defaultVisionModelLabel(resolveSidecarLabel(visionId))
                .defaultVideoModelId(videoId)
                .defaultVideoModelLabel(resolveSidecarLabel(videoId))
                .build());
    }

    private String resolveSidecarLabel(Long modelId) {
        if (modelId == null) return null;
        try {
            ModelConfigEntity m = modelConfigService.getModel(modelId);
            return m == null ? null : m.getProvider() + " / " + m.getModelName();
        } catch (Exception e) {
            return null;
        }
    }

    @Operation(summary = "创建Agent")
    @PostMapping
    @RequireWorkspaceRole("member")
    public R<AgentEntity> create(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody AgentEntity agent,
            Authentication auth) {
        // 始终注入 workspace_id，无 header 时使用默认
        agent.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
        // RFC-077 §4.4: 记录创建者，让 member 后续可删除自建 Agent
        agent.setCreatorUserId(resolveUserId(auth));
        AgentEntity created = agentService.createAgent(agent);
        auditEventService.record("CREATE", "AGENT", String.valueOf(created.getId()), created.getName(), null);
        return R.ok(created);
    }

    @Operation(summary = "更新Agent")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<AgentEntity> update(@PathVariable Long id, @RequestBody AgentEntity agent,
                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity existing = agentService.getAgent(id);
        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);
        agent.setId(id);
        agent.setWorkspaceId(existing.getWorkspaceId()); // 不允许跨 workspace 迁移
        AgentEntity updated = agentService.updateAgent(agent);
        auditEventService.record("UPDATE", "AGENT", String.valueOf(id), updated.getName(), null);
        return R.ok(updated);
    }

    @Operation(summary = "删除Agent")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
                          Authentication auth) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent.getWorkspaceId(), workspaceId);

        // RFC-077 §4.4: 三选一鉴权 — 系统 admin / workspace admin+ / 创建者本人
        Long userId = resolveUserId(auth);
        boolean systemAdmin = isSystemAdmin(auth);
        boolean workspaceAdmin = !systemAdmin
                && workspaceService.hasPermission(agent.getWorkspaceId(), userId, "admin");
        boolean isCreator = userId.equals(agent.getCreatorUserId());
        if (!systemAdmin && !workspaceAdmin && !isCreator) {
            throw new MateClawException("err.agent.delete_forbidden", 403,
                    "Only the creator or a workspace admin can delete this Agent");
        }

        agentService.deleteAgent(id);
        auditEventService.record("DELETE", "AGENT", String.valueOf(id), agent.getName(), null);
        return R.ok();
    }

    @Operation(summary = "流式对话（SSE）")
    @GetMapping("/{id}/chat/stream")
    @RequireWorkspaceRole("viewer")
    public SseEmitter chatStream(
            @PathVariable Long id,
            @RequestParam String message,
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent != null ? agent.getWorkspaceId() : null, workspaceId);
        verifyAgentEnabled(agent);

        // RFC-058 PR-1: Utf8SseEmitter 显式 charset=UTF-8，防止中文 SSE 乱码
        SseEmitter emitter = new Utf8SseEmitter(5 * 60 * 1000L);
        sseExecutor.execute(() -> {
            try {
                agentService.chatStream(id, message, conversationId)
                        .doOnNext(chunk -> {
                            try {
                                emitter.send(SseEmitter.event().name("message").data(chunk));
                            } catch (IOException e) {
                                log.warn("SSE send error: {}", e.getMessage());
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(emitter::completeWithError)
                        .subscribe();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @Operation(summary = "同步对话")
    @PostMapping("/{id}/chat")
    @RequireWorkspaceRole("viewer")
    public R<String> chat(
            @PathVariable Long id,
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent != null ? agent.getWorkspaceId() : null, workspaceId);
        verifyAgentEnabled(agent);
        return R.ok(agentService.chat(id, request.getMessage(), request.getConversationId()));
    }

    @Operation(summary = "执行复杂任务（Plan-Execute）")
    @PostMapping("/{id}/execute")
    @RequireWorkspaceRole("viewer")
    public R<String> execute(
            @PathVariable Long id,
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent != null ? agent.getWorkspaceId() : null, workspaceId);
        verifyAgentEnabled(agent);
        return R.ok(agentService.execute(id, request.getMessage(), request.getConversationId()));
    }

    @Operation(summary = "获取Agent运行状态")
    @GetMapping("/{id}/state")
    @RequireWorkspaceRole("viewer")
    public R<AgentState> getState(@PathVariable Long id,
                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent != null ? agent.getWorkspaceId() : null, workspaceId);
        return R.ok(agentService.getAgentState(id));
    }

    @lombok.Data
    public static class ChatRequest {
        private String message;
        private String conversationId = "default";
    }

    /**
     * 校验目标资源实际归属的 workspace 与请求 header 一致。
     * 防止 "在 workspace A 鉴权，操作 workspace B 资源" 的跨域攻击。
     */
    private void verifyResourceWorkspace(Long resourceWorkspaceId, Long headerWorkspaceId) {
        long requestedWs = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (resourceWorkspaceId != null && !resourceWorkspaceId.equals(requestedWs)) {
            throw new MateClawException("err.common.wrong_workspace", 403, "资源不属于当前工作区");
        }
    }

    /**
     * Block runtime calls against an agent flagged as disabled.
     *
     * <p>{@code AgentService#getOrBuildAgent} also checks the flag, but only on
     * a cache miss — once the {@code BaseAgent} instance is warm, a flip to
     * disabled would silently keep serving requests until something else
     * invalidates the cache. Enforcing here at the controller closes that gap
     * for every external entry point.
     */
    private void verifyAgentEnabled(AgentEntity agent) {
        if (agent != null && !Boolean.TRUE.equals(agent.getEnabled())) {
            throw new MateClawException("err.agent.disabled", "Agent 已禁用: " + agent.getName());
        }
    }

    private Long resolveUserId(Authentication auth) {
        if (auth == null) {
            throw new MateClawException("err.auth.unauthenticated", 401, "Not authenticated");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", 401, "User not found: " + auth.getName());
        }
        return user.getId();
    }

    private boolean isSystemAdmin(Authentication auth) {
        if (auth == null) {
            return false;
        }
        UserEntity user = authService.findByUsername(auth.getName());
        return user != null && "admin".equalsIgnoreCase(user.getRole());
    }
}
