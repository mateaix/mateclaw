package vip.mate.workspace.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.conversation.vo.TokenUsageSummaryVO;
import vip.mate.workspace.conversation.vo.TokenUsageSummaryVO.DateUsageItem;
import vip.mate.workspace.conversation.vo.TokenUsageSummaryVO.ModelUsageItem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Token Usage 统计服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final MessageMapper messageMapper;

    /**
     * 查询指定时间范围内的 token 使用汇总
     */
    public TokenUsageSummaryVO getSummary(LocalDate startDate, LocalDate endDate,
                                          String modelName, String providerId) {
        // 自动交换
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            LocalDate tmp = startDate;
            startDate = endDate;
            endDate = tmp;
        }

        // 默认值
        if (endDate == null) endDate = LocalDate.now();
        if (startDate == null) startDate = endDate.minusDays(30);

        // 查询 assistant 消息，且有 token 数据
        LocalDateTime startTime = startDate.atStartOfDay();
        LocalDateTime endTime = endDate.atTime(LocalTime.MAX);

        LambdaQueryWrapper<MessageEntity> wrapper = new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getRole, "assistant")
                .ge(MessageEntity::getCreateTime, startTime)
                .le(MessageEntity::getCreateTime, endTime)
                .and(w -> w
                        .isNotNull(MessageEntity::getTokenUsage)
                        .or()
                        .gt(MessageEntity::getPromptTokens, 0)
                        .or()
                        .gt(MessageEntity::getCompletionTokens, 0))
                .eq(MessageEntity::getDeleted, 0);

        if (modelName != null && !modelName.isBlank()) {
            wrapper.eq(MessageEntity::getRuntimeModel, modelName);
        }
        if (providerId != null && !providerId.isBlank()) {
            wrapper.eq(MessageEntity::getRuntimeProvider, providerId);
        }

        // 只查需要的列
        wrapper.select(
                MessageEntity::getPromptTokens,
                MessageEntity::getCompletionTokens,
                MessageEntity::getCacheReadTokens,
                MessageEntity::getCacheWriteTokens,
                MessageEntity::getReasoningTokens,
                MessageEntity::getRuntimeModel,
                MessageEntity::getRuntimeProvider,
                MessageEntity::getCreateTime
        );

        List<MessageEntity> messages = messageMapper.selectList(wrapper);

        return buildSummary(messages);
    }

    private TokenUsageSummaryVO buildSummary(List<MessageEntity> messages) {
        TokenUsageSummaryVO vo = new TokenUsageSummaryVO();

        long totalPrompt = 0;
        long totalCompletion = 0;
        long totalCacheRead = 0;
        long totalCacheWrite = 0;
        long totalReasoning = 0;

        // 按模型聚合
        Map<String, long[]> modelMap = new LinkedHashMap<>();
        // 按日期聚合
        Map<String, long[]> dateMap = new TreeMap<>();

        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (MessageEntity msg : messages) {
            int prompt = msg.getPromptTokens() != null ? msg.getPromptTokens() : 0;
            int completion = msg.getCompletionTokens() != null ? msg.getCompletionTokens() : 0;
            totalPrompt += prompt;
            totalCompletion += completion;
            totalCacheRead += msg.getCacheReadTokens() != null ? msg.getCacheReadTokens() : 0;
            totalCacheWrite += msg.getCacheWriteTokens() != null ? msg.getCacheWriteTokens() : 0;
            totalReasoning += msg.getReasoningTokens() != null ? msg.getReasoningTokens() : 0;

            // 模型维度
            String model = msg.getRuntimeModel() != null ? msg.getRuntimeModel() : "unknown";
            String provider = msg.getRuntimeProvider() != null ? msg.getRuntimeProvider() : "";
            String modelKey = provider + "|" + model;
            modelMap.computeIfAbsent(modelKey, k -> new long[]{0, 0, 0});
            long[] modelStats = modelMap.get(modelKey);
            modelStats[0] += prompt;
            modelStats[1] += completion;
            modelStats[2]++;

            // 日期维度
            String dateKey = msg.getCreateTime() != null
                    ? msg.getCreateTime().format(dateFmt)
                    : "unknown";
            dateMap.computeIfAbsent(dateKey, k -> new long[]{0, 0, 0});
            long[] dateStats = dateMap.get(dateKey);
            dateStats[0] += prompt;
            dateStats[1] += completion;
            dateStats[2]++;
        }

        vo.setTotalPromptTokens(totalPrompt);
        vo.setTotalCompletionTokens(totalCompletion);
        vo.setTotalCacheReadTokens(totalCacheRead);
        vo.setTotalCacheWriteTokens(totalCacheWrite);
        vo.setTotalReasoningTokens(totalReasoning);
        vo.setTotalMessages(messages.size());

        // 转换 byModel
        vo.setByModel(modelMap.entrySet().stream().map(e -> {
            String[] parts = e.getKey().split("\\|", 2);
            long[] stats = e.getValue();
            ModelUsageItem item = new ModelUsageItem();
            item.setRuntimeProvider(parts[0]);
            item.setRuntimeModel(parts.length > 1 ? parts[1] : "");
            item.setPromptTokens(stats[0]);
            item.setCompletionTokens(stats[1]);
            item.setMessageCount(stats[2]);
            return item;
        }).collect(Collectors.toList()));

        // 转换 byDate
        vo.setByDate(dateMap.entrySet().stream().map(e -> {
            long[] stats = e.getValue();
            DateUsageItem item = new DateUsageItem();
            item.setDate(e.getKey());
            item.setPromptTokens(stats[0]);
            item.setCompletionTokens(stats[1]);
            item.setMessageCount(stats[2]);
            return item;
        }).collect(Collectors.toList()));

        return vo;
    }
}
