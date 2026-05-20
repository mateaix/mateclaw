package vip.mate.agent.context;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 运行时上下文注入器 — 在 LLM 消息列表中预注入当前时间等运行时信息。
 * <p>
 * 参考 Claude Code 的 prependUserContext 模式：将时间信息作为首条 meta UserMessage 注入，
 * 而非修改 System Prompt，以保持 prompt cache 命中率。
 * <p>
 * <b>RFC-014 协同要点</b>：本类返回的内容必须始终包装为 {@code UserMessage} 注入，
 * 严禁拼接进 SystemMessage —— 否则每次时间变化都会击穿 spring-ai 的
 * {@code AnthropicCacheStrategy.SYSTEM_AND_TOOLS / CONVERSATION_HISTORY} system cache。
 * 内容长度（约 70 字符）远小于 spring-ai 的 USER min-content-length 阈值（≥1024 字符），
 * 因此不会被错误纳入对话 cache 块。新增调用点时请保持此约束。
 */
public final class RuntimeContextInjector {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private RuntimeContextInjector() {
    }

    /**
     * 构建运行时上下文消息，包含当前日期和时间。
     */
    public static String buildContextMessage() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        return "[system-context] Current time: " + now.format(DATE_FMT)
                + " " + now.format(TIME_FMT) + " (Asia/Shanghai)";
    }

    /**
     * 构建运行时上下文消息，包含当前日期、时间和工作目录。
     * 使用 I18nService 解析本地化消息。
     */
    public static String buildContextMessage(String workspaceBasePath) {
        return buildContextMessage(workspaceBasePath, null);
    }

    /**
     * 构建运行时上下文消息（i18n 版本）。
     */
    public static String buildContextMessage(String workspaceBasePath, vip.mate.i18n.I18nService i18n) {
        return buildContextMessage(workspaceBasePath, i18n, null);
    }

    /**
     * Build the runtime-context message and (when {@code origin} is non-null
     * and carries IM channel context) append a short "who is talking, where,
     * via what channel" block so the agent's system prompt can personalise
     * its reply. Same cache discipline as the simpler overloads — the block
     * stays well under the spring-ai user-cache threshold (≥1024 chars).
     *
     * <p>The sender block is suppressed when:
     * <ul>
     *   <li>{@code origin} is null or {@link ChatOrigin#EMPTY}</li>
     *   <li>the origin carries no IM context (web / cron) — both produce
     *       a null {@code channelType} or {@code "web"}</li>
     * </ul>
     * Web and cron callers thus see exactly the same prompt as before.
     */
    public static String buildContextMessage(String workspaceBasePath,
                                              vip.mate.i18n.I18nService i18n,
                                              ChatOrigin origin) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        String dateStr = now.format(DATE_FMT);
        String timeStr = now.format(TIME_FMT);

        StringBuilder sb = new StringBuilder();
        if (i18n != null) {
            sb.append(i18n.msg("context.current_time", dateStr, timeStr));
        } else {
            sb.append("[system-context] Current time: ").append(dateStr)
              .append(" ").append(timeStr).append(" (Asia/Shanghai)");
        }

        if (workspaceBasePath != null && !workspaceBasePath.isBlank()) {
            if (i18n != null) {
                sb.append("\n").append(i18n.msg("context.working_dir", workspaceBasePath));
                sb.append("\n").append(i18n.msg("context.working_dir_hint"));
            } else {
                sb.append("\n[system-context] Working directory: ").append(workspaceBasePath);
                sb.append("\nYou can only read/write files and execute commands within this directory and its subdirectories.");
            }
        }

        appendSenderBlockIfPresent(sb, origin);
        return sb.toString();
    }

    /**
     * Append a sender / channel / chat block when the origin carries
     * meaningful IM context. Format is intentionally one line per
     * fact so it's both LLM-readable and easy to log-grep.
     */
    private static void appendSenderBlockIfPresent(StringBuilder sb, ChatOrigin origin) {
        if (origin == null || origin == ChatOrigin.EMPTY) return;
        String channelType = origin.channelType();
        // Only inject for real IM channels — web / null / cron should
        // see the previous prompt verbatim so their cache hit rate
        // and existing eval baselines don't shift.
        if (channelType == null || channelType.isBlank()
                || "web".equalsIgnoreCase(channelType)
                || origin.cronOrigin()) {
            return;
        }
        sb.append("\n[system-context] Channel: ").append(channelType);
        if (origin.senderName() != null && !origin.senderName().isBlank()) {
            sb.append("\n[system-context] Sender: ").append(origin.senderName());
        }
        if (origin.requesterId() != null && !origin.requesterId().isBlank()) {
            sb.append(" (id=").append(origin.requesterId()).append(')');
        }
        if (origin.chatId() != null && !origin.chatId().isBlank()) {
            sb.append("\n[system-context] Chat: ").append(origin.chatId())
              .append(" (group conversation — multiple users may follow up)");
        }
    }
}
