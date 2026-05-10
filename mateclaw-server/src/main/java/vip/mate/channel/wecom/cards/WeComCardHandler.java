package vip.mate.channel.wecom.cards;

import vip.mate.channel.wecom.WeComChannelAdapter;

import java.util.Map;

/**
 * Processes an inbound WeCom {@code template_card_event} frame for one
 * kind of card.
 *
 * <p>Implementations must respect the 5-second WeCom window: render and
 * dispatch the resolved-state card update (via
 * {@link WeComChannelAdapter#updateTemplateCard}) inside that window,
 * THEN enqueue any agent-side command (e.g. {@code /approve <id>}).
 * The window starts the moment the event frame is received, so any
 * pre-update validation must be cheap (DB lookup + identity check is
 * fine; LLM round-trip is not).
 */
@FunctionalInterface
public interface WeComCardHandler {
    /**
     * @param adapter   the live WeCom adapter (provides
     *                  {@code updateTemplateCard}, {@code messageRouter}
     *                  for command injection, etc.)
     * @param frame     the raw inbound frame including {@code headers.req_id}
     *                  needed by {@code updateTemplateCard}
     * @param tce       the parsed {@code event.template_card_event} sub-object
     *                  (already extracted by the dispatcher; contains
     *                  {@code task_id} / {@code event_key})
     * @param fromBlock the {@code body.from} sub-object (carries
     *                  {@code userid} of the clicker — needed for
     *                  identity validation against original requester)
     */
    void handle(WeComChannelAdapter adapter,
                Map<String, Object> frame,
                Map<String, Object> tce,
                Map<String, Object> fromBlock);
}
