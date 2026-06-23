package vip.mate.skill.reflection;

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
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.event.ConversationCompletedEvent;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;
import vip.mate.tool.builtin.SkillManageTool;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Out-of-band skill reflection — after a conversation finishes, reviews the
 * recent turns and autonomously creates or improves skills, mirroring the
 * memory-nudge cadence but writing to the skill registry instead of memory.
 *
 * <p>The review runs on an async thread so it never blocks the user response
 * and never consumes the live turn's context window. Every write is routed
 * back through {@link SkillManageTool#skill_manage} so it inherits the same
 * security scan, name validation, builtin guard, fuzzy-patch matching, and
 * workspace export as the in-band agent path — this service only decides
 * <em>what</em> to write, never <em>how</em>.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillReflectionService {

    private final ConversationService conversationService;
    private final SkillService skillService;
    private final SkillManageTool skillManageTool;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final SkillReflectionProperties properties;
    private final ObjectMapper objectMapper;

    /** Per-conversation cooldown tracking. */
    private final ConcurrentHashMap<String, Instant> lastRunTimes = new ConcurrentHashMap<>();

    /** Per-message truncation when building the review transcript. */
    private static final int MESSAGE_TRUNCATE_CHARS = 1200;
    /** Per-skill body truncation when building the catalog. */
    private static final int CATALOG_BODY_TRUNCATE_CHARS = 1200;

    @Async
    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent event) {
        if (event == null) {
            return;
        }
        maybeReflect(event.agentId(), event.conversationId(), event.messageCount());
    }

    /**
     * Decide whether a review should run for this conversation and execute it
     * if the cadence, tool-use floor, and cooldown gates all pass.
     */
    @Async
    public void maybeReflect(Long agentId, String conversationId, int messageCount) {
        if (!properties.isEnabled() || agentId == null || conversationId == null) {
            return;
        }
        // Cadence gate: review every N messages.
        if (properties.getReviewTurnInterval() <= 0
                || messageCount % properties.getReviewTurnInterval() != 0) {
            return;
        }
        if (isInCooldown(conversationId)) {
            log.debug("[SkillReflect] conversation {} in cooldown, skipping", conversationId);
            return;
        }
        try {
            boolean ran = doReflect(agentId, conversationId);
            if (ran) {
                lastRunTimes.put(conversationId, Instant.now());
            }
        } catch (Exception e) {
            log.warn("[SkillReflect] Failed for agent={}, conv={}: {}",
                    agentId, conversationId, e.getMessage());
        }
    }

    /** @return {@code true} when a review actually ran (cooldown should advance). */
    private boolean doReflect(Long agentId, String conversationId) {
        // 1. Load the recent window of the conversation.
        List<MessageEntity> messages = conversationService.listMessages(conversationId);
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        int maxReview = properties.getMaxMessages();
        List<MessageEntity> recent = messages.size() > maxReview
                ? messages.subList(messages.size() - maxReview, messages.size())
                : messages;

        // 2. Substance floor — a window with too few assistant turns rarely
        // yields a reusable skill. Tool calls are not persisted as separate
        // messages, so assistant-turn count is the observable signal.
        long assistantTurns = recent.stream().filter(m -> "assistant".equals(m.getRole())).count();
        if (assistantTurns < properties.getMinAssistantTurns()) {
            log.debug("[SkillReflect] conv {} below assistant-turn floor ({} < {}), skipping",
                    conversationId, assistantTurns, properties.getMinAssistantTurns());
            return false;
        }

        String transcript = buildTranscript(recent);
        if (transcript.isBlank()) {
            return false;
        }
        String skillCatalog = buildSkillCatalog(properties.getCatalogCharBudget());

        // 3. Ask the reviewer for a JSON action plan.
        String llmResponse;
        try {
            String systemPrompt = PromptLoader.loadPrompt("skill/reflect-system");
            String userPrompt = PromptLoader.loadPrompt("skill/reflect-user")
                    .replace("{skills}", skillCatalog.isBlank() ? "(no skills yet)" : skillCatalog)
                    .replace("{transcript}", transcript);
            ChatModel chatModel = buildChatModel();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)));
            llmResponse = callLlmWithRetry(chatModel, prompt, 2);
        } catch (Exception e) {
            log.warn("[SkillReflect] LLM call failed for conv={}: {}", conversationId, e.getMessage());
            return true;
        }
        if (llmResponse == null) {
            return true;
        }

        // 4. Parse and apply the plan via the shared skill_manage safety pipeline.
        JsonNode plan = parseJsonResponse(llmResponse);
        if (plan == null || !plan.isArray() || plan.isEmpty()) {
            log.debug("[SkillReflect] No actions proposed for conv={}", conversationId);
            return true;
        }

        ToolContext toolContext = buildToolContext(agentId, conversationId);
        int applied = 0;
        for (JsonNode action : plan) {
            if (applied >= properties.getMaxActionsPerRun()) {
                log.info("[SkillReflect] Hit maxActionsPerRun={} for conv={}, stopping",
                        properties.getMaxActionsPerRun(), conversationId);
                break;
            }
            if (applyAction(action, toolContext)) {
                applied++;
            }
        }
        if (applied > 0) {
            log.info("[SkillReflect] Applied {} skill action(s) from conv={}", applied, conversationId);
        }
        return true;
    }

    /** Route one planned action through {@link SkillManageTool}. */
    private boolean applyAction(JsonNode action, ToolContext toolContext) {
        String act = action.path("action").asText("").strip().toLowerCase();
        String name = action.path("name").asText("").strip();
        if (act.isBlank() || name.isBlank()) {
            return false;
        }
        // Reflection never deletes — it only creates or improves.
        if (!List.of("create", "edit", "patch").contains(act)) {
            log.debug("[SkillReflect] Ignoring unsupported action '{}'", act);
            return false;
        }
        String content = action.path("content").asText(null);
        String oldText = action.path("oldText").asText(null);
        String newText = action.path("newText").asText(null);
        try {
            String result = skillManageTool.skill_manage(act, name, content, oldText, newText, null, toolContext);
            boolean ok = result != null && !result.startsWith("Error") && !result.startsWith("Security scan BLOCKED");
            if (ok) {
                log.info("[SkillReflect] {} '{}' — {}", act, name,
                        action.path("reason").asText(""));
            } else {
                log.debug("[SkillReflect] {} '{}' rejected: {}", act, name, result);
            }
            return ok;
        } catch (Exception e) {
            log.warn("[SkillReflect] Action {} '{}' threw: {}", act, name, e.getMessage());
            return false;
        }
    }

    /**
     * Build a ToolContext carrying the agent's origin so created skills are
     * stamped with their source conversation (making them curator-eligible
     * under the {@code AGENT_CREATED} scope).
     */
    private ToolContext buildToolContext(Long agentId, String conversationId) {
        ChatOrigin origin = new ChatOrigin(agentId, conversationId, "", null, null,
                null, null, false, null, null, null, null);
        return new ToolContext(Map.of(ChatOrigin.CTX_KEY, origin));
    }

    /** Existing non-builtin skills with truncated bodies, capped to a char budget. */
    private String buildSkillCatalog(int charBudget) {
        List<SkillEntity> skills = skillService.listEnabledSkills();
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SkillEntity skill : skills) {
            if (Boolean.TRUE.equals(skill.getBuiltin())) {
                continue;
            }
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

    private String buildTranscript(List<MessageEntity> messages) {
        StringBuilder sb = new StringBuilder();
        for (MessageEntity msg : messages) {
            String role = msg.getRole();
            String content = msg.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String label = switch (role == null ? "" : role) {
                case "user" -> "User";
                case "assistant" -> "Assistant";
                case "tool" -> "Tool[" + (msg.getToolName() != null ? msg.getToolName() : "unknown") + "]";
                default -> null;
            };
            if (label == null) {
                continue;
            }
            sb.append(label).append(": ").append(truncate(content, MESSAGE_TRUNCATE_CHARS)).append("\n\n");
        }
        return sb.toString().strip();
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity model = null;
        if (properties.getModelId() != null && !properties.getModelId().isBlank()) {
            try {
                model = modelConfigService.getModel(Long.parseLong(properties.getModelId()));
            } catch (Exception e) {
                log.warn("[SkillReflect] Invalid modelId '{}', falling back to default", properties.getModelId());
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
        cleaned = cleaned.strip();
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.debug("[SkillReflect] JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String callLlmWithRetry(ChatModel chatModel, Prompt prompt, int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ChatResponse response = chatModel.call(prompt);
                if (response != null && response.getResult() != null
                        && response.getResult().getOutput() != null) {
                    return response.getResult().getOutput().getText();
                }
                return null;
            } catch (Exception e) {
                if (attempt < maxRetries && isRateLimitError(e)) {
                    long delay = 5000L * (attempt + 1);
                    log.info("[SkillReflect] Rate limited, waiting {}ms before retry ({}/{})",
                            delay, attempt + 1, maxRetries);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
            }
        }
        return null;
    }

    private boolean isRateLimitError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("429") || msg.contains("rate_limit")
                || msg.contains("Too Many Requests"));
    }

    private boolean isInCooldown(String conversationId) {
        Instant lastRun = lastRunTimes.get(conversationId);
        if (lastRun == null) {
            return false;
        }
        long cooldownSeconds = properties.getCooldownMinutes() * 60L;
        return Instant.now().isBefore(lastRun.plusSeconds(cooldownSeconds));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "... [truncated]";
    }
}
