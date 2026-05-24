package vip.mate.channel.wecom.cards.tool_guard;

import lombok.extern.slf4j.Slf4j;
import vip.mate.approval.ApprovalService;
import vip.mate.approval.PendingApproval;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.wecom.WeComChannelAdapter;
import vip.mate.channel.wecom.cards.WeComCardHandler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Process an inbound {@code template_card_event} frame for a tool-guard
 * approval card.
 *
 * <p><b>Step ordering — validate before render</b> (RFC-32 v2.1 / R-5):
 * v2.0's draft step order was "render resolved card → inject command →
 * router validates identity". That meant a non-original-requester click
 * would briefly show "✅ 已批准 by 李四" on the card before the router
 * silently dropped the injected command. v2.1 reorders to:
 * <ol>
 *   <li>Decode {@code event_key} → null check</li>
 *   <li>Look up {@code PendingApproval} by id</li>
 *   <li>Identity check: pending.userId vs clicker</li>
 *   <li>Render the appropriate resolved-state card (success / unauthorized / expired)</li>
 *   <li>Inject {@code /approve} or {@code /deny} command into the router
 *       — only for authorised clicks</li>
 * </ol>
 *
 * <p>Steps 1-4 must complete inside the WeCom 5-second window for the
 * card update; step 5 can be slower.
 */
@Slf4j
public class ToolGuardCardHandler implements WeComCardHandler {

    private final ApprovalService approvalService;
    private final ToolGuardButtonKey buttonKey;

    public ToolGuardCardHandler(ApprovalService approvalService, ToolGuardButtonKey buttonKey) {
        this.approvalService = approvalService;
        this.buttonKey = buttonKey;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void handle(WeComChannelAdapter adapter,
                       Map<String, Object> frame,
                       Map<String, Object> tce,
                       Map<String, Object> fromBlock) {
        String eventReqId = extractEventReqId(frame);
        String taskId = (String) tce.getOrDefault("task_id", "");
        String eventKey = (String) tce.getOrDefault("event_key", "");

        // ---- 1. Decode event_key ----
        ToolGuardButtonKey.Decoded decoded = buttonKey.decode(eventKey);
        if (decoded == null) {
            log.warn("[wecom-toolguard] Could not decode event_key, ignoring task_id={}", taskId);
            return;
        }
        String pendingId = decoded.pendingId();
        ToolGuardButtonKey.Action action = decoded.action();
        String clickerUserId = stringOrEmpty(fromBlock.get("userid"));

        // ---- 2. Look up pending approval ----
        Optional<PendingApproval> opt = approvalService.getPending(pendingId);
        if (opt.isEmpty() || !"pending".equals(opt.get().getStatus())) {
            // Either: GC'd it / approved/denied via another path / never existed
            log.info("[wecom-toolguard] Pending {} not found or already resolved (action={}, clicker={})",
                    pendingId, action, abbrev(clickerUserId));
            renderExpired(adapter, eventReqId, taskId, decoded.toolName());
            return;
        }
        PendingApproval pending = opt.get();

        // ---- 3. Identity check (fail-closed) ----
        // Agent/cron ("system") or unattributed (null) approvals have no human
        // requester to match the clicker against; a group card would let any
        // member resolve a guarded action. Reject here (mirrors the feishu card
        // handler + router) so we never renderResolved a click the router will
        // then refuse to execute. These approvals go through the admin console.
        String originalRequester = pending.getUserId();
        boolean isAuthorized = originalRequester != null
                && !"system".equals(originalRequester)
                && originalRequester.equals(clickerUserId);
        if (!isAuthorized) {
            log.warn("[wecom-toolguard] Unauthorised click: clicker={} != requester={}, pending={}",
                    abbrev(clickerUserId), abbrev(originalRequester), pendingId);
            renderUnauthorised(adapter, eventReqId, taskId, decoded.toolName(), originalRequester);
            return;
        }

        // ---- 4. Render resolved card (must finish inside the 5s WeCom window) ----
        renderResolved(adapter, eventReqId, taskId, decoded.toolName(), action, clickerUserId);

        // ---- 5. Inject the /approve or /deny command into the message router ----
        // Re-routing the click as a synthetic user message means we reuse the
        // existing approval validation + state-machine path
        // (ChannelMessageRouter.processMessage), so any future change to the
        // approval flow keeps working without a parallel button-click code path.
        String commandText = (action == ToolGuardButtonKey.Action.APPROVE ? "/approve " : "/deny ")
                + pendingId;
        ChannelMessage synthetic = buildSynthetic(commandText, clickerUserId, pending, frame);
        try {
            adapter.injectSyntheticMessage(synthetic);
            log.info("[wecom-toolguard] Injected '{}' for pending={}, clicker={}",
                    action == ToolGuardButtonKey.Action.APPROVE ? "/approve" : "/deny",
                    pendingId, abbrev(clickerUserId));
        } catch (Exception e) {
            // Never let an enqueue failure leave the card looking applied.
            // The card already shows resolved-state, but the agent won't see
            // the approve/deny — operator log is the safety net.
            log.error("[wecom-toolguard] Failed to inject command for pending={}: {}",
                    pendingId, e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Card rendering helpers
    // ------------------------------------------------------------------

    private static void renderResolved(WeComChannelAdapter adapter, String eventReqId,
                                       String taskId, String toolName,
                                       ToolGuardButtonKey.Action action, String clicker) {
        String title = action == ToolGuardButtonKey.Action.APPROVE
                ? "✅ 已批准"
                : "🚫 已拒绝";
        String desc = action == ToolGuardButtonKey.Action.APPROVE
                ? "Tool " + toolName + " 已批准"
                : "Tool " + toolName + " 已拒绝";
        try {
            adapter.updateTemplateCard(eventReqId,
                    ToolGuardCardRenderer.buildResolvedCard(taskId, title, desc));
        } catch (Exception e) {
            log.warn("[wecom-toolguard] update_template_card (resolved) failed: {}", e.getMessage());
        }
    }

    private static void renderUnauthorised(WeComChannelAdapter adapter, String eventReqId,
                                           String taskId, String toolName, String originalRequester) {
        String requesterLabel = originalRequester == null ? "原请求者" : abbrev(originalRequester);
        try {
            adapter.updateTemplateCard(eventReqId,
                    ToolGuardCardRenderer.buildResolvedCard(taskId,
                            "❌ 仅原请求者可审批",
                            "请由 " + requesterLabel + " 操作"));
        } catch (Exception e) {
            log.warn("[wecom-toolguard] update_template_card (unauthorised) failed: {}", e.getMessage());
        }
    }

    private static void renderExpired(WeComChannelAdapter adapter, String eventReqId,
                                      String taskId, String toolName) {
        try {
            adapter.updateTemplateCard(eventReqId,
                    ToolGuardCardRenderer.buildResolvedCard(taskId,
                            "⌛ 审批已过期",
                            "Tool " + toolName + " 的审批已过期或被处理"));
        } catch (Exception e) {
            log.warn("[wecom-toolguard] update_template_card (expired) failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Synthetic message construction
    // ------------------------------------------------------------------

    /**
     * Build a {@link ChannelMessage} that looks like the clicker just sent
     * "/approve <id>" (or /deny). The router's existing approval gate
     * picks it up via {@code processMessage} and runs the same identity-
     * check + state-machine that text commands hit.
     */
    @SuppressWarnings("unchecked")
    private static ChannelMessage buildSynthetic(String commandText, String clickerUserId,
                                                  PendingApproval pending,
                                                  Map<String, Object> eventFrame) {
        // The conversation behind the original card is whichever WeCom chat
        // the {@code template_card_event} arrived from. body.chattype +
        // body.chatid let us reconstruct the same conversationId the
        // original message used.
        Map<String, Object> body = (Map<String, Object>) eventFrame.getOrDefault("body", Map.of());
        String chatType = stringOrEmpty(body.get("chattype"));
        String chatId = stringOrEmpty(body.get("chatid"));
        boolean isGroup = "group".equals(chatType);
        String effectiveChatId = isGroup && !chatId.isBlank() ? chatId : null;
        String replyToken = isGroup && !chatId.isBlank() ? chatId : clickerUserId;

        return ChannelMessage.builder()
                .channelType("wecom")
                .senderId(clickerUserId)
                .senderName(clickerUserId)
                .chatId(effectiveChatId)
                .content(commandText)
                .contentType("text")
                .contentParts(List.of())
                .inputMode("text")
                .timestamp(LocalDateTime.now())
                .replyToken(replyToken)
                // Tag rawPayload so any downstream code that wants to
                // distinguish real messages from button-click injections
                // can read this flag instead of inspecting senderId.
                .rawPayload(Map.of(
                        "wecom_button_click", true,
                        "wecom_pending_id", pending.getPendingId()
                ))
                .build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static String extractEventReqId(Map<String, Object> frame) {
        Map<String, Object> headers = (Map<String, Object>) frame.getOrDefault("headers", Map.of());
        return stringOrEmpty(headers.get("req_id"));
    }

    private static String stringOrEmpty(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String abbrev(String s) {
        if (s == null || s.length() <= 8) return s == null ? "" : s;
        return s.substring(0, 8) + "…";
    }
}
