package vip.mate.skill.synthesis;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.SkillSecurityService;
import vip.mate.skill.runtime.SkillValidationResult;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RFC-023: 从对话历史蒸馏 SKILL.md 的服务
 * <p>
 * 供 {@code POST /api/v1/skills/synthesize-from-conversation} 和前端"建议保存 Skill"流程调用。
 * 与 {@code SkillManageTool}（Agent 自治路径）互补——本服务是"用户主动触发"路径。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillSynthesisService {

    private final MessageMapper messageMapper;
    private final SkillService skillService;
    private final SkillSecurityService securityService;
    private final SkillWorkspaceManager workspaceManager;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final SkillSynthesisProperties properties;

    private static final RetryTemplate NO_RETRY = RetryTemplate.builder().maxAttempts(1).build();

    /**
     * 从对话历史合成 Skill
     *
     * @param conversationId 源对话 ID
     * @param agentId        Agent ID（用于记录来源）
     * @param workspaceId    目标工作区 ID（决定新 Skill 的归属）
     * @return 合成结果（包含 skillId、name、status）
     */
    public SynthesisResult synthesize(String conversationId, Long agentId, Long workspaceId) {
        // 1. 读取对话历史
        List<MessageEntity> messages = messageMapper.selectList(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId)
                        .orderByAsc(MessageEntity::getCreateTime));
        if (messages.isEmpty()) {
            return SynthesisResult.failed("No messages found for conversation " + conversationId);
        }

        // 2. 压缩对话为 LLM 输入（纯规则，不用 LLM）
        String condensed = condenseConversation(messages);
        if (condensed.length() < 100) {
            return SynthesisResult.failed("Conversation too short to synthesize a meaningful skill");
        }

        // 3. 调 LLM 生成 SKILL.md
        String skillMd;
        try {
            skillMd = callLlm(condensed);
        } catch (Exception e) {
            log.error("[SkillSynthesis] LLM call failed for conversation={}: {}", conversationId, e.getMessage(), e);
            return SynthesisResult.failed("LLM call failed: " + e.getMessage());
        }

        if (skillMd == null || skillMd.isBlank()) {
            return SynthesisResult.failed("LLM returned empty content");
        }

        // 4. 提取名称
        String name = extractFrontmatterValue(skillMd, "name");
        if (name == null || name.isBlank()) {
            name = "auto-skill-" + System.currentTimeMillis();
        }
        name = name.strip().toLowerCase().replaceAll("[^a-z0-9._-]", "-");

        // 去重
        SkillEntity existing = skillService.findByName(name);
        if (existing != null) {
            name = name + "-" + (System.currentTimeMillis() % 10000);
        }

        // 5. 安全扫描
        SkillValidationResult scanResult = securityService.scanContent(skillMd, name);
        String scanStatus = scanResult.isBlocked() ? "FAILED" : "PASSED";

        if (scanResult.isBlocked()) {
            log.warn("[SkillSynthesis] Security scan BLOCKED synthesized skill '{}': {}", name, scanResult.getSummary());
            return SynthesisResult.blocked(name, scanResult.getSummary());
        }

        // 6. 保存
        try {
            SkillEntity skill = new SkillEntity();
            skill.setName(name);
            skill.setDescription(extractFrontmatterValue(skillMd, "description"));
            skill.setSkillType("custom");
            skill.setSkillContent(skillMd);
            skill.setEnabled(true);
            skill.setBuiltin(false);
            skill.setVersion(extractFrontmatterValue(skillMd, "version"));
            skill.setSourceConversationId(conversationId);
            skill.setSecurityScanStatus(scanStatus);
            skill.setWorkspaceId(workspaceId);

            skillService.createSkill(skill);

            try {
                workspaceManager.exportToWorkspace(name, skillMd);
            } catch (Exception e) {
                log.warn("[SkillSynthesis] Workspace export failed for '{}': {}", name, e.getMessage());
            }

            log.info("[SkillSynthesis] Synthesized skill '{}' from conversation={}, agentId={}", name, conversationId, agentId);
            return SynthesisResult.success(skill.getId(), name);
        } catch (Exception e) {
            log.error("[SkillSynthesis] Failed to save skill '{}': {}", name, e.getMessage(), e);
            return SynthesisResult.failed("Save failed: " + e.getMessage());
        }
    }

    /**
     * 统计对话中的工具调用数（用于建议器的阈值判断）
     */
    public int countToolCalls(String conversationId) {
        Long count = messageMapper.selectCount(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId)
                        .eq(MessageEntity::getRole, "tool"));
        return count != null ? count.intValue() : 0;
    }

    // ==================== 内部方法 ====================

    /**
     * 把对话历史压缩为 LLM 可消费的摘要（纯规则，不调 LLM）
     */
    private String condenseConversation(List<MessageEntity> messages) {
        StringBuilder sb = new StringBuilder();
        int maxLen = 12000; // 控制在 ~3K tokens

        for (MessageEntity msg : messages) {
            if (sb.length() > maxLen) {
                sb.append("\n... (truncated, ").append(messages.size() - messages.indexOf(msg)).append(" messages remaining)");
                break;
            }

            String role = msg.getRole();
            String content = msg.getContent();
            if (content == null || content.isBlank()) continue;

            switch (role) {
                case "user" -> {
                    sb.append("\n### User:\n");
                    sb.append(truncate(content, 500));
                }
                case "assistant" -> {
                    sb.append("\n### Assistant:\n");
                    sb.append(truncate(content, 800));
                }
                case "tool" -> {
                    sb.append("\n### Tool [").append(msg.getToolName() != null ? msg.getToolName() : "unknown").append("]:\n");
                    // 工具结果只保留前 300 字符（通常很长）
                    sb.append(truncate(content, 300));
                }
                // system messages 跳过
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String callLlm(String condensed) {
        String systemPrompt = PromptLoader.loadPrompt("skill/synthesize-system");
        String userTemplate = PromptLoader.loadPrompt("skill/synthesize-user");
        String userPrompt = userTemplate.replace("{conversation}", condensed);

        ChatModel chatModel = buildChatModel();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)));

        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        String text = response.getResult().getOutput().getText();

        // 剥离 markdown 代码块
        if (text != null) {
            text = text.strip();
            if (text.startsWith("```")) {
                int firstNewline = text.indexOf('\n');
                if (firstNewline > 0) text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).strip();
            }
        }
        return text;
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity model = null;
        if (properties.getModelId() != null && !properties.getModelId().isBlank()) {
            try {
                model = modelConfigService.getModel(Long.parseLong(properties.getModelId()));
            } catch (Exception e) {
                log.warn("[SkillSynthesis] Invalid modelId '{}', falling back to default", properties.getModelId());
            }
        }
        if (model == null) {
            model = modelConfigService.getDefaultModel();
        }
        return agentGraphBuilder.buildRuntimeChatModel(model, NO_RETRY);
    }

    private String extractFrontmatterValue(String content, String key) {
        if (content == null || !content.startsWith("---")) return null;
        int endIdx = content.indexOf("---", 3);
        if (endIdx < 0) return null;
        String frontmatter = content.substring(3, endIdx);
        for (String line : frontmatter.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(key + ":")) {
                String value = trimmed.substring(key.length() + 1).strip();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== 结果 DTO ====================

    public record SynthesisResult(
            boolean success,
            boolean blocked,
            Long skillId,
            String skillName,
            String error,
            String scanSummary
    ) {
        public static SynthesisResult success(Long id, String name) {
            return new SynthesisResult(true, false, id, name, null, null);
        }

        public static SynthesisResult failed(String error) {
            return new SynthesisResult(false, false, null, null, error, null);
        }

        public static SynthesisResult blocked(String name, String scanSummary) {
            return new SynthesisResult(false, true, null, name, "Security scan blocked", scanSummary);
        }
    }
}
