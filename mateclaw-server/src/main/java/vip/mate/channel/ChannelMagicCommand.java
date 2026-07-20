package vip.mate.channel;

import java.util.Locale;
import java.util.Set;

/**
 * User-typed channel control commands that should be handled by the platform
 * instead of being sent to the agent as normal prompt text.
 */
final class ChannelMagicCommand {

    private static final Set<String> CLEAR_COMMANDS = Set.of(
            "clear",
            "/clear",
            "reset",
            "/reset",
            "清空",
            "/清空",
            "清空上下文",
            "/清空上下文",
            "清理上下文",
            "/清理上下文",
            "清除上下文",
            "/清除上下文",
            "重置上下文",
            "/重置上下文"
    );

    private ChannelMagicCommand() {
    }

    static boolean isClearCommand(String text) {
        String normalized = normalize(text);
        return !normalized.isEmpty() && CLEAR_COMMANDS.contains(normalized);
    }

    static String clearConfirmation() {
        return "✅ 上下文已清理，后续消息会从新的上下文开始。";
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }
}
