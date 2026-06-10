package vip.mate.channel.feishu.cards.tool_guard;

import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import lombok.extern.slf4j.Slf4j;
import vip.mate.approval.ApprovalService;
import vip.mate.approval.PendingApproval;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.feishu.FeishuChannelAdapter;
import vip.mate.channel.feishu.cards.FeishuCardHandler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Process an inbound {@code P2CardActionTrigger} for the tool-guard
 * approval card.
 *
 * <p><b>Schema-2.0 update protocol</b>: the card update must travel
 * back to Feishu via the {@code P2CardActionTriggerResponse} we
 * return — async {@code PATCH /im/v1/messages/{id}} is silent no-op
 * on V2 cards. We build a {@link CallBackCard} carrying the resolved
 * card JSON and a {@link CallBackToast} for the transient "已批准" /
 * "🚫 已拒绝" popup.
 *
 * <p><b>Replay protocol</b>: instead of calling
 * {@code approvalWorkflowService.resolve(...)} directly, we inject a
 * synthetic {@code /approve <pendingId>} message into the router. This
 * reuses the canonical text-approve path that the router already
 * tested: {@code resolveAndConsume + replayApprovedToolCall} — so the
 * approved tool actually re-runs through {@code ToolExecutionExecutor}
 * and the agent picks the next step. The button click and the
 * {@code /approve} text command thus take exactly the same code path,
 * preventing the two from drifting.
 *
 * <p><b>Step ordering</b>:
 * <ol>
 *   <li>Decode {@code action.value} → null check</li>
 *   <li>Look up {@code PendingApproval} by id</li>
 *   <li>Identity check: clicker (open_id) vs original requester</li>
 *   <li>Inject synthetic {@code /approve} or {@code /deny} command —
 *       router does the resolve + replay</li>
 *   <li>Build {@link P2CardActionTriggerResponse} with the resolved
 *       card JSON + toast and return it</li>
 * </ol>
 *
 * <p>All steps must complete inside Feishu's response window. Steps
 * 1–3 are O(ms) DB lookups; step 4 enqueues to the router but does
 * not block on execution; step 5 just serialises a Map.
 */
@Slf4j
public class ToolGuardCardHandler implements FeishuCardHandler {

    private final ApprovalService approvalService;
    private final ToolGuardButtonValue buttonValue;

    public ToolGuardCardHandler(ApprovalService approvalService,
                                 ToolGuardButtonValue buttonValue) {
        this.approvalService = approvalService;
        this.buttonValue = buttonValue;
    }

    @Override
    public P2CardActionTriggerResponse handle(FeishuChannelAdapter adapter, P2CardActionTriggerData data) {
        if (data == null) {
            log.warn("[feishu-toolguard] handle called with null data");
            return null;
        }
        CallBackAction action = data.getAction();
        CallBackOperator operator = data.getOperator();
        CallBackContext context = data.getContext();
        String messageId = context != null ? context.getOpenMessageId() : null;
        String clickerOpenId = operator != null ? operator.getOpenId() : null;

        // ---- 1. Decode button value
        ToolGuardButtonValue.Decoded decoded = action != null
                ? buttonValue.decode(action.getValue())
                : null;
        if (decoded == null) {
            log.warn("[feishu-toolguard] Could not decode action.value (messageId={}, clicker={})",
                    abbrev(messageId), abbrev(clickerOpenId));
            return null;
        }
        String pendingId = decoded.pendingId();
        ToolGuardButtonValue.Action act = decoded.action();

        // ---- 2. Look up pending approval
        Optional<PendingApproval> opt = approvalService.getPending(pendingId);
        if (opt.isEmpty() || !"pending".equals(opt.get().getStatus())) {
            log.info("[feishu-toolguard] Pending {} not found / already resolved (action={}, clicker={})",
                    pendingId, act, abbrev(clickerOpenId));
            return buildExpiredResponse(decoded.toolName());
        }
        PendingApproval pending = opt.get();

        // ---- 3. Identity check (fail-closed)
        // Agent/cron ("system") or unattributed (null) approvals have no human
        // requester to match the clicker against. A guarded-tool card landing in
        // a group chat would otherwise let ANY member click Approve and run the
        // tool. Those approvals must be resolved from the admin console instead,
        // so only an exact requester==clicker match is authorized here.
        String originalRequester = pending.getUserId();
        boolean authorized = originalRequester != null
                && !"system".equals(originalRequester)
                && originalRequester.equals(clickerOpenId);
        if (!authorized) {
            log.warn("[feishu-toolguard] Unauthorised click: clicker={} != requester={}, pending={}",
                    abbrev(clickerOpenId), abbrev(originalRequester), pendingId);
            return buildUnauthorizedResponse(decoded.toolName(), originalRequester);
        }

        // ---- 4. Inject synthetic /approve | /deny — router runs the
        //         canonical resolve + replay path. Mirror of WeCom's
        //         button-card handling so the two channels share one
        //         resolve code path.
        String commandText = (act == ToolGuardButtonValue.Action.APPROVE ? "/approve " : "/deny ")
                + pendingId;
        ChannelMessage synthetic = buildSynthetic(commandText, clickerOpenId, pending, data);
        try {
            adapter.injectSyntheticMessage(synthetic);
            log.info("[feishu-toolguard] Injected '{}' for pending={}, clicker={}",
                    act == ToolGuardButtonValue.Action.APPROVE ? "/approve" : "/deny",
                    pendingId, abbrev(clickerOpenId));
        } catch (Exception e) {
            // Returning the resolved-state card to Feishu without the
            // router seeing the click leaves the agent stuck. Log and
            // surface a failure toast — the user will see "未生效"
            // popup and the buttons stay clickable.
            log.error("[feishu-toolguard] Failed to inject synthetic command for pending={}: {}",
                    pendingId, e.getMessage(), e);
            return buildErrorResponse("⚠️ 审批未生效，请重试或联系运维");
        }

        // ---- 5. Build the resolved-state response
        return buildResolvedResponse(decoded.toolName(), act, clickerOpenId);
    }

    // ------------------------------------------------------------------
    // Response builders — assemble P2CardActionTriggerResponse{toast,card}
    // ------------------------------------------------------------------

    private static P2CardActionTriggerResponse buildResolvedResponse(
            String toolName, ToolGuardButtonValue.Action act, String clickerOpenId) {
        boolean approve = act == ToolGuardButtonValue.Action.APPROVE;
        String title = approve ? "✅ 已批准" : "🚫 已拒绝";
        String template = approve ? "green" : "red";
        String desc = "**工具**: `" + (toolName == null ? "" : toolName) + "`\n"
                + "**操作者**: " + abbrev(clickerOpenId);
        Map<String, Object> card = ToolGuardCardRenderer.buildResolvedCard(title, desc, template);

        P2CardActionTriggerResponse resp = new P2CardActionTriggerResponse();
        resp.setToast(buildToast(approve ? "info" : "warning", title));
        resp.setCard(wrapCard(card));
        return resp;
    }

    private static P2CardActionTriggerResponse buildUnauthorizedResponse(
            String toolName, String originalRequester) {
        String requesterLabel = originalRequester == null ? "原请求者" : abbrev(originalRequester);
        String desc = "**工具**: `" + (toolName == null ? "" : toolName) + "`\n"
                + "**原请求者**: " + requesterLabel + "\n*仅原请求者可批准 / 拒绝该操作*";
        Map<String, Object> card = ToolGuardCardRenderer.buildResolvedCard(
                "❌ 仅原请求者可审批", desc, "grey");

        P2CardActionTriggerResponse resp = new P2CardActionTriggerResponse();
        resp.setToast(buildToast("warning", "仅原请求者可审批"));
        resp.setCard(wrapCard(card));
        return resp;
    }

    private static P2CardActionTriggerResponse buildExpiredResponse(String toolName) {
        String desc = "**工具**: `" + (toolName == null ? "" : toolName) + "`\n"
                + "*该审批已过期或已被处理*";
        Map<String, Object> card = ToolGuardCardRenderer.buildResolvedCard(
                "⌛ 审批已失效", desc, "grey");

        P2CardActionTriggerResponse resp = new P2CardActionTriggerResponse();
        resp.setToast(buildToast("warning", "审批已失效"));
        resp.setCard(wrapCard(card));
        return resp;
    }

    private static P2CardActionTriggerResponse buildErrorResponse(String message) {
        P2CardActionTriggerResponse resp = new P2CardActionTriggerResponse();
        resp.setToast(buildToast("error", message));
        // Leave card null → original card stays clickable so the user can retry.
        return resp;
    }

    private static CallBackToast buildToast(String type, String content) {
        CallBackToast toast = new CallBackToast();
        toast.setType(type);
        toast.setContent(content);
        return toast;
    }

    private static CallBackCard wrapCard(Map<String, Object> cardJson) {
        CallBackCard cb = new CallBackCard();
        // Feishu callback-response validator only accepts Schema 1.0
        // inline cards with type="raw" — type="card_json" + Schema 2.0
        // body returns 200672 "卡片内容格式错误" even though the same
        // Schema 2.0 body works fine on cardkit/v1 card.create and on
        // im/v1 message.create msg_type=interactive. Two different
        // server-side validators, only one of which has been upgraded
        // for Schema 2.0. QwenPaw's production Feishu adapter uses the
        // same type="raw" approach for callback updates.
        cb.setType("raw");
        cb.setData(cardJson);
        return cb;
    }

    // ------------------------------------------------------------------
    // Synthetic message construction (mirror of WeCom pattern)
    // ------------------------------------------------------------------

    /**
     * Build a {@link ChannelMessage} that looks like the clicker just
     * typed "/approve &lt;pendingId&gt;" (or /deny) in the same chat.
     * The router's existing approve / deny gate picks it up and runs
     * the canonical resolveAndConsume + replay path.
     *
     * <p><b>conversationId matching is critical</b>: the router routes
     * the synthetic by {@code buildConversationId(message)} →
     * {@code feishu:<chatId-or-senderId>} and looks up pending under
     * that key. If the synthetic's conversationId doesn't match the
     * pending's own {@code conversationId}, the router treats the
     * message as a regular query and the LLM sees "/approve xxxxx"
     * as user text.
     *
     * <p>Feishu's card-callback {@code context.openChatId} is populated
     * even for 1:1 bot chats, but the original inbound-message handler
     * stores 1:1 chats with {@code chatId=null} (so
     * {@code buildConversationId} falls back to senderId). To stay
     * consistent we ignore {@code openChatId} and derive the chatId
     * from {@code pending.conversationId} — the source of truth that
     * was used to register the pending in the first place.
     */
    private static ChannelMessage buildSynthetic(String commandText, String clickerOpenId,
                                                  PendingApproval pending,
                                                  P2CardActionTriggerData data) {
        // pending.conversationId looks like "feishu:<scope>" where
        // <scope> is either ou_xxx (1:1 chat — derived from senderId)
        // or oc_xxx (group chat — derived from chatId). Reverse the
        // scope back into the right chatId field so buildConversationId
        // reproduces the exact same key.
        String convId = pending.getConversationId();
        String scope = (convId != null && convId.startsWith("feishu:"))
                ? convId.substring("feishu:".length())
                : null;
        boolean isGroup = scope != null && scope.startsWith("oc_");
        String chatId = isGroup ? scope : null;
        String replyToken = isGroup ? scope : clickerOpenId;

        return ChannelMessage.builder()
                .channelType("feishu")
                .senderId(clickerOpenId)
                .senderName(clickerOpenId)
                .chatId(chatId)
                .content(commandText)
                .contentType("text")
                .contentParts(List.of())
                .inputMode("text")
                .timestamp(LocalDateTime.now())
                .replyToken(replyToken)
                .rawPayload(Map.of(
                        "feishu_button_click", true,
                        "feishu_pending_id", pending.getPendingId()
                ))
                .build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String abbrev(String s) {
        if (s == null || s.isBlank()) return "";
        if (s.length() <= 12) return s;
        return s.substring(0, 12) + "…";
    }
}
