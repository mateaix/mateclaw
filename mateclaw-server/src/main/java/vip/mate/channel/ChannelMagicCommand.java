package vip.mate.channel;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * User-typed channel control commands that should be handled by the platform
 * instead of being sent to the agent as normal prompt text.
 * <p>
 * Matching rules:
 * <ul>
 *   <li>Case-insensitive; the whole message is trimmed first.</li>
 *   <li>Bare aliases (no leading "/") match only when the entire message is
 *       exactly the alias — "clear 一下北京天气" is normal prompt text.</li>
 *   <li>Slash-prefixed aliases may carry trailing arguments after the first
 *       whitespace; the remainder is passed through verbatim as args.</li>
 * </ul>
 */
final class ChannelMagicCommand {

    /** Platform-level command kinds, dispatched by {@link ChannelMessageRouter}. */
    enum Type { CLEAR, NEW, HELP, STATUS, STOP }

    /** A recognized command plus its raw (possibly empty) argument string. */
    record Parsed(Type type, String args) {
    }

    /**
     * Alias token → command type. LinkedHashMap keeps registration ordering
     * stable. Every bare alias also registers its "/"-prefixed twin.
     */
    private static final Map<String, Type> ALIASES = buildAliases();

    private ChannelMagicCommand() {
    }

    static Optional<Parsed> parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        Type wholeMatch = ALIASES.get(lower);
        if (wholeMatch != null) {
            return Optional.of(new Parsed(wholeMatch, ""));
        }
        // Only slash-prefixed commands may carry arguments; bare words with a
        // trailing remainder are ordinary prompts, never commands.
        if (!lower.startsWith("/")) {
            return Optional.empty();
        }
        int ws = indexOfWhitespace(lower);
        if (ws < 0) {
            return Optional.empty();
        }
        Type type = ALIASES.get(lower.substring(0, ws));
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(type, trimmed.substring(ws).trim()));
    }

    static String clearConfirmation() {
        return "✅ 上下文已清理，后续消息会从新的上下文开始。";
    }

    static String newConfirmation() {
        return "✨ 已开启新会话，之前的上下文不会带入。";
    }

    static String stopConfirmation() {
        return "⏹️ 已停止当前任务。";
    }

    static String stopNothingRunning() {
        return "当前没有进行中的任务。";
    }

    static String helpText() {
        return """
                🪄 可用命令：
                /clear — 清空当前会话上下文（别名：/reset、清空上下文）
                /new — 开启新会话（别名：新会话）
                /stop — 停止当前进行中的任务（别名：停止）
                /status — 查看当前会话状态（别名：状态）
                /help — 显示本帮助（别名：帮助）""";
    }

    private static Map<String, Type> buildAliases() {
        Map<String, Type> aliases = new LinkedHashMap<>();
        register(aliases, Type.CLEAR,
                "clear", "reset",
                "清空", "清空上下文", "清理上下文", "清除上下文", "重置上下文");
        register(aliases, Type.NEW,
                "new", "新会话", "新对话");
        register(aliases, Type.HELP,
                "help", "帮助");
        register(aliases, Type.STATUS,
                "status", "状态");
        register(aliases, Type.STOP,
                "stop", "停止");
        return aliases;
    }

    private static void register(Map<String, Type> aliases, Type type, String... names) {
        for (String name : names) {
            aliases.put(name, type);
            aliases.put("/" + name, type);
        }
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
