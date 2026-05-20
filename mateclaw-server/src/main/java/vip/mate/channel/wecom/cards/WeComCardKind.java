package vip.mate.channel.wecom.cards;

import vip.mate.channel.cards.CardOversizedException;

/**
 * Description of one kind of interactive WeCom template card the
 * dispatcher knows how to route, along with the two functional callbacks
 * that handle the outbound render and the inbound click event.
 *
 * <p>Kept as a simple record so adding a new card type (e.g. a poll
 * card, an info-request card) is just: implement renderer/handler,
 * register a new {@code WeComCardKind} in
 * {@link WeComCardDispatcher#registerKinds()}.
 *
 * @param name           short human-readable name for logs
 * @param messageType    matches {@code metadata.message_type} on the
 *                      outbound event coming from the agent runtime
 *                      (drives the {@code render} dispatch)
 * @param taskIdPrefix   matches the prefix of the inbound
 *                      {@code template_card_event.task_id} (drives the
 *                      {@code handle} dispatch). Card kinds must use
 *                      disjoint prefixes; the dispatcher rejects
 *                      registration of a colliding prefix.
 * @param renderer       converts a pending business object (e.g.
 *                      {@code ApprovalNotice}) into a WeCom template_card
 *                      payload Map. Throws {@link CardOversizedException}
 *                      to signal "this kind cannot render now, fall back
 *                      to text".
 * @param handler        processes an inbound {@code template_card_event}
 *                      frame: validate identity → render resolved card →
 *                      enqueue any follow-up command. Implementations
 *                      MUST complete the render-resolved-card step inside
 *                      the 5s WeCom protocol window; the agent enqueue
 *                      step can be slower.
 */
public record WeComCardKind(
        String name,
        String messageType,
        String taskIdPrefix,
        WeComCardRenderer renderer,
        WeComCardHandler handler
) {
    public WeComCardKind {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("WeComCardKind.name must not be blank");
        }
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("WeComCardKind.messageType must not be blank");
        }
        if (taskIdPrefix == null || taskIdPrefix.isBlank()) {
            throw new IllegalArgumentException("WeComCardKind.taskIdPrefix must not be blank");
        }
    }
}
