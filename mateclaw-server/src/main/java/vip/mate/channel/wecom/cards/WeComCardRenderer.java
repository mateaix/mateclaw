package vip.mate.channel.wecom.cards;

import vip.mate.channel.cards.CardOversizedException;

import vip.mate.channel.notification.ApprovalNotice;

import java.util.Map;

/**
 * Builds a WeCom template_card payload Map from a business object.
 *
 * <p>Implementations may throw {@link CardOversizedException} to signal
 * the caller to fall back to a non-card path (e.g. text approval
 * notice). All other throw paths surface as bugs.
 *
 * <p>Currently parameterised on {@link ApprovalNotice} since tool-guard
 * is the only card kind in PR-1; future kinds will likely accept a
 * different input or take {@code Object} and self-cast.
 */
@FunctionalInterface
public interface WeComCardRenderer {
    /**
     * Build the template_card body Map ready to drop into
     * {@code aibot_respond_msg.body.template_card}.
     */
    Map<String, Object> render(ApprovalNotice notice) throws CardOversizedException;
}
