package vip.mate.agent.binding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import vip.mate.agent.binding.model.AgentProviderPreference;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.repository.AgentProviderPreferenceMapper;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.binding.repository.AgentToolBindingMapper;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 能力绑定服务
 * <p>
 * 管理 Agent 与 Skill/Tool 的关联关系。
 * 当 Agent 没有任何绑定记录时，默认使用全局 enabled 的 tool/skill（向后兼容）。
 * 一旦有绑定记录，则严格按绑定列表过滤。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class AgentBindingService {

    private final AgentSkillBindingMapper skillBindingMapper;
    private final AgentToolBindingMapper toolBindingMapper;
    private final AgentProviderPreferenceMapper providerPreferenceMapper;
    /**
     * {@code @Lazy} — SkillRuntimeService and AgentBindingService both sit
     * near the agent boot path; the lazy proxy avoids a circular bean
     * graph when SkillRuntimeService initializes after binding.
     */
    private final SkillRuntimeService skillRuntimeService;
    /**
     * Source of truth for what the picker can offer (built-in + MCP). Used
     * by {@link #setToolBindings} to refuse new tool names that the runtime
     * couldn't resolve anyway — closes the gap where a UI-disabled row
     * could still be saved by hitting the API directly.
     */
    private final AvailableToolService availableToolService;
    /**
     * Direct mapper access (instead of {@code AgentService}) to look up an
     * agent's workspace before binding a skill. {@code AgentService} pulls
     * in {@code AgentGraphBuilder}, which itself depends on
     * {@code AgentBindingService} — going through the service would create a
     * boot-time cycle. The mapper has no such transitive dependency.
     */
    private final AgentMapper agentMapper;
    /** Same reasoning as {@link #agentMapper}: skill workspace lookup. */
    private final SkillMapper skillMapper;
    /**
     * ACP virtual skills aren't rows in {@code mate_skill}; the bridge
     * synthesizes them from {@code mate_acp_endpoint}. We need this to
     * answer "what workspace does this virtual id belong to?" when an
     * agent tries to bind one. MCP virtual skills don't need a bridge
     * reference — {@link McpSkillBridge#isVirtualMcpSkillId(Long)} is a
     * static range check, and MCP servers carry no workspace today, so
     * binding any MCP virtual id is allowed for any agent.
     */
    private final AcpSkillBridge acpSkillBridge;

    @Autowired
    public AgentBindingService(AgentSkillBindingMapper skillBindingMapper,
                               AgentToolBindingMapper toolBindingMapper,
                               AgentProviderPreferenceMapper providerPreferenceMapper,
                               @Lazy SkillRuntimeService skillRuntimeService,
                               AvailableToolService availableToolService,
                               AgentMapper agentMapper,
                               SkillMapper skillMapper,
                               AcpSkillBridge acpSkillBridge) {
        this.skillBindingMapper = skillBindingMapper;
        this.toolBindingMapper = toolBindingMapper;
        this.providerPreferenceMapper = providerPreferenceMapper;
        this.skillRuntimeService = skillRuntimeService;
        this.availableToolService = availableToolService;
        this.agentMapper = agentMapper;
        this.skillMapper = skillMapper;
        this.acpSkillBridge = acpSkillBridge;
    }

    // ==================== Skill Bindings ====================

    public List<AgentSkillBinding> listSkillBindings(Long agentId) {
        return skillBindingMapper.selectList(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getAgentId, agentId)
                        .orderByAsc(AgentSkillBinding::getCreateTime));
    }

    /**
     * 获取 Agent 绑定的 enabled skill ID 集合。
     * 返回 null 表示该 agent 没有自定义绑定（使用全局默认）。
     */
    public Set<Long> getBoundSkillIds(Long agentId) {
        List<AgentSkillBinding> bindings = listSkillBindings(agentId);
        if (bindings.isEmpty()) {
            return null; // 无绑定 → 全局默认
        }
        return bindings.stream()
                .filter(b -> Boolean.TRUE.equals(b.getEnabled()))
                .map(AgentSkillBinding::getSkillId)
                .collect(Collectors.toSet());
    }

    public AgentSkillBinding bindSkill(Long agentId, Long skillId) {
        requireSameWorkspace(agentId, skillId);
        // 检查是否已绑定
        AgentSkillBinding existing = skillBindingMapper.selectOne(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getAgentId, agentId)
                        .eq(AgentSkillBinding::getSkillId, skillId));
        if (existing != null) {
            existing.setEnabled(true);
            skillBindingMapper.updateById(existing);
            return existing;
        }
        AgentSkillBinding binding = new AgentSkillBinding();
        binding.setAgentId(agentId);
        binding.setSkillId(skillId);
        binding.setEnabled(true);
        skillBindingMapper.insert(binding);
        return binding;
    }

    public void unbindSkill(Long agentId, Long skillId) {
        skillBindingMapper.delete(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getAgentId, agentId)
                        .eq(AgentSkillBinding::getSkillId, skillId));
    }

    /**
     * 批量设置 Agent 的 skill 绑定（替换模式）
     */
    public void setSkillBindings(Long agentId, List<Long> skillIds) {
        // Validate every incoming skill BEFORE touching the binding rows;
        // a half-applied save (old bindings dropped, new set rejected
        // mid-loop) would leave the agent silently un-bound from skills it
        // had a moment ago.
        if (skillIds != null) {
            for (Long skillId : skillIds) {
                requireSameWorkspace(agentId, skillId);
            }
        }
        // 删除旧绑定
        skillBindingMapper.delete(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getAgentId, agentId));
        // 创建新绑定
        if (skillIds != null) {
            for (Long skillId : skillIds) {
                AgentSkillBinding binding = new AgentSkillBinding();
                binding.setAgentId(agentId);
                binding.setSkillId(skillId);
                binding.setEnabled(true);
                skillBindingMapper.insert(binding);
            }
        }
    }

    /**
     * Refuse to bind a skill that doesn't share the agent's workspace.
     * Skills are per-workspace installable artifacts (each workspace has
     * its own catalog under {@code mate_skill.workspace_id}); letting
     * workspace A's agent bind workspace B's skill would leak capabilities
     * — and prompt content — across the tenancy boundary.
     *
     * <p>Three skill id flavors to handle:
     * <ul>
     *   <li><b>Real {@code mate_skill} rows</b> — straight mapper lookup,
     *       compare {@code workspace_id} to the agent's.</li>
     *   <li><b>Virtual MCP-derived ids</b> ({@code >= McpSkillBridge.VIRTUAL_ID_BASE})
     *       — pass through. MCP servers carry no workspace concept today,
     *       so any agent in any workspace may bind any MCP virtual skill.
     *       The picker (/skills/enabled) hands these out to every workspace.</li>
     *   <li><b>Virtual ACP-derived ids</b> ({@code AcpSkillBridge}'s range)
     *       — resolve through the bridge so the {@link SkillEntity#getWorkspaceId()}
     *       comes from the backing {@code mate_acp_endpoint.workspace_id},
     *       then apply the same workspace comparison.</li>
     * </ul>
     *
     * <p>Builtin skills are exempt: they are global capabilities seeded
     * once into the default workspace and shared with every workspace, so
     * any agent may bind them regardless of its own workspace. Only
     * workspace-owned skills (dynamic / installed / synthesized) are
     * tenancy-checked.
     *
     * @throws MateClawException 404 if the agent or skill doesn't exist;
     *                           403 on a workspace mismatch.
     */
    private void requireSameWorkspace(Long agentId, Long skillId) {
        if (agentId == null) {
            throw new MateClawException("err.agent.not_found", 404, "Agent ID is required");
        }
        if (skillId == null) {
            throw new MateClawException("err.skill.not_found", 404, "Skill ID is required");
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new MateClawException("err.agent.not_found", 404, "Agent 不存在: " + agentId);
        }
        // MCP virtual: no workspace on McpServerEntity — globally bindable.
        if (McpSkillBridge.isVirtualMcpSkillId(skillId)) {
            return;
        }
        SkillEntity skill;
        if (AcpSkillBridge.isVirtualAcpSkillId(skillId)) {
            // ACP virtual: synthesize from the bridge so workspace_id flows
            // through from mate_acp_endpoint. A null reply here means the
            // backing endpoint was deleted or disabled between picker render
            // and save — same surface as a deleted real skill.
            skill = acpSkillBridge.findEntityById(skillId);
            if (skill == null) {
                throw new MateClawException("err.skill.not_found", 404,
                        "ACP endpoint backing skill " + skillId + " is gone or disabled");
            }
        } else {
            skill = skillMapper.selectById(skillId);
            if (skill == null) {
                throw new MateClawException("err.skill.not_found", 404, "Skill 不存在: " + skillId);
            }
        }
        // Builtin skills are global — shared across every workspace, so any
        // agent in any workspace may bind them (same stance as MCP virtuals
        // above). Only workspace-owned skills are tenancy-checked.
        if (Boolean.TRUE.equals(skill.getBuiltin())) {
            return;
        }
        long agentWs = agent.getWorkspaceId() == null ? 1L : agent.getWorkspaceId();
        long skillWs = skill.getWorkspaceId() == null ? 1L : skill.getWorkspaceId();
        if (agentWs != skillWs) {
            throw new MateClawException("err.skill.cross_workspace_binding", 403,
                    "Skill " + skillId + " (workspace=" + skillWs
                            + ") cannot be bound to Agent " + agentId
                            + " (workspace=" + agentWs + ")");
        }
    }

    // ==================== Tool Bindings ====================

    public List<AgentToolBinding> listToolBindings(Long agentId) {
        return toolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .orderByAsc(AgentToolBinding::getCreateTime));
    }

    /**
     * 获取 Agent 绑定的 enabled tool name 集合。
     * 返回 null 表示该 agent 没有自定义绑定（使用全局默认）。
     */
    public Set<String> getBoundToolNames(Long agentId) {
        List<AgentToolBinding> bindings = listToolBindings(agentId);
        if (bindings.isEmpty()) {
            return null; // 无绑定 → 全局默认
        }
        return bindings.stream()
                .filter(b -> Boolean.TRUE.equals(b.getEnabled()))
                .map(AgentToolBinding::getToolName)
                .collect(Collectors.toSet());
    }

    /**
     * RFC-090 §14.2 — single entry point that maps an agent's bindings to
     * the set of tool names allowed at runtime.
     *
     * <p>Three-state semantics (mirrors {@link #getBoundSkillIds} /
     * {@link #getBoundToolNames}):
     * <ul>
     *   <li><b>{@code null} bound skills + {@code null} bound tools</b> →
     *       returns {@code null}. Caller treats this as "no agent-level
     *       restriction; let the upstream {@code ToolSet} pass through
     *       its global default".</li>
     *   <li><b>at least one side non-null</b> → returns the union, which
     *       may be empty (= "this agent is explicitly restricted to no
     *       tools"). The caller must distinguish empty from null.</li>
     * </ul>
     *
     * <p>Skill expansion rules (§14.2):
     * <ul>
     *   <li>Resolved skill found → contribute
     *       {@code ResolvedSkill.getEffectiveAllowedTools()} — only tools
     *       whose owning feature is READY (unavailable features stay
     *       hidden from the LLM, §10.2 Q8).</li>
     *   <li>Skill bound but unresolved (e.g. legacy or missing manifest)
     *       → contribute nothing through this path; legacy SKILL.md prompt
     *       enhancement still runs separately.</li>
     * </ul>
     *
     * <p>Auto-included on every non-null result, in addition to the bound
     * tools and skill-expanded tools:
     * <ul>
     *   <li>{@link #SYSTEM_LEVEL_TOOLS} — agent-wide primitives.</li>
     *   <li>Every currently-bindable MCP tool (any tool with
     *       {@code source="mcp"} and {@code available=true} in the picker).
     *       MCP servers are administrator-level capabilities; once enabled
     *       globally they should not be silently hidden from an agent that
     *       happens to have any other binding. To deny a specific MCP tool
     *       to a specific agent, use the tool-guard deny path applied
     *       upstream in {@code AgentGraphBuilder}.</li>
     * </ul>
     */
    public Set<String> getEffectiveToolNames(Long agentId) {
        Set<Long> boundSkillIds = getBoundSkillIds(agentId);
        Set<String> directTools = getBoundToolNames(agentId);

        // (1) null + null → no restriction; defer to the global default.
        if (boundSkillIds == null && directTools == null) {
            return null;
        }

        Set<String> merged = new LinkedHashSet<>();

        if (boundSkillIds != null) {
            for (Long skillId : boundSkillIds) {
                ResolvedSkill resolved = findResolvedSkillById(skillId);
                if (resolved == null) continue;
                if (!vip.mate.skill.runtime.SkillRuntimeService.passesActiveGate(resolved)) {
                    // §14.2 fix: a disabled / security-blocked / setup-needed
                    // skill must not contribute tools to the LLM
                    // advertisement even if it's still bound. Without this
                    // guard, users see ghost tools for skills they thought
                    // were off.
                    continue;
                }
                Set<String> skillTools = resolved.getEffectiveAllowedTools();
                if (skillTools != null && !skillTools.isEmpty()) merged.addAll(skillTools);
            }
        }

        if (directTools != null) {
            // ∪ Advanced 直选的原子 tool（§9.2 调整 B）
            merged.addAll(directTools);
        }

        // System-level tools that don't belong to any single skill but
        // are agent-wide capabilities. Without this carve-out, binding
        // any skill silently strips record_lesson / remember / structured-
        // memory tools, breaking the §11 self-evolution loop entirely
        // (the LLM stops being able to write to LESSONS.md / MEMORY.md).
        merged.addAll(SYSTEM_LEVEL_TOOLS);

        // Enabled MCP server tools auto-join the allowlist for the same
        // reason SYSTEM_LEVEL_TOOLS does: MCP servers are an
        // administrator-enabled capability, not a per-agent opt-in. Without
        // this union, an agent with any skill or built-in tool bound would
        // silently lose every MCP tool — users hit this when they bound one
        // built-in tool, didn't tick the MCP rows, and observed "only
        // built-in tools work". Operators who need to hide a specific MCP
        // tool from a specific agent still have the tool-guard deny path
        // (AgentGraphBuilder applies withDeniedToolsFiltered before this).
        merged.addAll(getEnabledMcpToolNames());

        return merged;
    }

    /**
     * Names of every currently-bindable MCP tool, sourced from the same
     * picker that the agent edit screen reads. Failures (picker outage,
     * cache parse error) yield an empty set so the caller's allowlist is
     * strictly narrower, never wider, than the picker — never throws.
     */
    private Set<String> getEnabledMcpToolNames() {
        try {
            return availableToolService.listAvailable().stream()
                    .filter(t -> "mcp".equals(t.getSource()))
                    .filter(AvailableToolDTO::isAvailable)
                    .map(AvailableToolDTO::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.warn("AvailableToolService unavailable while computing effective tool allowlist; "
                    + "MCP tools will be excluded for this resolve cycle: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * RFC-090 §11 — tools that exist outside the skill scope and must
     * survive any agent-level skill binding restriction.
     *
     * <p>Add new entries here only after verifying the tool is genuinely
     * agent-wide, not skill-specific. Tools added here bypass the
     * {@link #getEffectiveToolNames} allowlist completely.
     */
    private static final Set<String> SYSTEM_LEVEL_TOOLS = Set.of(
            // Structured memory primitives — used by every agent regardless
            // of skill bindings, otherwise the self-evolution path collapses
            // (§11.3 / §11.4).
            "record_lesson",
            "remember",
            "remember_structured",
            "recall_structured",
            "forget_structured",
            // Workspace memory file CRUD (PROFILE.md / MEMORY.md / SOUL.md /
            // memory/YYYY-MM-DD.md). Prior versions whitelisted
            // "read_workspace_file" / "write_workspace_file" /
            // "list_workspace_files" — those names match no @Tool bean; the
            // actual function names carry the "_memory" segment, so the
            // earlier carve-out was silently dead.
            "list_workspace_memory_files",
            "read_workspace_memory_file",
            "write_workspace_memory_file",
            "edit_workspace_memory_file",
            // Skill discovery / dispatch — skills are docs, not callables;
            // these helpers let the LLM read SKILL.md / run scripts.
            "readSkillFile",
            "runSkillScript",
            "listSkillFiles",
            "listAvailableSkills",
            // Date / time — prior whitelist had a fictional "datetime"; the
            // real DateTimeTool exposes three separate methods.
            "getCurrentDate",
            "getCurrentDateTime",
            "getCurrentTime",
            // Multi-agent delegation — prior whitelist had "delegate_agent",
            // but DelegateAgentTool's @Tool methods are delegateToAgent /
            // delegateParallel / listAvailableAgents. Same dead-name bug.
            "delegateToAgent",
            "delegateParallel",
            "listAvailableAgents",
            // Document / media generation — agent-wide capabilities, never
            // declared inside any skill manifest. Pre-Phase-2b these were
            // universally visible; the new gate silently strips them whenever
            // any skill is bound, breaking "generate a Word doc / image /
            // song / video" intents on agents that happen to have a skill
            // on. Regression observed 2026-05-01: a Code Reviewer agent with
            // skills bound dropped renderDocx and fell back to dumping the
            // markdown body for the user to copy.
            "renderDocx",
            "renderDocxFromFile",
            "renderDocxFromFiles",
            "image_generate",
            "music_generate",
            "video_generate",
            // HTML → PNG rasteriser. Closes the loop for HTML-producing skills
            // (architecture-diagram, infographics, dashboards) so IM channels
            // can deliver the artifact as a native image instead of a file or
            // a dead markdown link.
            "render_html_image",
            // Universal capabilities the global system prompts (SOUL.md /
            // AGENTS.md / "Web Search Capability" / "File Reading Guidelines")
            // explicitly tell the LLM exist. Pre-Phase-2b they were globally
            // available; the new gate silently hid them on any agent with
            // skills bound, so the prompt promises a tool the registry then
            // refuses ("Tool not found: search"). Observed 2026-05-01 on the
            // Code Reviewer agent — the model called search → got
            // not-found → gave up before ever reaching renderDocx.
            "search",
            "browser_use",
            "read_file",
            "write_file",
            "edit_file",
            "execute_shell_command",
            "detect_file_type",
            "extract_document_text",
            "extract_pdf_text",
            "extract_docx_text",
            "readMateClawDoc"
    );

    private ResolvedSkill findResolvedSkillById(Long skillId) {
        if (skillId == null || skillRuntimeService == null) return null;
        // resolveAllSkillsStatus returns every skill in the catalog, not
        // just the active ones, so we still see READY/SETUP_NEEDED status
        // for bound but partially-unsatisfied skills.
        return skillRuntimeService.resolveAllSkillsStatus().stream()
                .filter(s -> s != null && skillId.equals(s.getId()))
                .findFirst()
                .orElse(null);
    }

    public AgentToolBinding bindTool(Long agentId, String toolName) {
        AgentToolBinding existing = toolBindingMapper.selectOne(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .eq(AgentToolBinding::getToolName, toolName));
        if (existing != null) {
            existing.setEnabled(true);
            toolBindingMapper.updateById(existing);
            return existing;
        }
        AgentToolBinding binding = new AgentToolBinding();
        binding.setAgentId(agentId);
        binding.setToolName(toolName);
        binding.setEnabled(true);
        toolBindingMapper.insert(binding);
        return binding;
    }

    public void unbindTool(Long agentId, String toolName) {
        toolBindingMapper.delete(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .eq(AgentToolBinding::getToolName, toolName));
    }

    /**
     * Replace the agent's tool binding set.
     *
     * <p>Validation rule for each incoming name:
     * <ul>
     *   <li><b>Already in the existing binding</b> → always allowed (so the
     *       user can keep a previously-bound tool whose upstream MCP server
     *       is currently stale or even removed; the client just keeps what
     *       it already had).</li>
     *   <li><b>New addition (not in existing binding)</b> → must appear in
     *       {@link AvailableToolService#listAvailable()} with
     *       {@code available == true}. Names that are unknown
     *       (typos / legacy unprefixed MCP names / hand-crafted strings) or
     *       that the picker marked unavailable (hash collision, etc.) are
     *       rejected — saving them would put a {@code mate_agent_tool} row
     *       in the database that the runtime can never resolve, which then
     *       silently drops the tool when the agent runs.</li>
     * </ul>
     */
    public void setToolBindings(Long agentId, List<String> toolNames) {
        validateNewToolBindings(agentId, toolNames);

        toolBindingMapper.delete(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId));
        if (toolNames != null) {
            for (String toolName : toolNames) {
                AgentToolBinding binding = new AgentToolBinding();
                binding.setAgentId(agentId);
                binding.setToolName(toolName);
                binding.setEnabled(true);
                toolBindingMapper.insert(binding);
            }
        }
    }

    /**
     * Refuse the save when any *newly-added* tool name doesn't resolve to
     * an {@code available=true} row in the picker. Names already in the
     * existing binding are exempt so that subsequent edits (especially
     * "remove this stale tool") still succeed even if upstream state has
     * drifted.
     */
    private void validateNewToolBindings(Long agentId, List<String> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        Set<String> existing = listToolBindings(agentId).stream()
                .map(AgentToolBinding::getToolName)
                .collect(Collectors.toSet());
        Set<String> bindable;
        try {
            bindable = availableToolService.listAvailable().stream()
                    .filter(AvailableToolDTO::isAvailable)
                    .map(AvailableToolDTO::getName)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            // The picker source briefly failing must not block the user
            // from saving a binding that's still in their existing set.
            // Re-validate everything against just the existing set —
            // strictly conservative: only allow keeps, refuse adds.
            log.warn("AvailableToolService unavailable during binding validation, falling back to existing-only: {}",
                    e.getMessage());
            bindable = Set.of();
        }

        List<String> rejected = new java.util.ArrayList<>();
        for (String name : incoming) {
            if (name == null || name.isBlank()) {
                rejected.add("<blank>");
                continue;
            }
            if (existing.contains(name)) continue; // keeps are always allowed
            if (!bindable.contains(name)) rejected.add(name);
        }
        if (!rejected.isEmpty()) {
            String preview = rejected.size() <= 5
                    ? String.join(", ", rejected)
                    : String.join(", ", rejected.subList(0, 5)) + " (+" + (rejected.size() - 5) + " more)";
            throw new MateClawException("err.agent.tool_binding_unbindable",
                    "Tool name(s) cannot be bound: " + preview
                            + ". Either the name is unknown or the picker marked it unavailable "
                            + "(e.g. hash collision, upstream server removed).");
        }
    }

    // ==================== Provider Preferences (RFC-009 PR-3) ====================

    /** Raw rows for the agent edit form. Sorted by sort_order ascending. */
    public List<AgentProviderPreference> listProviderPreferences(Long agentId) {
        return providerPreferenceMapper.selectList(
                new LambdaQueryWrapper<AgentProviderPreference>()
                        .eq(AgentProviderPreference::getAgentId, agentId)
                        .orderByAsc(AgentProviderPreference::getSortOrder));
    }

    /**
     * Ordered list of provider ids the agent prefers, lowest sort_order
     * first. Disabled rows are filtered out. Empty list means "no
     * preference — fall back to the global chain order".
     *
     * <p>Used by {@code AgentGraphBuilder.buildFallbackChain} to bias the
     * fallback chain order per agent.</p>
     */
    public List<String> getPreferredProviderIds(Long agentId) {
        if (agentId == null) return Collections.emptyList();
        return listProviderPreferences(agentId).stream()
                .filter(p -> Boolean.TRUE.equals(p.getEnabled()))
                .map(AgentProviderPreference::getProviderId)
                .collect(Collectors.toList());
    }

    /**
     * Replace the full preference list for an agent. {@code providerIds}
     * is the new ordered preference (index 0 = highest preference).
     * Empty / null list clears all preferences for the agent.
     */
    public void setProviderPreferences(Long agentId, List<String> providerIds) {
        providerPreferenceMapper.delete(
                new LambdaQueryWrapper<AgentProviderPreference>()
                        .eq(AgentProviderPreference::getAgentId, agentId));
        if (providerIds == null) return;
        int order = 0;
        for (String providerId : providerIds) {
            if (providerId == null || providerId.isBlank()) continue;
            AgentProviderPreference row = new AgentProviderPreference();
            row.setAgentId(agentId);
            row.setProviderId(providerId.trim());
            row.setSortOrder(order++);
            row.setEnabled(true);
            providerPreferenceMapper.insert(row);
        }
    }
}
