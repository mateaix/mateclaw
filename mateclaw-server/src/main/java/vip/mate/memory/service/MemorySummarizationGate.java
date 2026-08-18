package vip.mate.memory.service;

import vip.mate.workspace.conversation.MessageMetadataJson;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters conversations that should not be promoted into long-term memory.
 */
final class MemorySummarizationGate {

    private static final Pattern FINISH_REASON = Pattern.compile(
            "\"(?:finishReason|finish_reason)\"\\s*:\\s*\"([^\"]+)\"");

    private MemorySummarizationGate() {
    }

    static Decision evaluate(List<MessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return Decision.skip("empty conversation");
        }

        String latestUser = latestContent(messages, "user");
        String latestAssistant = latestContent(messages, "assistant");
        String latestAssistantMetadata = latestMetadata(messages, "assistant");
        String finishReason = extractFinishReason(latestAssistantMetadata);
        if (isNonDurableFinishReason(finishReason)) {
            return Decision.skip("finishReason=" + finishReason + " is not durable memory input");
        }
        if (looksIncompleteOrUnsupported(latestAssistant)) {
            return Decision.skip("assistant content is incomplete or evidence-insufficient");
        }

        if (isExplicitRememberRequest(latestUser)) {
            return Decision.analyze();
        }

        if (looksLikeSourceAnalysis(latestUser)) {
            return Decision.skip("source-analysis conversations are one-off work, not long-term memory");
        }

        return Decision.analyze();
    }

    private static boolean isNonDurableFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isBlank()) {
            return false;
        }
        return switch (finishReason) {
            case "normal", "return_direct" -> false;
            default -> true;
        };
    }

    private static boolean isExplicitRememberRequest(String text) {
        String normalized = normalize(text);
        return normalized.contains("记住") || normalized.contains("remember")
                || normalized.contains("保存到记忆") || normalized.contains("写入记忆");
    }

    private static boolean looksLikeSourceAnalysis(String text) {
        String normalized = normalize(text);
        boolean sourceIntent = normalized.contains("源码") || normalized.contains("代码")
                || normalized.contains("review") || normalized.contains("日志")
                || normalized.contains("debug") || normalized.contains("排查");
        boolean analysisIntent = normalized.contains("分析") || normalized.contains("检查")
                || normalized.contains("看看") || normalized.contains("修复")
                || normalized.contains("问题") || normalized.contains("待修复");
        return sourceIntent && analysisIntent;
    }

    private static boolean looksIncompleteOrUnsupported(String text) {
        String normalized = normalize(text);
        return normalized.contains("[证据不足]") || normalized.contains("证据不足")
                || normalized.contains("failed to generate a response")
                || normalized.contains("error_fallback")
                || normalized.contains("未确认")
                || normalized.contains("无法确认");
    }

    private static String latestContent(List<MessageEntity> messages, String role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageEntity message = messages.get(i);
            if (role.equals(message.getRole()) && message.getContent() != null) {
                return message.getContent();
            }
        }
        return "";
    }

    private static String latestMetadata(List<MessageEntity> messages, String role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageEntity message = messages.get(i);
            if (role.equals(message.getRole()) && message.getMetadata() != null) {
                return message.getMetadata();
            }
        }
        return "";
    }

    private static String extractFinishReason(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        // The pattern matches `"finishReason":"x"`, which the escaped form
        // (`\"finishReason\":\"x\"`) does not contain — the gate would then see
        // no reason at all and promote incomplete / stopped / errored turns
        // into long-term memory, the exact guess-from-text behaviour the
        // structured field exists to avoid.
        Matcher matcher = FINISH_REASON.matcher(MessageMetadataJson.normalize(metadata));
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    record Decision(boolean shouldAnalyze, String reason) {
        static Decision analyze() {
            return new Decision(true, "eligible");
        }

        static Decision skip(String reason) {
            return new Decision(false, reason);
        }
    }
}
