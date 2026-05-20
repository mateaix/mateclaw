package vip.mate.channel.feishu.cards;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.feishu.cards.tool_guard.ToolGuardCardKindFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routing-only dispatcher for Feishu interactive cards.
 *
 * <p>Maintains a single index keyed by {@link FeishuCardKind#actionPrefix} —
 * the inbound {@code P2CardActionTrigger} payload carries
 * {@code action.value.action} (a string we put there during render),
 * and the dispatcher picks the handler whose prefix matches.
 *
 * <p>Card kinds <i>must</i> use disjoint prefixes; collision throws at
 * registration time. Mirror image of {@code WeComCardDispatcher} —
 * same shape, same disjoint-prefix invariant — but parameterised on
 * Feishu's {@code action.value} discriminator rather than WeCom's
 * {@code template_card_event.task_id} prefix.
 *
 * <p>Outbound rendering today has a single direct caller
 * ({@code FeishuChannelAdapter.sendApprovalNotice}) which always wants
 * the tool-guard kind, so no outbound discriminator is needed yet.
 * Adding more outbound kinds: introduce a second index keyed by
 * {@code metadata.message_type} the way WeCom does.
 */
@Slf4j
@Component
public class FeishuCardDispatcher {

    /** {@code action.value.action} prefix → kind. */
    private final Map<String, FeishuCardKind> byActionPrefix = new HashMap<>();

    /** {@code name} → kind, for outbound lookup by callers that know the kind name. */
    private final Map<String, FeishuCardKind> byName = new HashMap<>();

    private final ToolGuardCardKindFactory toolGuardFactory;

    public FeishuCardDispatcher(ToolGuardCardKindFactory toolGuardFactory) {
        this.toolGuardFactory = toolGuardFactory;
        registerKinds();
    }

    private void registerKinds() {
        // Currently single kind. Add lines here as new card kinds land.
        // Order doesn't matter — disjoint-prefix invariant prevents ambiguity.
        register(toolGuardFactory.create());
    }

    private void register(FeishuCardKind kind) {
        if (byActionPrefix.containsKey(kind.actionPrefix())) {
            throw new IllegalStateException(
                    "duplicate card kind for actionPrefix '" + kind.actionPrefix()
                            + "': existing=" + byActionPrefix.get(kind.actionPrefix()).name()
                            + ", new=" + kind.name());
        }
        if (byName.containsKey(kind.name())) {
            throw new IllegalStateException(
                    "duplicate card kind name '" + kind.name() + "'");
        }
        byActionPrefix.put(kind.actionPrefix(), kind);
        byName.put(kind.name(), kind);
        log.info("[feishu-cards] Registered card kind: name={} actionPrefix={}",
                kind.name(), kind.actionPrefix());
    }

    /** Look up a card kind by its registered name (outbound). */
    public Optional<FeishuCardKind> lookupByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Look up a card kind by the inbound {@code action.value.action}
     * string's prefix. O(N) over registered kinds (N is small —
     * currently 1).
     */
    public Optional<FeishuCardKind> lookupByAction(String action) {
        if (action == null || action.isBlank()) return Optional.empty();
        for (Map.Entry<String, FeishuCardKind> e : byActionPrefix.entrySet()) {
            if (action.startsWith(e.getKey())) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /** Visible for tests / logs. */
    public List<String> registeredKindNames() {
        return List.copyOf(byName.keySet());
    }
}
