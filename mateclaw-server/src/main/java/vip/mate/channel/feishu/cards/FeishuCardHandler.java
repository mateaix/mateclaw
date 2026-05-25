package vip.mate.channel.feishu.cards;

import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import vip.mate.channel.feishu.FeishuChannelAdapter;

/**
 * Process an inbound {@code P2CardActionTrigger} event for one kind of
 * interactive card (e.g. a tool-guard approval card).
 *
 * <p>Schema-2.0 cards update in-place via the {@code
 * P2CardActionTriggerResponse} the handler returns — Feishu uses
 * {@code response.card} as the new card body and surfaces
 * {@code response.toast} as a transient popup. The async
 * {@code PATCH /im/v1/messages/{id}} path is a silent no-op for V2
 * cards; do NOT use it.
 *
 * <p>Implementations must:
 * <ol>
 *   <li>Validate the click — decode the button value, look up the
 *       pending business object, identity-check the clicker against
 *       the original requester.</li>
 *   <li>Inject any agent-side follow-up (e.g. a synthetic
 *       {@code /approve <id>} message via {@code adapter.injectSyntheticMessage(...)})
 *       so the router runs its canonical resolve + replay logic.</li>
 *   <li>Build and return a {@link P2CardActionTriggerResponse} with
 *       the resolved-state card body. Must complete inside Feishu's
 *       response window (~3 seconds before timeout).</li>
 * </ol>
 *
 * <p>Returning {@code null} is allowed — Feishu leaves the original
 * card untouched in that case (use sparingly, only when the event
 * shouldn't acknowledge visibly).
 */
@FunctionalInterface
public interface FeishuCardHandler {
    /**
     * @param adapter the live Feishu adapter (provides
     *                {@code injectSyntheticMessage}, SDK client, etc.)
     * @param data    the parsed {@code P2CardActionTriggerData} payload —
     *                contains the operator, action.value, the card
     *                token, and {@code context} (containing the
     *                {@code open_message_id} of the original card)
     * @return the response Feishu should use to update the card, or
     *         null to leave it unchanged
     */
    P2CardActionTriggerResponse handle(FeishuChannelAdapter adapter, P2CardActionTriggerData data);
}
