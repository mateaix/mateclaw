package vip.mate.tool.builtin;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import vip.mate.agent.context.ChatOrigin;

/**
 * 工具执行上下文 — 通过 ThreadLocal 向 @Tool 方法传递执行环境信息
 * <p>
 * 在 ToolExecutionExecutor.executeSingleTool() 中 set，在 finally 中 clear。
 * 视频生成等需要知道 conversationId 的工具从此处获取。
 *
 * <p>RFC-063r §2.5 兼容期：执行器会同时填充本 ThreadLocal 和 Spring AI 的
 * {@link ToolContext}（携带 {@link ChatOrigin}）。优先读 ToolContext 的工具
 * 调用 {@link #conversationId(ToolContext)} / {@link #username(ToolContext)}
 * / {@link #workspaceBasePath(ToolContext)} 等三参重载即可——传入 ctx 不为
 * null 时优先返回 origin 的字段，否则回退到 ThreadLocal。
 *
 * @author MateClaw Team
 */
public final class ToolExecutionContext {

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    /** 工作区活动目录（为空不限制） */
    private static final ThreadLocal<String> WORKSPACE_BASE_PATH = new ThreadLocal<>();

    private ToolExecutionContext() {}

    public static void set(String conversationId, String username) {
        CONVERSATION_ID.set(conversationId);
        USERNAME.set(username);
        WORKSPACE_BASE_PATH.remove();
    }

    public static void set(String conversationId, String username, String workspaceBasePath) {
        CONVERSATION_ID.set(conversationId);
        USERNAME.set(username);
        WORKSPACE_BASE_PATH.set(workspaceBasePath);
    }

    public static String conversationId() {
        return CONVERSATION_ID.get();
    }

    public static String username() {
        return USERNAME.get();
    }

    /** 获取当前工作区活动目录，为 null 表示不限制 */
    public static String workspaceBasePath() {
        return WORKSPACE_BASE_PATH.get();
    }

    public static void clear() {
        CONVERSATION_ID.remove();
        USERNAME.remove();
        WORKSPACE_BASE_PATH.remove();
    }

    // ===== RFC-063r §2.5: ToolContext-aware accessors =====
    //
    // Preferred over the parameter-less variants: read from the explicit
    // Spring AI ToolContext (carries ChatOrigin) when available, otherwise
    // fall back to the legacy ThreadLocal so legacy paths keep working.

    public static String conversationId(@Nullable ToolContext ctx) {
        if (ctx != null) {
            String v = ChatOrigin.from(ctx).conversationId();
            if (v != null && !v.isEmpty()) return v;
        }
        return CONVERSATION_ID.get();
    }

    public static String username(@Nullable ToolContext ctx) {
        if (ctx != null) {
            String v = ChatOrigin.from(ctx).requesterId();
            if (v != null && !v.isEmpty()) return v;
        }
        return USERNAME.get();
    }

    public static String workspaceBasePath(@Nullable ToolContext ctx) {
        if (ctx != null) {
            String v = ChatOrigin.from(ctx).workspaceBasePath();
            if (v != null && !v.isBlank()) return v;
        }
        return WORKSPACE_BASE_PATH.get();
    }

    public static Long originMessageId(@Nullable ToolContext ctx) {
        return ctx == null ? null : ChatOrigin.from(ctx).originMessageId();
    }
}
