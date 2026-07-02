package vip.mate.memory.service;

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
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.memory.MemoryProperties;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 记忆摘要服务
 * <p>
 * 分析对话内容，提取值得记忆的信息，写入对应的工作区文件。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemorySummarizationService {

    private final ConversationService conversationService;
    private final WorkspaceFileService workspaceFileService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final StructuredMemoryService structuredMemoryService;

    /** Typed-memory categories the summarizer may route entries into. */
    private static final java.util.Set<String> STRUCTURED_TYPES =
            java.util.Set.of("user", "feedback", "project", "reference");

    /** Per-(agent, owner) 锁，防止并发写入 */
    private final ConcurrentHashMap<String, ReentrantLock> agentLocks = new ConcurrentHashMap<>();

    /** Per-(agent, owner) 冷却时间记录 */
    private final ConcurrentHashMap<String, Instant> lastRunTimes = new ConcurrentHashMap<>();

    /** Backwards-compatible entry without an owner key (writes shared memory). */
    public void analyzeAndUpdateMemory(Long agentId, String conversationId) {
        analyzeAndUpdateMemory(agentId, conversationId, null);
    }

    /**
     * 分析对话并更新记忆文件
     *
     * @param agentId        Agent ID
     * @param conversationId 会话 ID
     * @param ownerKey       memory owner this conversation is attributed to; null
     *                       or "system" writes shared (TEAM) memory, otherwise
     *                       memory is written PERSONAL to this owner
     */
    public void analyzeAndUpdateMemory(Long agentId, String conversationId, String ownerKey) {
        // Per-owner isolation is gated on the lifecycle prefetch path, which is
        // the only auto-injector of PERSONAL memory. When that path is off,
        // writing PERSONAL would strand memory in a bucket nothing auto-reads,
        // so fall back to shared (legacy) writes — isolation activates together
        // with lifecycleMediatorEnabled.
        if (!properties.isLifecycleMediatorEnabled()) {
            ownerKey = null;
        }
        // Lock / cooldown are keyed per (agent, owner) so one owner's busy
        // extraction never starves another owner sharing the same agent.
        String lockKey = agentId + ":" + (ownerKey == null ? "" : ownerKey);

        // 冷却检查
        if (isInCooldown(lockKey)) {
            log.debug("[Memory] Agent {} (owner {}) is in cooldown, skipping summarization", agentId, ownerKey);
            return;
        }

        ReentrantLock lock = agentLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("[Memory] Agent {} (owner {}) is already being summarized, skipping", agentId, ownerKey);
            return;
        }

        try {
            doAnalyzeAndUpdate(agentId, conversationId, ownerKey);
            lastRunTimes.put(lockKey, Instant.now());
        } finally {
            lock.unlock();
        }
    }

    private void doAnalyzeAndUpdate(Long agentId, String conversationId, String ownerKey) {
        // 1. 加载对话消息
        List<MessageEntity> messages = conversationService.listMessages(conversationId);
        if (messages.size() < properties.getMinMessagesForSummarize()) {
            log.debug("[Memory] Conversation {} has only {} messages, skipping",
                    conversationId, messages.size());
            return;
        }
        MemorySummarizationGate.Decision decision = MemorySummarizationGate.evaluate(messages);
        if (!decision.shouldAnalyze()) {
            log.info("[Memory] Conversation {} skipped by summarization gate: {}",
                    conversationId, decision.reason());
            return;
        }

        // 2. 加载现有记忆文件内容（按 owner 隔离）
        String profileContent = readFileContentSafe(agentId, "PROFILE.md", ownerKey);
        String memoryContent = readFileContentSafe(agentId, "MEMORY.md", ownerKey);
        String dailyFilename = "memory/" + LocalDate.now() + ".md";
        String dailyContent = readFileContentSafe(agentId, dailyFilename, ownerKey);

        // 3. 构建对话 transcript
        String transcript = buildTranscript(messages);
        if (transcript.isBlank()) {
            return;
        }

        // 4. 调用 LLM 分析
        String systemPrompt = PromptLoader.loadPrompt("memory/summarize-system");
        String userTemplate = PromptLoader.loadPrompt("memory/summarize-user");

        String userPrompt = userTemplate
                .replace("{today}", LocalDate.now().toString())
                .replace("{profile}", profileContent)
                .replace("{memory}", memoryContent)
                .replace("{daily_filename}", dailyFilename)
                .replace("{daily}", dailyContent)
                .replace("{transcript}", transcript);

        String llmResponse;
        try {
            ChatModel chatModel = buildChatModel();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ));
            llmResponse = callLlmWithRetry(chatModel, prompt, 2);
            if (llmResponse == null) {
                log.warn("[Memory] LLM returned null after retries for agent={}, conv={}", agentId, conversationId);
                return;
            }
        } catch (Exception e) {
            log.warn("[Memory] LLM call failed for agent={}, conv={}: {}",
                    agentId, conversationId, e.getMessage());
            return;
        }

        // 5. 解析 JSON 响应
        try {
            JsonNode root = parseJsonResponse(llmResponse);
            if (root == null || !root.path("should_update").asBoolean(false)) {
                String reason = root != null ? root.path("reason").asText("") : "parse failed";
                log.info("[Memory] No update needed for agent={}, conv={}: {}",
                        agentId, conversationId, reason);
                return;
            }

            // 6. 应用更新
            applyUpdates(agentId, root, dailyFilename, dailyContent, ownerKey);

            String reason = root.path("reason").asText("");
            log.info("[Memory] Memory updated for agent={}, conv={}: {}", agentId, conversationId, reason);

        } catch (Exception e) {
            log.warn("[Memory] Failed to parse/apply memory update for agent={}, conv={}: {}",
                    agentId, conversationId, e.getMessage());
        }
    }

    private void applyUpdates(Long agentId, JsonNode root, String dailyFilename,
                              String existingDailyContent, String ownerKey) {
        // Daily entry: 追加模式
        JsonNode dailyNode = root.path("daily_entry");
        if (!dailyNode.isNull() && dailyNode.isTextual()) {
            String entry = dailyNode.asText().trim();
            if (!entry.isEmpty()) {
                String newContent = existingDailyContent.isEmpty()
                        ? "# " + LocalDate.now() + "\n\n" + entry
                        : existingDailyContent + "\n\n" + entry;
                saveMemory(agentId, dailyFilename, newContent, ownerKey);
                log.info("[Memory] Appended daily entry to {} for agent={}, owner={}", dailyFilename, agentId, ownerKey);
            }
        }

        // MEMORY.md: 完整替换
        JsonNode memoryNode = root.path("memory_update");
        if (!memoryNode.isNull() && memoryNode.isTextual()) {
            String content = memoryNode.asText().trim();
            if (!content.isEmpty()) {
                saveMemory(agentId, "MEMORY.md", content, ownerKey);
                log.info("[Memory] Updated MEMORY.md for agent={}, owner={}", agentId, ownerKey);
            }
        }

        // PROFILE.md: 完整替换
        JsonNode profileNode = root.path("profile_update");
        if (!profileNode.isNull() && profileNode.isTextual()) {
            String content = profileNode.asText().trim();
            if (!content.isEmpty()) {
                saveMemory(agentId, "PROFILE.md", content, ownerKey);
                log.info("[Memory] Updated PROFILE.md for agent={}, owner={}", agentId, ownerKey);
            }
        }

        // Structured entries: route typed facts (especially volatile project /
        // reference facts kept out of the always-on MEMORY.md) into structured
        // memory so they become query-conditioned recallable, instead of being
        // stranded in daily notes that only the agent's tools can reach.
        applyStructuredEntries(agentId, root.path("structured_entries"), ownerKey);
    }

    private void applyStructuredEntries(Long agentId, JsonNode entriesNode, String ownerKey) {
        if (entriesNode == null || !entriesNode.isArray() || entriesNode.isEmpty()) {
            return;
        }
        int written = 0;
        for (JsonNode entry : entriesNode) {
            String type = entry.path("type").asText("").trim().toLowerCase();
            String key = entry.path("key").asText("").trim();
            String content = entry.path("content").asText("").trim();
            if (!STRUCTURED_TYPES.contains(type) || key.isEmpty() || content.isEmpty()) {
                log.debug("[Memory] Skipping invalid structured entry (type={}, key={}) for agent={}",
                        type, key, agentId);
                continue;
            }
            try {
                structuredMemoryService.remember(agentId, type, key, content, "auto-summary", ownerKey);
                written++;
            } catch (Exception e) {
                log.warn("[Memory] Failed to write structured entry '{}' (type={}) for agent={}: {}",
                        key, type, agentId, e.getMessage());
            }
        }
        if (written > 0) {
            log.info("[Memory] Routed {} structured entr{} for agent={}",
                    written, written == 1 ? "y" : "ies", agentId);
        }
    }

    private String buildTranscript(List<MessageEntity> messages) {
        int maxMessages = properties.getMaxTranscriptMessages();
        List<MessageEntity> recentMessages = messages.size() > maxMessages
                ? messages.subList(messages.size() - maxMessages, messages.size())
                : messages;

        StringBuilder sb = new StringBuilder();
        for (MessageEntity msg : recentMessages) {
            String role = msg.getRole();
            String content = msg.getContent();
            if (content == null || content.isBlank()) continue;
            // 跳过 tool 和 system 消息，只关注 user/assistant
            if (!"user".equals(role) && !"assistant".equals(role)) continue;

            String label = "user".equals(role) ? "用户" : "助手";
            // 截断过长的单条消息
            if (content.length() > 2000) {
                content = content.substring(0, 2000) + "... [截断]";
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

        // 去除可能的 markdown 代码块标记
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("[Memory] Failed to parse JSON response: {}", e.getMessage());
            log.debug("[Memory] Raw response: {}", response);
            return null;
        }
    }

    /**
     * Read an owner-scoped memory file. When {@code ownerKey} denotes a real
     * owner the row is looked up by (agent, filename, owner); otherwise it falls
     * back to the shared file so cron / system extraction keeps working.
     */
    private String readFileContentSafe(Long agentId, String filename, String ownerKey) {
        try {
            WorkspaceFileEntity file = isPersonal(ownerKey)
                    ? workspaceFileService.getMemoryFile(agentId, filename, ownerKey)
                    : workspaceFileService.getFile(agentId, filename);
            return file != null && file.getContent() != null ? file.getContent() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Persist extracted memory to the owner's PERSONAL bucket, or to the shared
     * (TEAM) file when there is no real owner (cron / system).
     */
    private void saveMemory(Long agentId, String filename, String content, String ownerKey) {
        content = capAlwaysOnFile(filename, content);
        if (isPersonal(ownerKey)) {
            workspaceFileService.saveMemoryFile(agentId, filename, content, ownerKey);
        } else {
            workspaceFileService.saveFile(agentId, filename, content);
        }
    }

    /**
     * Enforce the deterministic size ceiling on the always-on profile/memory files
     * so they cannot grow the per-turn system prompt without bound. Daily notes and
     * other files (only recalled on demand) are left untouched.
     */
    private String capAlwaysOnFile(String filename, String content) {
        if ("PROFILE.md".equals(filename)) {
            return AlwaysOnFileBudget.enforce(content, properties.getProfileMaxChars());
        }
        if ("MEMORY.md".equals(filename)) {
            return AlwaysOnFileBudget.enforce(content, properties.getMemoryMdMaxChars());
        }
        return content;
    }

    /** A real, isolatable owner — i.e. not null/blank and not the system bucket. */
    private boolean isPersonal(String ownerKey) {
        return ownerKey != null && !ownerKey.isBlank()
                && !vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey);
    }

    /**
     * 带轻量重试的 LLM 调用：遇到 429 时等待后重试，避免后台任务因限流直接放弃。
     * Spring AI RetryTemplate 已处理第一层重试，此方法作为二次保护。
     */
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
                    log.info("[Memory] Rate limited, waiting {}ms before retry ({}/{})",
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

    private boolean isInCooldown(String lockKey) {
        Instant lastRun = lastRunTimes.get(lockKey);
        if (lastRun == null) return false;
        long cooldownSeconds = properties.getCooldownMinutes() * 60L;
        return Instant.now().isBefore(lastRun.plusSeconds(cooldownSeconds));
    }
}
