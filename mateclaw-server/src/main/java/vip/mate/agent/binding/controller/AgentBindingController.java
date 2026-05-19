package vip.mate.agent.binding.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.model.AgentProviderPreference;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * Agent 能力绑定接口
 *
 * @author MateClaw Team
 */
@Tag(name = "Agent能力绑定")
@RestController
@RequestMapping("/api/v1/agents/{agentId}")
@RequiredArgsConstructor
public class AgentBindingController {

    private final AgentBindingService bindingService;
    private final AgentService agentService;
    private final AuditEventService auditEventService;

    // ==================== Skill Bindings ====================

    @Operation(summary = "获取 Agent 已绑定的 Skills")
    @GetMapping("/skills")
    @RequireWorkspaceRole("viewer")
    public R<List<AgentSkillBinding>> listSkills(@PathVariable Long agentId,
                                                  @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyAgentWorkspace(agentId, workspaceId);
        return R.ok(bindingService.listSkillBindings(agentId));
    }

    @Operation(summary = "批量设置 Agent 的 Skill 绑定")
    @PutMapping("/skills")
    @RequireWorkspaceRole("member")
    public R<Void> setSkills(@PathVariable Long agentId, @RequestBody List<Long> skillIds,
                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyAgentWorkspace(agentId, workspaceId);
        bindingService.setSkillBindings(agentId, skillIds);
        agentService.invalidateAgentCache(agentId);
        auditEventService.record("UPDATE", "AGENT_SKILL", String.valueOf(agentId),
                "skills=" + skillIds.size(), null);
        return R.ok();
    }

    @Operation(summary = "绑定单个 Skill")
    @PostMapping("/skills/{skillId}")
    @RequireWorkspaceRole("member")
    public R<AgentSkillBinding> bindSkill(@PathVariable Long agentId, @PathVariable Long skillId,
                                           @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyAgentWorkspace(agentId, workspaceId);
        AgentSkillBinding binding = bindingService.bindSkill(agentId, skillId);
        agentService.invalidateAgentCache(agentId);
        return R.ok(binding);
    }

    @Operation(summary = "解绑单个 Skill")
    @DeleteMapping("/skills/{skillId}")
    @RequireWorkspaceRole("member")
    public R<Void> unbindSkill(@PathVariable Long agentId, @PathVariable Long skillId,
                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyAgentWorkspace(agentId, workspaceId);
        bindingService.unbindSkill(agentId, skillId);
        agentService.invalidateAgentCache(agentId);
        return R.ok();
    }

    // ==================== Tool Bindings ====================

    @Operation(summary = "获取 Agent 已绑定的 Tools")
    @GetMapping("/tools")
    @RequireWorkspaceRole("viewer")
    public R<List<AgentToolBinding>> listTools(@PathVariable Long agentId,
                                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyAgentWorkspace(agentId, workspaceId);
        return R.ok(bindingService.listToolBindings(agentId));
    }

    @Operation(summary = "批量设置 Agent 的 Tool 绑定")
    @PutMapping("/tools")
    @RequireWorkspaceRole("member")
    public R<Void> setTools(@PathVariable Long agentId, @RequestBody List<String> toolNames,
                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyAgentWorkspace(agentId, workspaceId);
        bindingService.setToolBindings(agentId, toolNames);
        agentService.invalidateAgentCache(agentId);
        auditEventService.record("UPDATE", "AGENT_TOOL", String.valueOf(agentId),
                "tools=" + toolNames.size(), null);
        return R.ok();
    }

    // ==================== Provider Preferences (RFC-009 PR-3) ====================

    @Operation(summary = "获取 Agent 的偏好 Provider 顺序")
    @GetMapping("/provider-preferences")
    @RequireWorkspaceRole("viewer")
    public R<List<AgentProviderPreference>> listProviderPreferences(
            @PathVariable Long agentId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyAgentWorkspace(agentId, workspaceId);
        return R.ok(bindingService.listProviderPreferences(agentId));
    }

    @Operation(summary = "批量设置 Agent 的偏好 Provider 顺序（替换模式）")
    @PutMapping("/provider-preferences")
    @RequireWorkspaceRole("member")
    public R<Void> setProviderPreferences(
            @PathVariable Long agentId,
            @RequestBody List<String> providerIds,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyAgentWorkspace(agentId, workspaceId);
        bindingService.setProviderPreferences(agentId, providerIds);
        agentService.invalidateAgentCache(agentId);
        auditEventService.record("UPDATE", "AGENT_PROVIDER_PREF", String.valueOf(agentId),
                "providers=" + providerIds.size(), null);
        return R.ok();
    }

    // ==================== Workspace Verification ====================

    private void verifyAgentWorkspace(Long agentId, Long headerWorkspaceId) {
        AgentEntity agent = agentService.getAgent(agentId);
        if (agent == null) {
            throw new MateClawException("Agent not found");
        }
        long requestedWs = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (agent.getWorkspaceId() != null && !agent.getWorkspaceId().equals(requestedWs)) {
            throw new MateClawException("err.common.wrong_workspace", 403, "资源不属于当前工作区");
        }
    }
}
