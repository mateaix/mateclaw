package vip.mate.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.vo.AgentDraftVO;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a single natural-language requirement into a ready-to-review employee
 * draft. The model is given the workspace's real capability catalog (tools,
 * skills, knowledge bases) and asked to pick from it, so the resulting draft
 * proposes a name, persona, type and a coherent set of capabilities in one
 * shot. Every suggested capability is re-validated against the catalog before
 * it leaves this service, so a hallucinated tool name or skill id never
 * reaches the wizard.
 *
 * <p>The draft is intentionally not persisted here. The wizard renders it for
 * review and edits, then commits through the existing agent-create and
 * capability-binding endpoints — reusing their tested persistence and audit
 * paths rather than duplicating them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentGenerationService {

    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final ObjectMapper objectMapper;
    private final AvailableToolService availableToolService;
    private final SkillService skillService;
    private final WikiKnowledgeBaseService wikiKnowledgeBaseService;

    /** Bound the catalog we feed the model so the prompt stays compact. */
    private static final int MAX_TOOLS = 60;
    private static final int MAX_SKILLS = 40;
    private static final int MAX_KBS = 20;

    public AgentDraftVO generateDraft(String requirement, Long workspaceId) {
        if (requirement == null || requirement.isBlank()) {
            throw new MateClawException("err.agent.generate_empty", 400,
                    "Please describe the employee you want to create");
        }
        long wsId = workspaceId != null ? workspaceId : 1L;

        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        if (defaultModel == null) {
            throw new MateClawException("err.agent.generate_no_model", 400,
                    "No default model is configured yet");
        }

        // Build the capability catalog the model is allowed to pick from.
        List<AvailableToolDTO> tools = bindableTools();
        List<SkillEntity> skills = workspaceSkills(wsId);
        List<WikiKnowledgeBaseEntity> kbs = workspaceKbs(wsId);

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(requirement.trim(), tools, skills, kbs);

        String raw;
        try {
            ChatModel chatModel = agentGraphBuilder.buildRuntimeChatModel(defaultModel);
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt))));
            raw = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
        } catch (Exception e) {
            log.warn("[AgentGen] LLM call failed: {}", e.getMessage());
            throw new MateClawException("err.agent.generate_failed", 500,
                    "Failed to generate employee draft");
        }

        JsonNode root = parseJson(raw);
        if (root == null || !root.isObject()) {
            throw new MateClawException("err.agent.generate_failed", 500,
                    "Model returned an unexpected response");
        }
        return toDraft(root, tools, skills, kbs);
    }

    // ==================== Catalog ====================

    private List<AvailableToolDTO> bindableTools() {
        List<AvailableToolDTO> all;
        try {
            all = availableToolService.listAvailable();
        } catch (Exception e) {
            log.warn("[AgentGen] failed to list tools: {}", e.getMessage());
            return List.of();
        }
        List<AvailableToolDTO> out = new ArrayList<>();
        for (AvailableToolDTO t : all) {
            // Only offer tools that are currently bindable and reachable; a
            // stale MCP tool would resolve to nothing at chat time.
            if (t != null && t.isAvailable() && !t.isStale()
                    && t.getName() != null && !t.getName().isBlank()) {
                out.add(t);
                if (out.size() >= MAX_TOOLS) break;
            }
        }
        return out;
    }

    private List<SkillEntity> workspaceSkills(long wsId) {
        try {
            List<SkillEntity> skills = skillService.listEnabledSkills(wsId);
            return skills.size() > MAX_SKILLS ? skills.subList(0, MAX_SKILLS) : skills;
        } catch (Exception e) {
            log.warn("[AgentGen] failed to list skills: {}", e.getMessage());
            return List.of();
        }
    }

    private List<WikiKnowledgeBaseEntity> workspaceKbs(long wsId) {
        try {
            List<WikiKnowledgeBaseEntity> kbs = wikiKnowledgeBaseService.listByWorkspace(wsId);
            return kbs.size() > MAX_KBS ? kbs.subList(0, MAX_KBS) : kbs;
        } catch (Exception e) {
            log.warn("[AgentGen] failed to list knowledge bases: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== Prompt ====================

    private String buildSystemPrompt() {
        return """
                You are an employee (AI agent) configuration generator for an agent platform.
                Given a one-sentence requirement, output a single JSON object describing one
                ready-to-use employee. Respond in the SAME language as the requirement.

                Output ONLY the JSON object, no prose, no markdown fences. Schema:
                {
                  "name": "short display name, no instruction words",
                  "icon": "a single emoji matching the role",
                  "description": "one concise sentence shown on the roster card",
                  "agentType": "react | plan_execute",
                  "role": "short role label",
                  "goal": "one short sentence on what this employee achieves",
                  "systemPrompt": "the full persona prompt: who it is, how it works, constraints",
                  "tags": ["1-3 short tags"],
                  "recommendedQuestions": ["2-4 starter questions a user might ask first"],
                  "tools": ["tool names chosen ONLY from the provided tool catalog"],
                  "skillIds": ["skill ids chosen ONLY from the provided skill catalog, as strings"],
                  "primaryKbId": "one knowledge base id from the catalog, or null"
                }

                Rules:
                - Use agentType "plan_execute" only for multi-step / long-horizon work; otherwise "react".
                - Pick tools, skillIds and primaryKbId ONLY from the catalogs given below. Never invent
                  names or ids. If nothing fits, return an empty array (or null for primaryKbId).
                - Prefer the smallest capability set that satisfies the requirement.
                - Skills already bundle their own tools, so do not also list a tool a chosen skill provides.
                """;
    }

    private String buildUserPrompt(String requirement, List<AvailableToolDTO> tools,
                                   List<SkillEntity> skills, List<WikiKnowledgeBaseEntity> kbs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Requirement:\n").append(requirement).append("\n\n");

        sb.append("Tool catalog (name — description):\n");
        if (tools.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (AvailableToolDTO t : tools) {
                sb.append("- ").append(t.getName());
                if (t.getDescription() != null && !t.getDescription().isBlank()) {
                    sb.append(" — ").append(trim(t.getDescription(), 120));
                }
                sb.append('\n');
            }
        }

        sb.append("\nSkill catalog (id — name — description):\n");
        if (skills.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (SkillEntity s : skills) {
                sb.append("- ").append(s.getId()).append(" — ").append(s.getName());
                if (s.getDescription() != null && !s.getDescription().isBlank()) {
                    sb.append(" — ").append(trim(s.getDescription(), 120));
                }
                sb.append('\n');
            }
        }

        sb.append("\nKnowledge base catalog (id — name — description):\n");
        if (kbs.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (WikiKnowledgeBaseEntity kb : kbs) {
                sb.append("- ").append(kb.getId()).append(" — ").append(kb.getName());
                if (kb.getDescription() != null && !kb.getDescription().isBlank()) {
                    sb.append(" — ").append(trim(kb.getDescription(), 120));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // ==================== Parse + validate ====================

    private AgentDraftVO toDraft(JsonNode root, List<AvailableToolDTO> tools,
                                 List<SkillEntity> skills, List<WikiKnowledgeBaseEntity> kbs) {
        String name = text(root, "name");
        if (name.isBlank()) {
            name = "New employee";
        }
        String agentType = text(root, "agentType");
        if (!"plan_execute".equals(agentType)) {
            agentType = "react";
        }

        return AgentDraftVO.builder()
                .name(trim(name, 60))
                .icon(firstEmoji(text(root, "icon")))
                .description(trim(text(root, "description"), 200))
                .agentType(agentType)
                .role(trim(text(root, "role"), 60))
                .goal(trim(text(root, "goal"), 120))
                .systemPrompt(text(root, "systemPrompt"))
                .tags(stringList(root.get("tags"), 5))
                .recommendedQuestions(stringList(root.get("recommendedQuestions"), 4))
                .tools(validTools(root.get("tools"), tools))
                .skillIds(validSkillIds(root.get("skillIds"), skills))
                .primaryKbId(validKbId(root.get("primaryKbId"), kbs))
                .build();
    }

    private List<String> validTools(JsonNode node, List<AvailableToolDTO> catalog) {
        Set<String> allowed = new LinkedHashSet<>();
        for (AvailableToolDTO t : catalog) allowed.add(t.getName());
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String v = n.asText("");
                if (allowed.contains(v) && !out.contains(v)) out.add(v);
            }
        }
        return out;
    }

    private List<Long> validSkillIds(JsonNode node, List<SkillEntity> catalog) {
        Map<Long, Boolean> allowed = new LinkedHashMap<>();
        for (SkillEntity s : catalog) allowed.put(s.getId(), Boolean.TRUE);
        List<Long> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                Long id = asLong(n);
                if (id != null && allowed.containsKey(id) && !out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    private Long validKbId(JsonNode node, List<WikiKnowledgeBaseEntity> catalog) {
        Long id = asLong(node);
        if (id == null) return null;
        for (WikiKnowledgeBaseEntity kb : catalog) {
            if (kb.getId().equals(id)) return id;
        }
        return null;
    }

    // ==================== Helpers ====================

    private JsonNode parseJson(String response) {
        if (response == null || response.isBlank()) return null;
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        cleaned = cleaned.trim();
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.debug("[AgentGen] JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** Accept both numeric and textual ids — textual is preferred to preserve precision. */
    private Long asLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            if (node.isTextual()) {
                String v = node.asText().trim();
                return v.isEmpty() ? null : Long.parseLong(v);
            }
            if (node.isNumber()) return node.asLong();
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return null;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? "" : n.asText("").trim();
    }

    private static List<String> stringList(JsonNode node, int max) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String v = n.asText("").trim();
                if (!v.isEmpty() && !out.contains(v)) {
                    out.add(v);
                    if (out.size() >= max) break;
                }
            }
        }
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Keep only the first emoji-ish glyph so the icon column never holds a sentence. */
    private static String firstEmoji(String s) {
        if (s == null || s.isBlank()) return "🤖";
        String t = s.trim();
        int end = t.offsetByCodePoints(0, Math.min(t.codePointCount(0, t.length()), 1));
        return t.substring(0, end);
    }
}
