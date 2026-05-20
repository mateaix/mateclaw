package vip.mate.channel.feishu.cards;

import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import vip.mate.channel.feishu.FeishuChannelAdapter;

/**
 * Process an inbound {@code P2CardActionTrigger} event for one kind of
 * interactive card (e.g. a tool-guard approval card).
 *
 * <p>Implementations must:
 * <ol>
 *   <li>Validate the click — decode the button value, look up the pending
 *       business object, identity-check the clicker against the original
 *       requester.</li>
 *   <li>Update the original card to a resolved state via
 *       {@link FeishuChannelAdapter#updateCard} so the user sees an
 *       immediate "✅ 已批准" / "🚫 已拒绝" / unauthorized / expired
 *       confirmation. Must complete inside Feishu's response window
 *       (the SDK gives ~3 seconds before timing out the callback).</li>
 *   <li>Trigger any agent-side follow-up (e.g. resolve the approval,
 *       inject a synthetic command) — this step may be slower than the
 *       card-update step.</li>
 * </ol>
 */
@FunctionalInterface
public interface FeishuCardHandler {
    /**
     * @param adapter the live Feishu adapter (provides {@code updateCard},
     *                {@code messageRouter}, SDK client, etc.)
     * @param data    the parsed {@code P2CardActionTriggerData} payload —
     *                contains the operator, action.value, the card token,
     *                and {@code context} (containing the
     *                {@code open_message_id} of the original card)
     */
    void handle(FeishuChannelAdapter adapter, P2CardActionTriggerData data);
}
