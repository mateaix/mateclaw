package vip.mate.channel.cards;

/**
 * Thrown when a channel-specific card payload would exceed a platform-
 * imposed size limit (e.g. WeCom's 1024-byte {@code button.key},
 * Feishu's 30 KB interactive content cap).
 *
 * <p>Caught by adapters so they can fall back to the
 * {@code AbstractChannelAdapter} text-approval path instead of letting
 * the whole approval flow drop. Lives in the generic {@code channel/cards}
 * package so every channel implementation shares one type.
 */
public class CardOversizedException extends RuntimeException {
    public CardOversizedException(String message) {
        super(message);
    }
}
