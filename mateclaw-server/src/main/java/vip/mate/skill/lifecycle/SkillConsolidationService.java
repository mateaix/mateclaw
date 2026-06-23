package vip.mate.skill.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;
import vip.mate.tool.builtin.SkillManageTool;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consolidation pass for the skill curator: merges near-duplicate
 * agent-created skills into a broader umbrella skill, then archives the
 * narrow ones it absorbed. Off by default — opt in via
 * {@code mateclaw.skill.curator.consolidate}.
 *
 * <p>The umbrella write is routed through {@link SkillManageTool} so it
 * inherits the full security scan / validation pipeline; the absorbed skills
 * are archived (not deleted) through {@link SkillLifecycleService} so they
 * stay recoverable. The reviewer can only ever cause skills already in the
 * curator's candidate set to be archived — names it invents are ignored.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillConsolidationService {

    private final SkillService skillService;
    private final SkillManageTool skillManageTool;
    private final SkillLifecycleService lifecycleService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final SkillLifecycleProperties properties;
    private final ObjectMapper objectMapper;

    private static final int CATALOG_BODY_TRUNCATE_CHARS = 1500;

    /**
     * Run a consolidation pass over the given candidate skills, recording
     * outcomes into the sweep report. No-op when consolidation is disabled or
     * there are too few candidates to bother.
     */
    public void consolidate(List<SkillEntity> candidates, LocalDateTime now,
                            boolean dryRun, SkillCuratorReport.Builder report) {
        if (!properties.isConsolidate()) {
            return;
        }
        List<SkillEntity> withContent = candidates.stream()
                .filter(s -> s.getSkillContent() != null && !s.getSkillContent().isBlank())
                .toList();
        if (withContent.size() < properties.getConsolidateMinSkills()) {
            return;
        }

        // Index by name so the reviewer can only ever archive in-scope skills.
        Map<String, SkillEntity> byName = new LinkedHashMap<>();
        for (SkillEntity s : withContent) {
            byName.put(s.getName(), s);
        }

        JsonNode groups = askReviewer(withContent);
        if (groups == null || !groups.isArray() || groups.isEmpty()) {
            return;
        }

        int applied = 0;
        for (JsonNode group : groups) {
            if (applied >= properties.getConsolidateMaxGroupsPerRun()) {
                break;
            }
            if (applyGroup(group, byName, now, dryRun, report)) {
                applied++;
            }
        }
    }

    private boolean applyGroup(JsonNode group, Map<String, SkillEntity> byName,
                               LocalDateTime now, boolean dryRun, SkillCuratorReport.Builder report) {
        String umbrellaName = group.path("umbrella_name").asText("").strip().toLowerCase();
        String umbrellaContent = group.path("umbrella_content").asText(null);
        String reason = group.path("reason").asText("");
        if (umbrellaName.isBlank() || umbrellaContent == null || umbrellaContent.isBlank()) {
            return false;
        }

        // Restrict absorbed skills to the in-scope candidate set, excluding the
        // umbrella itself — the reviewer cannot archive anything outside it.
        List<String> absorb = new ArrayList<>();
        for (JsonNode n : group.path("absorb")) {
            String nm = n.asText("").strip().toLowerCase();
            if (!nm.isBlank() && !nm.equals(umbrellaName) && byName.containsKey(nm) && !absorb.contains(nm)) {
                absorb.add(nm);
            }
        }
        SkillEntity existingUmbrella = skillService.findByName(umbrellaName);
        boolean willCreate = existingUmbrella == null;
        // A real merge must touch at least two distinct skills: a brand-new
        // umbrella needs >=2 absorbed; reusing an existing skill as the
        // umbrella needs >=1 absorbed (the umbrella itself is the second).
        boolean realMerge = willCreate ? absorb.size() >= 2 : !absorb.isEmpty();
        if (!realMerge) {
            log.debug("[SkillConsolidate] Skipping group '{}' — not a real merge", umbrellaName);
            return false;
        }

        if (dryRun) {
            report.consolidation(new SkillCuratorReport.ConsolidationRow(
                    umbrellaName, willCreate, absorb, false, reason));
            return true;
        }

        // Stamp the umbrella with a source conversation from one absorbed skill
        // so it stays curator-eligible under the AGENT_CREATED scope.
        String lineageConv = absorb.stream()
                .map(byName::get)
                .map(SkillEntity::getSourceConversationId)
                .filter(c -> c != null && !c.isBlank())
                .findFirst().orElse(null);
        ToolContext ctx = toolContext(lineageConv);

        String act = willCreate ? "create" : "edit";
        String result = skillManageTool.skill_manage(act, umbrellaName, umbrellaContent, null, null, null, ctx);
        boolean umbrellaOk = result != null
                && !result.startsWith("Error") && !result.startsWith("Security scan BLOCKED");
        if (!umbrellaOk) {
            log.debug("[SkillConsolidate] Umbrella {} '{}' rejected: {}", act, umbrellaName, result);
            return false;
        }

        // Archive the absorbed narrow skills (recoverable, never deleted).
        for (String nm : absorb) {
            SkillEntity victim = byName.get(nm);
            if (victim == null) {
                continue;
            }
            try {
                lifecycleService.applyManual(victim, LifecycleTransition.TO_ARCHIVED, now,
                        "consolidated into " + umbrellaName);
            } catch (Exception e) {
                log.warn("[SkillConsolidate] Failed to archive absorbed skill '{}': {}", nm, e.getMessage());
            }
        }

        log.info("[SkillConsolidate] {} umbrella '{}' absorbing {} — {}", act, umbrellaName, absorb, reason);
        report.consolidation(new SkillCuratorReport.ConsolidationRow(
                umbrellaName, willCreate, absorb, true, reason));
        return true;
    }

    private JsonNode askReviewer(List<SkillEntity> skills) {
        try {
            String systemPrompt = PromptLoader.loadPrompt("skill/consolidate-system");
            String userPrompt = PromptLoader.loadPrompt("skill/consolidate-user")
                    .replace("{skills}", buildCatalog(skills, properties.getConsolidateCatalogCharBudget()));
            ChatModel chatModel = buildChatModel();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)));
            ChatResponse response = chatModel.call(prompt);
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return null;
            }
            return parseJsonResponse(response.getResult().getOutput().getText());
        } catch (Exception e) {
            log.warn("[SkillConsolidate] Reviewer call failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildCatalog(List<SkillEntity> skills, int charBudget) {
        StringBuilder sb = new StringBuilder();
        for (SkillEntity skill : skills) {
            String entry = "### " + skill.getName() + "\n"
                    + (skill.getDescription() == null ? "" : skill.getDescription().strip() + "\n")
                    + truncate(skill.getSkillContent(), CATALOG_BODY_TRUNCATE_CHARS) + "\n\n";
            if (sb.length() + entry.length() > charBudget) {
                sb.append("... (catalog truncated)\n");
                break;
            }
            sb.append(entry);
        }
        return sb.toString().strip();
    }

    private ToolContext toolContext(String sourceConversationId) {
        ChatOrigin origin = new ChatOrigin(null, sourceConversationId, "", null, null,
                null, null, false, null, null, null, null);
        return new ToolContext(Map.of(ChatOrigin.CTX_KEY, origin));
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity model = null;
        if (properties.getConsolidateModelId() != null && !properties.getConsolidateModelId().isBlank()) {
            try {
                model = modelConfigService.getModel(Long.parseLong(properties.getConsolidateModelId()));
            } catch (Exception e) {
                log.warn("[SkillConsolidate] Invalid consolidateModelId '{}', using default",
                        properties.getConsolidateModelId());
            }
        }
        if (model == null) {
            model = modelConfigService.getDefaultModel();
        }
        return agentGraphBuilder.buildRuntimeChatModel(model);
    }

    private JsonNode parseJsonResponse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String cleaned = response.strip();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        try {
            return objectMapper.readTree(cleaned.strip());
        } catch (Exception e) {
            log.debug("[SkillConsolidate] JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "... [truncated]";
    }
}
