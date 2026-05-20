package vip.mate.channel.feishu.cards;

import vip.mate.channel.cards.CardOversizedException;
import vip.mate.channel.notification.ApprovalNotice;

import java.util.Map;

/**
 * Build a Feishu interactive-card payload Map from a business object.
 *
 * <p>Implementations may throw {@link CardOversizedException} to signal
 * the caller to fall back to a non-card path (e.g. text approval
 * notice on the {@code AbstractChannelAdapter} default). Anything
 * else surfaces as a bug.
 *
 * <p>Currently parameterised on {@link ApprovalNotice} since tool-guard
 * is the only card kind in this PR; future kinds (poll cards, info-
 * request cards, etc.) will likely take a different input or accept
 * {@code Object} and self-cast.
 */
@FunctionalInterface
public interface FeishuCardRenderer {
    /**
     * Build the Schema-2.0 interactive-card body Map ready to drop into
     * {@code im/v1/messages.create} with {@code msg_type=interactive}.
     */
    Map<String, Object> render(ApprovalNotice notice) throws CardOversizedException;
}
