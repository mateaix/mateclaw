package vip.mate.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent-callable employee authoring tool.
 *
 * <p>Lets an agent design and persist a new specialized employee (Agent)
 * from a plain-language role spec, then bind a focused capability set to
 * it. Pairs with the workflow drafting tool so a single chat turn can plan
 * a team of employees and chain them into a workflow:
 * design roles → {@link #create_employee} for each → workflow drafting tool
 * referencing the just-created employees.
 *
 * <p>Workspace is taken from {@link ChatOrigin} on the active
 * {@link ToolContext}; the LLM can never write into a foreign workspace
 * even if its prompt tried to forge one. Mirrors the create-then-bind
 * sequence used when applying an agent template.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAuthoringTool {

    private final AgentService agentService;
    private final AgentBindingService agentBindingService;
    private final SkillMapper skillMapper;
    private final AvailableToolService availableToolService;
    private final ObjectMapper objectMapper;

    /** Cap on names listed per catalog section so the tool result stays small. */
    private static final int CATALOG_MAX_PER_SECTION = 200;

    @Tool(description = """
            Create a new specialized employee (Agent) in the current workspace from a role spec, \
            and optionally bind a focused set of skills and tools to it. \
            Use this when a task needs a role that does not exist yet — design the role, then create it. \
            Returns the new agentId (string) and a short summary. \
            Leave skillNames/toolNames empty to make a generalist that inherits all globally-enabled capabilities. \
            Call list_capability_catalog first to learn the exact skill and tool names you can assign. \
            The created employee is enabled immediately and can be referenced by the workflow drafting tool.""")
    public String create_employee(
            @ToolParam(description = "Employee name, unique within the workspace, e.g. \"market-research-analyst\".")
            String name,
            @ToolParam(description = "One-line description of the employee's role and responsibility. Shown in pickers and used by the workflow planner to route work.")
            String description,
            @ToolParam(description = "System prompt that defines the employee's persona, expertise, and working style. Be specific about its specialty.")
            String systemPrompt,
            @ToolParam(description = "Agent type: \"react\" (single-loop reasoning, default) or \"plan_execute\" (decompose then execute). Leave empty for react.", required = false)
            String agentType,
            @ToolParam(description = "Optional model name override (must match an enabled model). Leave empty to use the workspace default model.", required = false)
            String modelName,
            @ToolParam(description = "Skills to bind, as a JSON array of skill names or a comma-separated list, e.g. [\"sql_query\",\"make_plan\"]. Empty = inherit all globally-enabled skills. Names must come from list_capability_catalog.", required = false)
            String skillNames,
            @ToolParam(description = "Tools to bind, as a JSON array of tool names or a comma-separated list, e.g. [\"web_search\",\"read_file\"]. Empty = inherit all globally-enabled tools. Names must come from list_capability_catalog.", required = false)
            String toolNames,
            @Nullable ToolContext ctx) {

        ChatOrigin origin = ChatOrigin.from(ctx);
        Long workspaceId = origin.workspaceId();
        if (workspaceId == null || workspaceId <= 0) {
            return "[error] Cannot determine the current workspace; invoke this tool within a workspace context.";
        }
        if (name == null || name.isBlank()) {
            return "[error] Employee name is required.";
        }

        AgentEntity agent = new AgentEntity();
        agent.setName(name.trim());
        agent.setDescription(blankToNull(description));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            agent.setSystemPrompt(systemPrompt);
        }
        agent.setAgentType(normalizeAgentType(agentType));
        agent.setModelName(blankToNull(modelName));
        agent.setWorkspaceId(workspaceId);
        agent.setCreatorUserId(parseUserId(origin.requesterId()));

        AgentEntity created;
        try {
            created = agentService.createAgent(agent);
        } catch (MateClawException e) {
            // Duplicate name / blank name surface here as a friendly message
            // so the planner can rename and retry instead of aborting.
            return "[error] Failed to create employee: " + e.getMessage();
        }

        List<String> requestedSkills = parseNameList(skillNames);
        List<String> requestedTools = parseNameList(toolNames);

        List<String> boundSkills = bindSkills(created, workspaceId, requestedSkills);
        List<String> boundTools = bindTools(created, requestedTools);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", String.valueOf(created.getId()));
        result.put("name", created.getName());
        result.put("agentType", created.getAgentType());
        result.put("skillsBound", boundSkills.isEmpty() ? "(inherits global defaults)" : boundSkills);
        result.put("toolsBound", boundTools.isEmpty() ? "(inherits global defaults)" : boundTools);
        result.put("note", "Employee created and enabled. Reference it by name in the workflow drafting tool to chain it into a workflow.");
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "Employee created: id=" + created.getId() + " name=" + created.getName();
        }
    }

    @Tool(description = """
            List the capabilities you can assign when creating an employee: the enabled skill names \
            and the bindable tool names in the current workspace. \
            Call this before create_employee so you assign real, resolvable names rather than guessing.""")
    public String list_capability_catalog(@Nullable ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        Long workspaceId = origin.workspaceId();

        // Skills: builtin (global) + skills owned by this workspace, enabled only.
        List<SkillEntity> skills = skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getEnabled, true)
                .eq(SkillEntity::getDeleted, 0)
                .orderByAsc(SkillEntity::getName));
        long effectiveWs = workspaceId == null ? 1L : workspaceId;
        List<Map<String, String>> skillCatalog = new ArrayList<>();
        for (SkillEntity s : skills) {
            if (s.getName() == null || s.getName().isBlank()) continue;
            boolean builtin = Boolean.TRUE.equals(s.getBuiltin());
            long skillWs = s.getWorkspaceId() == null ? 1L : s.getWorkspaceId();
            if (!builtin && skillWs != effectiveWs) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("description", s.getDescription() == null ? "" : s.getDescription());
            skillCatalog.add(m);
            if (skillCatalog.size() >= CATALOG_MAX_PER_SECTION) break;
        }

        // Tools: only those the binding service would accept (available == true).
        List<Map<String, String>> toolCatalog = new ArrayList<>();
        try {
            for (AvailableToolDTO t : availableToolService.listAvailable()) {
                if (t == null || !t.isAvailable() || t.getName() == null || t.getName().isBlank()) continue;
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", t.getName());
                m.put("description", t.getDescription() == null ? "" : t.getDescription());
                toolCatalog.add(m);
                if (toolCatalog.size() >= CATALOG_MAX_PER_SECTION) break;
            }
        } catch (Exception e) {
            log.warn("[AgentAuthoringTool] tool catalog lookup failed: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skills", skillCatalog);
        result.put("tools", toolCatalog);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"skills\":[],\"tools\":[]}";
        }
    }

    // ==================== helpers ====================

    /**
     * Resolve requested skill names to ids within reach of this agent
     * (builtin skills are global; otherwise the skill must belong to the
     * agent's workspace) and bind them. Returns the names actually bound;
     * unresolved names are skipped with a warning so a single typo does not
     * abort the whole hire.
     */
    private List<String> bindSkills(AgentEntity agent, long workspaceId, List<String> requestedSkills) {
        if (requestedSkills.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>();
        List<String> boundNames = new ArrayList<>();
        for (String raw : requestedSkills) {
            String skillName = raw.trim();
            if (skillName.isEmpty()) continue;
            List<SkillEntity> matches = skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                    .eq(SkillEntity::getName, skillName)
                    .eq(SkillEntity::getDeleted, 0));
            SkillEntity chosen = matches.stream()
                    .filter(s -> {
                        if (Boolean.TRUE.equals(s.getBuiltin())) return true;
                        long ws = s.getWorkspaceId() == null ? 1L : s.getWorkspaceId();
                        return ws == workspaceId;
                    })
                    .findFirst()
                    .orElse(null);
            if (chosen == null) {
                log.warn("[AgentAuthoringTool] skill '{}' not resolvable for workspace {}; skipping", skillName, workspaceId);
                continue;
            }
            ids.add(chosen.getId());
            boundNames.add(chosen.getName());
        }
        if (ids.isEmpty()) return List.of();
        try {
            // Best-effort: the employee is already persisted, so a late
            // binding failure (e.g. a skill row deleted between resolve and
            // bind) must not throw out of the tool and strand the caller with
            // an error on top of an already-created agent. The agent simply
            // keeps the default capability set instead.
            agentBindingService.setSkillBindings(agent.getId(), ids);
        } catch (Exception e) {
            log.warn("[AgentAuthoringTool] skill binding failed for agent {}; left on global defaults: {}",
                    agent.getId(), e.getMessage());
            return List.of();
        }
        return boundNames;
    }

    /**
     * Filter requested tool names through the picker (only available == true
     * names are bindable) and bind them. Returns the names actually bound.
     */
    private List<String> bindTools(AgentEntity agent, List<String> requestedTools) {
        if (requestedTools.isEmpty()) return List.of();
        Set<String> bindable;
        try {
            bindable = availableToolService.listAvailable().stream()
                    .filter(AvailableToolDTO::isAvailable)
                    .map(AvailableToolDTO::getName)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[AgentAuthoringTool] tool picker unavailable; skipping tool bind: {}", e.getMessage());
            return List.of();
        }
        List<String> filtered = new ArrayList<>();
        for (String raw : requestedTools) {
            String toolName = raw == null ? "" : raw.trim();
            if (toolName.isEmpty()) continue;
            if (bindable.contains(toolName)) {
                filtered.add(toolName);
            } else {
                log.warn("[AgentAuthoringTool] tool '{}' not bindable; skipping", toolName);
            }
        }
        if (filtered.isEmpty()) return List.of();
        try {
            // Best-effort, same rationale as bindSkills: never throw after the
            // employee has been created.
            agentBindingService.setToolBindings(agent.getId(), filtered);
        } catch (Exception e) {
            log.warn("[AgentAuthoringTool] tool binding failed for agent {}; left on global defaults: {}",
                    agent.getId(), e.getMessage());
            return List.of();
        }
        return filtered;
    }

    /** Parse a JSON array of strings or a comma-separated list into a name list. */
    private List<String> parseNameList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                List<String> parsed = objectMapper.readValue(trimmed, new TypeReference<List<String>>() {});
                return parsed == null ? List.of() : parsed;
            } catch (Exception ignored) {
                // Fall through to comma split — the model occasionally emits a
                // malformed array; a comma split still recovers most names.
            }
        }
        List<String> out = new ArrayList<>();
        for (String part : trimmed.replace("[", "").replace("]", "").split(",")) {
            String p = part.trim().replaceAll("^[\"']|[\"']$", "");
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static String normalizeAgentType(String agentType) {
        if (agentType == null || agentType.isBlank()) return "react";
        String t = agentType.trim().toLowerCase();
        return "plan_execute".equals(t) ? "plan_execute" : "react";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Best-effort numeric parse of the requester id for creator attribution. */
    private static Long parseUserId(String requesterId) {
        if (requesterId == null || requesterId.isBlank()) return null;
        try {
            return Long.parseLong(requesterId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
