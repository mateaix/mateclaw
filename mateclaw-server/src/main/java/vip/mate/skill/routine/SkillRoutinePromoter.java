package vip.mate.skill.routine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import vip.mate.common.text.SecretRedactor;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.model.SkillOrigin;
import vip.mate.skill.routine.model.SkillRoutineCandidateEntity;
import vip.mate.skill.routine.repository.SkillRoutineCandidateMapper;
import vip.mate.tool.builtin.SkillManageTool;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a qualified recurring-request cluster into a class-level skill.
 *
 * <p>The distinguishing input is plural evidence: the synthesizer sees several
 * separate conversations that all served the same request, so it can describe
 * the shape they share instead of narrating one of them. That is exactly what
 * the single-window reflection reviewer cannot do, and it is why a routine
 * skill comes out at the class level without having to be talked into it.
 *
 * <p>Every write is routed through {@link SkillManageTool} so it inherits the
 * same security scan, name validation, builtin guard, and workspace export as
 * the in-band agent path. Because the tool call carries a {@link ChatOrigin}
 * naming the owning agent, the resulting skill is also auto-bound to that
 * agent — so the routine is reachable on the agent's very next turn, which is
 * the entire point of promoting it.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRoutinePromoter {

    private final SkillRoutineCandidateMapper candidateMapper;
    private final ConversationService conversationService;
    private final SkillManageTool skillManageTool;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final SkillRoutineProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Promote up to {@code maxPromotionsPerRun} qualified candidates.
     *
     * @return number of candidates that produced a skill
     */
    public int promoteQualified() {
        if (!properties.isEnabled()) {
            return 0;
        }
        List<SkillRoutineCandidateEntity> candidates = candidateMapper.selectList(
                new LambdaQueryWrapper<SkillRoutineCandidateEntity>()
                        .eq(SkillRoutineCandidateEntity::getStatus,
                                SkillRoutineCandidateEntity.STATUS_OBSERVING)
                        .ge(SkillRoutineCandidateEntity::getOccurrenceCount, properties.getMinOccurrences())
                        .ge(SkillRoutineCandidateEntity::getDistinctDayCount, properties.getMinDistinctDays())
                        .ge(SkillRoutineCandidateEntity::getLastSeenAt,
                                LocalDateTime.now().minusDays(Math.max(1, properties.getLookbackDays())))
                        .orderByDesc(SkillRoutineCandidateEntity::getOccurrenceCount)
                        .last("LIMIT " + Math.max(1, properties.getMaxPromotionsPerRun())));
        if (candidates.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        for (SkillRoutineCandidateEntity candidate : candidates) {
            try {
                if (promote(candidate)) {
                    promoted++;
                }
            } catch (Exception e) {
                log.warn("[SkillRoutine] Promotion failed for candidate {} ('{}'): {}",
                        candidate.getId(), candidate.getSignature(), e.getMessage());
            }
        }
        return promoted;
    }

    /**
     * Synthesize and persist the skill for one candidate.
     *
     * <p>Exposed so an operator can promote a candidate that has not yet met
     * the recurrence gates — the gates bound what the unattended pass does on
     * its own, not what a person may decide to do.
     *
     * @return {@code true} when a new skill was created
     */
    public boolean promoteCandidate(SkillRoutineCandidateEntity candidate) {
        return promote(candidate);
    }

    private boolean promote(SkillRoutineCandidateEntity candidate) {
        List<String> conversationIds = parseSamples(candidate.getSampleConversations());
        String evidence = buildEvidence(conversationIds, candidate.getWorkspaceId());
        if (evidence.isBlank()) {
            log.debug("[SkillRoutine] Candidate {} has no readable transcripts, skipping",
                    candidate.getId());
            return false;
        }

        String llmResponse;
        try {
            String systemPrompt = PromptLoader.loadPrompt("skill/routine-system");
            String userPrompt = PromptLoader.loadPrompt("skill/routine-user")
                    .replace("{occurrences}", String.valueOf(candidate.getOccurrenceCount()))
                    .replace("{days}", String.valueOf(candidate.getDistinctDayCount()))
                    .replace("{request}", candidate.getRepresentativeText() == null
                            ? candidate.getSignature() : candidate.getRepresentativeText())
                    .replace("{evidence}", evidence);
            ChatModel chatModel = buildChatModel();
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt))));
            llmResponse = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[SkillRoutine] Synthesis LLM call failed for candidate {}: {}",
                    candidate.getId(), e.getMessage());
            return false;
        }

        JsonNode plan = parseJson(llmResponse);
        if (plan == null) {
            return false;
        }
        String name = plan.path("name").asText("").strip().toLowerCase();
        String content = plan.path("content").asText(null);
        if (name.isBlank() || content == null || content.isBlank()) {
            log.debug("[SkillRoutine] Candidate {} produced no usable skill", candidate.getId());
            return false;
        }

        ToolContext toolContext = buildToolContext(candidate, conversationIds);
        String result = skillManageTool.skillManageAs(SkillOrigin.ROUTINE, "create", name, content,
                null, null, null, toolContext);
        boolean created = result != null && !result.startsWith("Error")
                && !result.startsWith("Security scan BLOCKED");
        boolean alreadyCovered = result != null && result.contains("already exists");
        if (!created && !alreadyCovered) {
            log.info("[SkillRoutine] Candidate {} rejected by skill_manage: {}", candidate.getId(), result);
            return false;
        }

        candidate.setStatus(SkillRoutineCandidateEntity.STATUS_PROMOTED);
        candidate.setPromotedSkillName(name);
        candidate.setPromotedAt(LocalDateTime.now());
        candidateMapper.updateById(candidate);
        log.info("[SkillRoutine] Promoted routine '{}' → skill '{}' for agent={} ({} occurrences over {} days)",
                candidate.getSignature(), name, candidate.getAgentId(),
                candidate.getOccurrenceCount(), candidate.getDistinctDayCount());
        return created;
    }

    /**
     * Stamp the tool call with the owning agent and the most recent member
     * conversation, so the created skill is attributed and auto-bound.
     */
    private ToolContext buildToolContext(SkillRoutineCandidateEntity candidate, List<String> conversationIds) {
        String sourceConversation = conversationIds.isEmpty() ? null : conversationIds.get(0);
        ChatOrigin origin = new ChatOrigin(candidate.getAgentId(), sourceConversation, "",
                candidate.getWorkspaceId(), null, null, null, false, null, null, null, null, null);
        return new ToolContext(Map.of(ChatOrigin.CTX_KEY, origin));
    }

    /**
     * Render the sample conversations as labelled transcripts. Each is capped
     * so a handful of long sessions cannot blow the synthesis context.
     */
    private String buildEvidence(List<String> conversationIds, Long workspaceId) {
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (String conversationId : conversationIds) {
            List<MessageEntity> messages;
            try {
                ConversationEntity conversation = conversationService.findByConversationId(conversationId);
                if (conversation == null || workspaceId == null
                        || !workspaceId.equals(conversation.getWorkspaceId())) {
                    log.warn("[SkillRoutine] Ignoring cross-workspace sample conversation {}", conversationId);
                    continue;
                }
                messages = conversationService.listMessages(conversationId);
            } catch (Exception e) {
                continue;
            }
            if (messages == null || messages.isEmpty()) {
                continue;
            }
            int limit = Math.max(2, properties.getTranscriptMessagesPerSample());
            List<MessageEntity> window = messages.size() > limit
                    ? messages.subList(0, limit) : messages;
            index++;
            sb.append("### Occurrence ").append(index).append("\n");
            for (MessageEntity m : window) {
                String label = switch (m.getRole() == null ? "" : m.getRole()) {
                    case "user" -> "User";
                    case "assistant" -> "Assistant";
                    case "tool" -> "Tool[" + (m.getToolName() == null ? "unknown" : m.getToolName()) + "]";
                    default -> null;
                };
                if (label == null || m.getContent() == null || m.getContent().isBlank()) {
                    continue;
                }
                sb.append(label).append(": ")
                        .append(SecretRedactor.redact(
                                truncate(m.getContent(), properties.getTranscriptTruncateChars())))
                        .append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private List<String> parseSamples(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    String v = n.asText("");
                    if (!v.isBlank()) {
                        out.add(v);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SkillRoutine] Sample list parse failed: {}", e.getMessage());
        }
        return out;
    }

    private JsonNode parseJson(String response) {
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
            JsonNode node = objectMapper.readTree(cleaned.strip());
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            log.debug("[SkillRoutine] Synthesis JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity model = null;
        if (properties.getModelId() != null && !properties.getModelId().isBlank()) {
            try {
                model = modelConfigService.getModel(Long.parseLong(properties.getModelId()));
            } catch (Exception e) {
                log.warn("[SkillRoutine] Invalid modelId '{}', falling back to default",
                        properties.getModelId());
            }
        }
        if (model == null) {
            model = modelConfigService.getDefaultModel();
        }
        return agentGraphBuilder.buildRuntimeChatModel(model);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "... [truncated]";
    }
}
