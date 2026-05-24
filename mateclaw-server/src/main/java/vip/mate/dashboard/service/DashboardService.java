package vip.mate.dashboard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Dashboard 统计服务
 * <p>
 * 直接实时查询 mate_message / mate_conversation 表，不依赖预聚合。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;

    /**
     * 获取概览统计（今日/本周/本月）— 实时查询
     */
    public Map<String, Object> getOverview(Long workspaceId) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate monthStart = today.withDayOfMonth(1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", queryStats(workspaceId, today, today));
        result.put("thisWeek", queryStats(workspaceId, weekStart, today));
        result.put("thisMonth", queryStats(workspaceId, monthStart, today));
        return result;
    }

    /**
     * 获取日趋势数据（最近 N 天，按天聚合）
     */
    public List<Map<String, Object>> getTrend(Long workspaceId, int days) {
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            Map<String, Object> dayStats = queryStats(workspaceId, date, date);
            dayStats.put("date", date.toString());
            trend.add(dayStats);
        }
        return trend;
    }

    private Map<String, Object> queryStats(Long workspaceId, LocalDate startDate, LocalDate endDate) {
        LocalDateTime startTime = startDate.atStartOfDay();
        LocalDateTime endTime = endDate.atTime(LocalTime.MAX);

        // 对话数
        LambdaQueryWrapper<ConversationEntity> convWrapper = new LambdaQueryWrapper<ConversationEntity>()
                .ge(ConversationEntity::getCreateTime, startTime)
                .le(ConversationEntity::getCreateTime, endTime);
        if (workspaceId != null) {
            convWrapper.eq(ConversationEntity::getWorkspaceId, workspaceId);
        }
        long conversations = conversationMapper.selectCount(convWrapper);

        // Workspace 级消息过滤：通过 conversation 关联 workspace
        // MessageEntity 没有 workspaceId 字段，需通过所属 conversation 间接过滤
        List<String> wsConversationIds = null;
        if (workspaceId != null) {
            List<ConversationEntity> wsConvs = conversationMapper.selectList(
                    new LambdaQueryWrapper<ConversationEntity>()
                            .eq(ConversationEntity::getWorkspaceId, workspaceId)
                            .select(ConversationEntity::getConversationId));
            wsConversationIds = wsConvs.stream()
                    .map(ConversationEntity::getConversationId).toList();
            if (wsConversationIds.isEmpty()) {
                // 该 workspace 无任何对话，直接返回零值
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("conversations", conversations);
                empty.put("messages", 0L);
                empty.put("totalTokens", 0L);
                empty.put("promptTokens", 0L);
                empty.put("completionTokens", 0L);
                empty.put("toolCalls", 0L);
                return empty;
            }
        }

        // 总消息数
        LambdaQueryWrapper<MessageEntity> msgWrapper = new LambdaQueryWrapper<MessageEntity>()
                .ge(MessageEntity::getCreateTime, startTime)
                .le(MessageEntity::getCreateTime, endTime)
                .eq(MessageEntity::getDeleted, 0);
        if (wsConversationIds != null) {
            msgWrapper.in(MessageEntity::getConversationId, wsConversationIds);
        }
        long messages = messageMapper.selectCount(msgWrapper);

        // Token 统计（assistant 消息）
        LambdaQueryWrapper<MessageEntity> tokenWrapper = new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getRole, "assistant")
                .ge(MessageEntity::getCreateTime, startTime)
                .le(MessageEntity::getCreateTime, endTime)
                .eq(MessageEntity::getDeleted, 0)
                .select(MessageEntity::getPromptTokens, MessageEntity::getCompletionTokens,
                        MessageEntity::getMetadata);
        if (wsConversationIds != null) {
            tokenWrapper.in(MessageEntity::getConversationId, wsConversationIds);
        }

        List<MessageEntity> assistantMessages = messageMapper.selectList(tokenWrapper);

        // Tool calls are not stored as standalone role="tool" rows; each agent
        // turn records them inside the assistant message's metadata JSON
        // (metadata.toolCalls, with metadata.segments[type=tool_call] as the
        // streaming-timeline fallback). Counting role="tool" therefore always
        // returned 0. We reuse the assistant messages already loaded for token
        // accounting and sum the tool-call entries from each row's metadata.
        long totalTokens = 0, promptTokens = 0, completionTokens = 0, toolCalls = 0;
        for (MessageEntity m : assistantMessages) {
            int pt = m.getPromptTokens() != null ? m.getPromptTokens() : 0;
            int ct = m.getCompletionTokens() != null ? m.getCompletionTokens() : 0;
            promptTokens += pt;
            completionTokens += ct;
            totalTokens += pt + ct;
            toolCalls += countToolCalls(m.getMetadata());
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("conversations", conversations);
        stats.put("messages", messages);
        stats.put("totalTokens", totalTokens);
        stats.put("promptTokens", promptTokens);
        stats.put("completionTokens", completionTokens);
        stats.put("toolCalls", toolCalls);
        return stats;
    }

    /**
     * Count tool invocations recorded on a single assistant message.
     * <p>
     * Tool calls live in the message's {@code metadata} JSON, not as separate
     * rows. The canonical list is {@code metadata.toolCalls}; older messages may
     * only carry the streaming timeline, so we fall back to counting
     * {@code metadata.segments} entries whose {@code type} is {@code tool_call}.
     * Parsing failures are treated as zero so a malformed row never breaks the
     * dashboard.
     */
    long countToolCalls(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank() || "{}".equals(metadataJson.trim())) {
            return 0;
        }
        try {
            // H2's JSON column hands the value back as a quoted JSON string
            // literal (double-encoded); MySQL returns the object directly. Mirror
            // MessageVO.parseMetadataToObject and unwrap one string layer first,
            // otherwise readTree yields a TextNode and toolCalls is never found.
            String json = metadataJson.trim();
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = objectMapper.readValue(json, String.class);
            }
            if (json.isBlank() || "{}".equals(json)) {
                return 0;
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode toolCalls = root.get("toolCalls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                return toolCalls.size();
            }
            JsonNode segments = root.get("segments");
            if (segments != null && segments.isArray()) {
                long count = 0;
                for (JsonNode seg : segments) {
                    if ("tool_call".equals(seg.path("type").asText())) {
                        count++;
                    }
                }
                return count;
            }
        } catch (Exception e) {
            log.debug("Failed to parse message metadata for tool-call count: {}", e.getMessage());
        }
        return 0;
    }
}
