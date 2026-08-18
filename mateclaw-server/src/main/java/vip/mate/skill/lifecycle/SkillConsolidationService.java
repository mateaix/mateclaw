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
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.common.text.SecretRedactor;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillOrigin;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.tool.builtin.SkillManageTool;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final SkillWorkspaceManager workspaceManager;
    private final SkillConsolidationTransactionRunner transactionRunner;
    private final SkillRuntimeService runtimeService;
    private final AgentBindingService agentBindingService;

    /**
     * Run a consolidation pass over the given candidate skills, recording
     * outcomes into the sweep report. No-op when consolidation is disabled or
     * there are too few candidates to bother.
     */
    public void consolidate(List<SkillEntity> candidates, LocalDateTime now,
                            boolean dryRun, SkillCuratorReport.Builder report) {
        Long workspaceId = candidates == null ? 1L : candidates.stream()
                .map(SkillEntity::getWorkspaceId)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(1L);
        consolidate(candidates, now, dryRun, report, workspaceId);
    }

    public void consolidate(List<SkillEntity> candidates, LocalDateTime now,
                            boolean dryRun, SkillCuratorReport.Builder report,
                            Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0 || candidates == null) {
            return;
        }
        List<SkillEntity> withContent = candidates.stream()
                .filter(s -> workspaceId.equals(s.getWorkspaceId()))
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
            try {
                if (transactionRunner.execute(
                        () -> applyGroup(group, byName, now, dryRun, report, workspaceId))) {
                    applied++;
                }
            } catch (RuntimeException e) {
                // applyGroup has already compensated its filesystem work. The
                // transaction runner returns only after the DB rollback, so now
                // rebuild caches/wrappers from the committed state.
                runtimeService.refreshActiveSkills();
                throw e;
            }
        }
    }

    private boolean applyGroup(JsonNode group, Map<String, SkillEntity> byName,
                               LocalDateTime now, boolean dryRun, SkillCuratorReport.Builder report,
                               Long workspaceId) {
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
        SkillEntity existingUmbrella = skillService.findByName(umbrellaName, workspaceId);
        boolean willCreate = existingUmbrella == null;
        Path previousWorkspace = workspaceManager.resolveEffectivePath(umbrellaName, null, workspaceId);
        String previousWorkspaceContent = readWorkspaceContent(previousWorkspace);
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

        // The reviewer call may take seconds. Re-read every victim inside the
        // group transaction before any write so a concurrent pin, release,
        // workspace move, archive, or agent binding cancels the whole plan.
        for (String nm : absorb) {
            requireStillEligible(byName.get(nm), workspaceId);
        }

        // Stamp the umbrella with a source conversation from one absorbed skill
        // so it stays curator-eligible under the AGENT_CREATED scope.
        String lineageConv = absorb.stream()
                .map(byName::get)
                .map(SkillEntity::getSourceConversationId)
                .filter(c -> c != null && !c.isBlank())
                .findFirst().orElse(null);
        ToolContext ctx = toolContext(lineageConv, workspaceId);

        String act = willCreate ? "create" : "edit";
        String result = skillManageTool.skillManageAs(SkillOrigin.AGENT, act, umbrellaName,
                umbrellaContent, null, null, null, ctx);
        boolean umbrellaOk = result != null
                && !result.startsWith("Error") && !result.startsWith("Security scan BLOCKED");
        if (!umbrellaOk) {
            log.debug("[SkillConsolidate] Umbrella {} '{}' rejected: {}", act, umbrellaName, result);
            return false;
        }

        // Archive the absorbed narrow skills (recoverable, never deleted).
        // Compensate filesystem moves before propagating a failure so the
        // surrounding transaction can roll back the database half as well.
        List<SkillEntity> archived = new ArrayList<>();
        try {
            for (String nm : absorb) {
                SkillEntity victim = byName.get(nm);
                if (victim == null) {
                    continue;
                }
                SkillEntity freshVictim = requireStillEligible(victim, workspaceId);
                boolean ok = lifecycleService.applyManual(freshVictim, LifecycleTransition.TO_ARCHIVED, now,
                        "consolidated into " + umbrellaName);
                if (!ok) {
                    throw new IllegalStateException("Failed to archive absorbed skill '" + nm + "'");
                }
                archived.add(freshVictim);
            }
        } catch (Exception e) {
            for (int i = archived.size() - 1; i >= 0; i--) {
                SkillEntity victim = archived.get(i);
                if (workspaceManager.restoreWorkspace(victim.getName(), workspaceId)
                        == SkillWorkspaceManager.RestoreResult.FAILED) {
                    log.error("[SkillConsolidate] Filesystem compensation failed for '{}'", victim.getName());
                }
            }
            if (willCreate) {
                if (previousWorkspace == null) {
                    workspaceManager.purgeWorkspace(umbrellaName, workspaceId);
                } else if (previousWorkspaceContent != null) {
                    workspaceManager.exportToWorkspace(umbrellaName, previousWorkspaceContent, workspaceId);
                } else {
                    log.error("[SkillConsolidate] Refusing to purge pre-existing workspace for '{}' during compensation",
                            umbrellaName);
                }
            } else if (existingUmbrella.getSkillContent() != null) {
                workspaceManager.exportToWorkspace(umbrellaName,
                        existingUmbrella.getSkillContent(), workspaceId);
            }
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Consolidation compensation failed", e);
        }

        log.info("[SkillConsolidate] {} umbrella '{}' absorbing {} — {}", act, umbrellaName, absorb, reason);
        report.consolidation(new SkillCuratorReport.ConsolidationRow(
                umbrellaName, willCreate, absorb, true, reason));
        return true;
    }

    private SkillEntity requireStillEligible(SkillEntity planned, Long workspaceId) {
        if (planned == null || planned.getId() == null) {
            throw new IllegalStateException("Consolidation victim is no longer available");
        }
        SkillEntity fresh = skillService.getSkill(planned.getId());
        boolean wrongWorkspace = fresh == null || !workspaceId.equals(fresh.getWorkspaceId());
        boolean noLongerManaged = "AGENT_CREATED".equals(properties.getScope())
                && (fresh == null || !SkillOrigin.curatorManagedCodes().contains(fresh.getOrigin()));
        if (wrongWorkspace || noLongerManaged || lifecycleService.isExempt(fresh)
                || "archived".equals(fresh.getLifecycleState())
                || !agentBindingService.enabledAgentsBoundToSkill(fresh.getId()).isEmpty()) {
            throw new IllegalStateException("Skill '" + planned.getName()
                    + "' changed while consolidation was being reviewed");
        }
        return fresh;
    }

    private JsonNode askReviewer(List<SkillEntity> skills) {
        try {
            String catalog = buildCatalog(skills, properties.getConsolidateCatalogCharBudget());
            if (catalog == null) {
                log.info("[SkillConsolidate] Skipping reviewer: complete catalog exceeds {} chars",
                        properties.getConsolidateCatalogCharBudget());
                return null;
            }
            String systemPrompt = PromptLoader.loadPrompt("skill/consolidate-system");
            String userPrompt = PromptLoader.loadPrompt("skill/consolidate-user")
                    .replace("{skills}", catalog);
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

    private static String readWorkspaceContent(Path workspace) {
        if (workspace == null) {
            return null;
        }
        try {
            Path skillMd = workspace.resolve("SKILL.md");
            return Files.isRegularFile(skillMd) ? Files.readString(skillMd) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildCatalog(List<SkillEntity> skills, int charBudget) {
        StringBuilder sb = new StringBuilder();
        for (SkillEntity skill : skills) {
            String entry = "### " + skill.getName() + "\n"
                    + (skill.getDescription() == null ? "" : skill.getDescription().strip() + "\n")
                    + SecretRedactor.redact(skill.getSkillContent()) + "\n\n";
            if (sb.length() + entry.length() > charBudget) {
                return null;
            }
            sb.append(entry);
        }
        return sb.toString().strip();
    }

    private ToolContext toolContext(String sourceConversationId, Long workspaceId) {
        ChatOrigin origin = new ChatOrigin(null, sourceConversationId, "", workspaceId, null,
                null, null, false, null, null, null, null, null);
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

}
