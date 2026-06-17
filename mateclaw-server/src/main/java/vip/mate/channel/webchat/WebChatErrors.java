package vip.mate.channel.webchat;

/**
 * Centralised error codes + messages for the visitor-facing webchat API.
 * <p>
 * Every {@code R.fail(...)} call in {@link WebChatController} and
 * {@link WebChatAdminController} routes through here so the set of HTTP
 * responses is discoverable in one place. Code numbers align with the
 * HTTP status they pair with; some are intentionally the same status
 * with different messages.
 *
 * @author MateClaw Team
 */
public enum WebChatErrors {

    // ---- 400 BAD REQUEST ----
    INVALID_SESSION_ID(400, "Invalid sessionId (allowed: letters, digits, '-', '_', length 1-64)"),
    INVALID_VISITOR_ID(400, "Invalid visitorId (allowed: letters, digits, '-', '_', '.', ':', length 1-128)"),
    TITLE_INVALID(400, "title 不合法（1-100 字）"),
    NO_AGENT(400, "No agent configured for this WebChat channel"),
    REQUESTED_AGENT_NOT_FOUND(400, "Requested agent not found"),
    REQUESTED_AGENT_WRONG_WORKSPACE(400, "Requested agent does not belong to this channel's workspace"),
    PINNED_BODY_REQUIRED(400, "body must contain {pinned: true|false}"),
    ARCHIVE_BODY_REQUIRED(400, "body must contain {archived: true|false}"),
    NO_USER_MESSAGE_TO_REGEN(400, "No user message to regenerate from"),
    VISITOR_ID_REQUIRED(400, "visitorId is required"),
    CHANNEL_AND_VISITOR_REQUIRED(400, "channelId and visitorId are required"),

    // ---- 401 UNAUTHORIZED ----
    INVALID_API_KEY(401, "Invalid API Key"),
    INVALID_VISITOR_TOKEN(401, "Invalid or missing visitor token"),

    // ---- 404 NOT FOUND ----
    SESSION_NOT_FOUND(404, "Session not found"),
    WEBCAT_CHANNEL_NOT_FOUND(404, "webchat channel not found"),

    // ---- 409 CONFLICT ----
    QUOTA_EMPTY_SESSIONS_EXCEEDED(409, "未活跃会话数已达上限（%d），请先发送消息或删除旧会话");

    public final int code;
    public final String message;

    WebChatErrors(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /** Apply an int substitution to messages using {@code %d}. */
    public String with(int arg) {
        return String.format(message, arg);
    }
}
