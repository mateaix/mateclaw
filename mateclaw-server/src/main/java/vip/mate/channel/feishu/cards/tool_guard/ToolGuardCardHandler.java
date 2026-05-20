package vip.mate.channel.feishu.cards.tool_guard;

import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import lombok.extern.slf4j.Slf4j;
import vip.mate.approval.ApprovalService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.PendingApproval;
import vip.mate.channel.feishu.FeishuChannelAdapter;
import vip.mate.channel.feishu.cards.FeishuCardHandler;

import java.util.Optional;

/**
 * Process an inbound {@code P2CardActionTrigger} for the tool-guard
 * approval card.
 *
 * <p><b>Step ordering — validate before render</b>: a non-original-
 * requester click must NOT briefly show "已批准 by ..." before the
 * router drops the injected command. Order:
 * <ol>
 *   <li>Decode {@code action.value} → null check</li>
 *   <li>Look up {@code PendingApproval} by id</li>
 *   <li>Identity check: clicker (open_id) vs original requester</li>
 *   <li>Update the original card to a resolved state (success /
 *       unauthorized / expired)</li>
 *   <li>Resolve the approval via {@link ApprovalService#resolve}</li>
 * </ol>
 *
 * <p>Steps 1–4 must complete inside Feishu's response window; step 5
 * can be slower.
 */
@Slf4j
public class ToolGuardCardHandler implements FeishuCardHandler {

    private final ApprovalService approvalService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final ToolGuardButtonValue buttonValue;

    public ToolGuardCardHandler(ApprovalService approvalService,
                                 ApprovalWorkflowService approvalWorkflowService,
                                 ToolGuardButtonValue buttonValue) {
        this.approvalService = approvalService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.buttonValue = buttonValue;
    }

    @Override
    public void handle(FeishuChannelAdapter adapter, P2CardActionTriggerData data) {
        if (data == null) {
            log.warn("[feishu-toolguard] handle called with null data");
            return;
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
            return;
        }
        String pendingId = decoded.pendingId();
        ToolGuardButtonValue.Action act = decoded.action();

        // ---- 2. Look up pending approval
        Optional<PendingApproval> opt = approvalService.getPending(pendingId);
        if (opt.isEmpty() || !"pending".equals(opt.get().getStatus())) {
            log.info("[feishu-toolguard] Pending {} not found / already resolved (action={}, clicker={})",
                    pendingId, act, abbrev(clickerOpenId));
            updateExpired(adapter, messageId, decoded.toolName());
            return;
        }
        PendingApproval pending = opt.get();

        // ---- 3. Identity check
        String originalRequester = pending.getUserId();
        boolean authorized = originalRequester == null
                || "system".equals(originalRequester)
                || originalRequester.equals(clickerOpenId);
        if (!authorized) {
            log.warn("[feishu-toolguard] Unauthorised click: clicker={} != requester={}, pending={}",
                    abbrev(clickerOpenId), abbrev(originalRequester), pendingId);
            updateUnauthorized(adapter, messageId, decoded.toolName(), originalRequester);
            return;
        }

        // ---- 4. Render resolved card (must finish inside Feishu's response window)
        updateResolved(adapter, messageId, decoded.toolName(), act, clickerOpenId);

        // ---- 5. Resolve the approval via the canonical workflow service
        String decisionLabel = act == ToolGuardButtonValue.Action.APPROVE ? "approved" : "denied";
        String actor = clickerOpenId == null ? "feishu-card" : clickerOpenId;
        try {
            approvalWorkflowService.resolve(pendingId, actor, decisionLabel);
            log.info("[feishu-toolguard] Resolved pending={} decision={} actor={}",
                    pendingId, decisionLabel, abbrev(actor));
        } catch (Exception e) {
            // Never let a resolve failure leave the card looking applied.
            // Card already shows resolved-state, but the agent won't see
            // the decision — operator log is the safety net.
            log.error("[feishu-toolguard] resolve {} failed for pending={}: {}",
                    decisionLabel, pendingId, e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Card render helpers
    // ------------------------------------------------------------------

    private static void updateResolved(FeishuChannelAdapter adapter, String messageId,
                                        String toolName, ToolGuardButtonValue.Action act, String clicker) {
        if (messageId == null || messageId.isBlank()) {
            log.debug("[feishu-toolguard] No messageId on inbound — cannot update card (will rely on resolve event)");
            return;
        }
        boolean approve = act == ToolGuardButtonValue.Action.APPROVE;
        String title = approve ? "✅ 已批准" : "🚫 已拒绝";
        String template = approve ? "green" : "red";
        StringBuilder desc = new StringBuilder();
        desc.append("**工具**: `").append(toolName == null ? "" : toolName).append("`\n");
        desc.append("**操作者**: ").append(abbrev(clicker));
        try {
            adapter.updateCard(messageId,
                    ToolGuardCardRenderer.buildResolvedCard(title, desc.toString(), template));
        } catch (Exception e) {
            log.warn("[feishu-toolguard] update_card (resolved) failed: {}", e.getMessage());
        }
    }

    private static void updateUnauthorized(FeishuChannelAdapter adapter, String messageId,
                                            String toolName, String originalRequester) {
        if (messageId == null || messageId.isBlank()) return;
        String desc = "**工具**: `" + (toolName == null ? "" : toolName) + "`\n"
                + "**原请求者**: " + abbrev(originalRequester) + "\n"
                + "*仅原请求者可批准 / 拒绝该操作*";
        try {
            adapter.updateCard(messageId,
                    ToolGuardCardRenderer.buildResolvedCard("❌ 仅原请求者可审批", desc, "grey"));
        } catch (Exception e) {
            log.warn("[feishu-toolguard] update_card (unauthorized) failed: {}", e.getMessage());
        }
    }

    private static void updateExpired(FeishuChannelAdapter adapter, String messageId, String toolName) {
        if (messageId == null || messageId.isBlank()) return;
        String desc = "**工具**: `" + (toolName == null ? "" : toolName) + "`\n"
                + "*该审批已过期或已被处理*";
        try {
            adapter.updateCard(messageId,
                    ToolGuardCardRenderer.buildResolvedCard("⌛ 审批已失效", desc, "grey"));
        } catch (Exception e) {
            log.warn("[feishu-toolguard] update_card (expired) failed: {}", e.getMessage());
        }
    }

    private static String abbrev(String s) {
        if (s == null || s.isBlank()) return "";
        if (s.length() <= 12) return s;
        return s.substring(0, 12) + "…";
    }
}
