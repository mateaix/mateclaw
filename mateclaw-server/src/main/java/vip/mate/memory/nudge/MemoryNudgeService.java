package vip.mate.memory.nudge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.StructuredMemoryService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memory Nudge service — periodically reviews recent conversation turns
 * and extracts structured memory entries (user/feedback/project/reference).
 * <p>
 * Triggered every N turns via ConversationCompletedEvent.
 * Runs async to avoid blocking the user response.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryNudgeService {

    private final ConversationService conversationService;
    private final StructuredMemoryService structuredMemoryService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper;

    /** Per-agent cooldown tracking */
    private final ConcurrentHashMap<Long, Instant> lastNudgeTimes = new ConcurrentHashMap<>();

    /**
     * Check if a nudge should be triggered and execute if so.
     * Called from PostConversationMemoryListener or directly.
     */
    @Async
    public void maybeNudge(Long agentId, String conversationId, int messageCount) {
        if (!properties.isNudgeEnabled()) {
            return;
        }

        // Check turn interval
        if (properties.getNudgeTurnInterval() <= 0
                || messageCount % properties.getNudgeTurnInterval() != 0) {
            return;
        }

        // Cooldown check
        if (isInCooldown(agentId)) {
            log.debug("[Nudge] Agent {} is in cooldown, skipping", agentId);
            return;
        }

        try {
            doNudge(agentId, conversationId);
            lastNudgeTimes.put(agentId, Instant.now());
        } catch (Exception e) {
            log.warn("[Nudge] Failed for agent={}, conv={}: {}",
                    agentId, conversationId, e.getMessage());
        }
    }

    private void doNudge(Long agentId, String conversationId) {
        // 1. Load recent messages
        List<MessageEntity> messages = conversationService.listMessages(conversationId);
        int maxReview = properties.getNudgeMaxMessages();
        List<MessageEntity> recent = messages.size() > maxReview
                ? messages.subList(messages.size() - maxReview, messages.size())
                : messages;

        if (recent.size() < 4) {
            log.debug("[Nudge] Not enough messages to review ({}), skipping", recent.size());
            return;
        }

        // 2. Build transcript
        String transcript = buildTranscript(recent);
        if (transcript.isBlank()) return;

        // 3. Load existing structured memories for dedup
        String existingMemories = structuredMemoryService.buildMemoryBlock(agentId);

        // 4. Build prompt
        String systemPrompt = PromptLoader.loadPrompt("memory/nudge-system");
        String userTemplate = PromptLoader.loadPrompt("memory/nudge-user");
        String userPrompt = userTemplate
                .replace("{transcript}", transcript)
                .replace("{existing_memories}", existingMemories.isBlank() ? "(none)" : existingMemories);

        // 5. Call LLM (with rate limit retry)
        String llmResponse;
        try {
            ChatModel chatModel = buildChatModel();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ));
            llmResponse = callLlmWithRetry(chatModel, prompt, 2);
            if (llmResponse == null) {
                log.warn("[Nudge] LLM returned null after retries for agent={}", agentId);
                return;
            }
        } catch (Exception e) {
            log.warn("[Nudge] LLM call failed for agent={}: {}", agentId, e.getMessage());
            return;
        }

        // 6. Parse and apply
        try {
            JsonNode root = parseJsonResponse(llmResponse);
            if (root == null || !root.isArray()) {
                log.debug("[Nudge] No entries extracted for agent={}", agentId);
                return;
            }

            int saved = 0;
            for (JsonNode entry : root) {
                String type = entry.path("type").asText("");
                String key = entry.path("key").asText("");
                String content = entry.path("content").asText("");
                if (type.isBlank() || key.isBlank() || content.isBlank()) continue;

                try {
                    structuredMemoryService.remember(agentId, type, key, content, "nudge");
                    saved++;
                } catch (Exception e) {
                    log.debug("[Nudge] Failed to save entry {}/{}: {}", type, key, e.getMessage());
                }
            }

            if (saved > 0) {
                log.info("[Nudge] Extracted {} entries for agent={}", saved, agentId);
            }

        } catch (Exception e) {
            log.warn("[Nudge] Failed to parse nudge response for agent={}: {}", agentId, e.getMessage());
        }
    }

    private String buildTranscript(List<MessageEntity> messages) {
        StringBuilder sb = new StringBuilder();
        for (MessageEntity msg : messages) {
            String role = msg.getRole();
            String content = msg.getContent();
            if (content == null || content.isBlank()) continue;
            if (!"user".equals(role) && !"assistant".equals(role)) continue;

            String label = "user".equals(role) ? "User" : "Assistant";
            if (content.length() > 1500) {
                content = content.substring(0, 1500) + "... [truncated]";
            }
            sb.append(label).append(": ").append(content).append("\n\n");
        }
        return sb.toString().trim();
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        return agentGraphBuilder.buildRuntimeChatModel(defaultModel);
    }

    private JsonNode parseJsonResponse(String response) {
        if (response == null || response.isBlank()) return null;
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        cleaned = cleaned.trim();

        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.debug("[Nudge] JSON parse failed: {}", e.getMessage());
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
                    log.info("[Nudge] Rate limited, waiting {}ms before retry ({}/{})",
                            delay, attempt + 1, maxRetries);
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
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
                || msg.contains("速率限制") || msg.contains("Too Many Requests"));
    }

    private boolean isInCooldown(Long agentId) {
        Instant lastRun = lastNudgeTimes.get(agentId);
        if (lastRun == null) return false;
        long cooldownSeconds = properties.getNudgeCooldownMinutes() * 60L;
        return Instant.now().isBefore(lastRun.plusSeconds(cooldownSeconds));
    }
}
