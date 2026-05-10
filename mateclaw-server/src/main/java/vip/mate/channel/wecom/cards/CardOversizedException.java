package vip.mate.channel.wecom.cards;

/**
 * Thrown when a card payload would exceed a WeCom-imposed size limit
 * (most commonly: button.key serialised JSON > 1024 bytes).
 *
 * <p>Catchable so that WeCom card renderers can fall back to the
 * abstract-class text path on overflow rather than letting the entire
 * approval flow drop. RFC-32 §2.1.1 calls this out explicitly.
 */
public class CardOversizedException extends RuntimeException {
    public CardOversizedException(String message) {
        super(message);
    }
}
