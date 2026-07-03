package vip.mate.workspace.conversation.vo;

import lombok.Data;

import java.util.List;

/**
 * Token Usage 聚合汇总 VO
 *
 * @author MateClaw Team
 */
@Data
public class TokenUsageSummaryVO {

    /** 总 prompt tokens */
    private long totalPromptTokens;

    /** 总 completion tokens */
    private long totalCompletionTokens;

    /** 总 prompt cache 命中 tokens */
    private long totalCacheReadTokens;

    /** 总 prompt cache 写入 tokens */
    private long totalCacheWriteTokens;

    /** 总思考（reasoning）tokens */
    private long totalReasoningTokens;

    /** 总 assistant 消息数 */
    private long totalMessages;

    /** 按模型聚合 */
    private List<ModelUsageItem> byModel;

    /** 按日期聚合 */
    private List<DateUsageItem> byDate;

    @Data
    public static class ModelUsageItem {
        private String runtimeModel;
        private String runtimeProvider;
        private long promptTokens;
        private long completionTokens;
        private long messageCount;
    }

    @Data
    public static class DateUsageItem {
        private String date;
        private long promptTokens;
        private long completionTokens;
        private long messageCount;
    }
}
