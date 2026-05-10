package vip.mate.channel.wecom.cards;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.wecom.cards.tool_guard.ToolGuardCardKindFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routing-only dispatcher for WeCom interactive template cards.
 *
 * <p>Maintains two indexes keyed by disjoint identifiers:
 * <ul>
 *   <li><b>Outbound</b>: {@code metadata.message_type} from the agent
 *       runtime → {@link WeComCardKind#renderer()}. Currently the only
 *       direct outbound caller is {@link
 *       vip.mate.channel.wecom.WeComChannelAdapter#sendApprovalNotice}
 *       which doesn't yet read message_type — but having the index lets
 *       us add future card kinds without touching the adapter.</li>
 *   <li><b>Inbound</b>: prefix of the
 *       {@code template_card_event.task_id} → {@link WeComCardKind#handler()}.
 *       Card kinds <i>must</i> use disjoint prefixes; collision throws
 *       at registration time.</li>
 * </ul>
 *
 * <p>The {@code @Component} is autowired; current contributors are
 * collected via {@link #registerKinds()} which calls factory beans for
 * each kind. Adding a new card kind: implement {@code WeComCardKind} +
 * a factory bean returning it + add a line to {@link #registerKinds()}.
 */
@Slf4j
@Component
public class WeComCardDispatcher {

    private final Map<String, WeComCardKind> byMessageType = new HashMap<>();
    private final Map<String, WeComCardKind> byTaskIdPrefix = new HashMap<>();

    private final ToolGuardCardKindFactory toolGuardFactory;

    public WeComCardDispatcher(ToolGuardCardKindFactory toolGuardFactory) {
        this.toolGuardFactory = toolGuardFactory;
        registerKinds();
    }

    private void registerKinds() {
        // Currently single kind. Add lines here as new card kinds land
        // (poll cards / info-request cards / etc.). Order doesn't matter:
        // the disjoint-prefix invariant prevents ambiguity at lookup.
        register(toolGuardFactory.create());
    }

    private void register(WeComCardKind kind) {
        if (byMessageType.containsKey(kind.messageType())) {
            throw new IllegalStateException(
                    "duplicate card kind for messageType '" + kind.messageType()
                            + "': existing=" + byMessageType.get(kind.messageType()).name()
                            + ", new=" + kind.name());
        }
        if (byTaskIdPrefix.containsKey(kind.taskIdPrefix())) {
            throw new IllegalStateException(
                    "duplicate card kind for taskIdPrefix '" + kind.taskIdPrefix()
                            + "': existing=" + byTaskIdPrefix.get(kind.taskIdPrefix()).name()
                            + ", new=" + kind.name());
        }
        byMessageType.put(kind.messageType(), kind);
        byTaskIdPrefix.put(kind.taskIdPrefix(), kind);
        log.info("[wecom-cards] Registered card kind: name={} messageType={} taskIdPrefix={}",
                kind.name(), kind.messageType(), kind.taskIdPrefix());
    }

    /**
     * Look up a card kind by outbound {@code metadata.message_type}.
     */
    public Optional<WeComCardKind> lookupByMessageType(String messageType) {
        if (messageType == null || messageType.isBlank()) return Optional.empty();
        return Optional.ofNullable(byMessageType.get(messageType));
    }

    /**
     * Look up a card kind by inbound {@code template_card_event.task_id}'s
     * prefix. O(N) over registered kinds (N is small — currently 1).
     */
    public Optional<WeComCardKind> lookupByTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) return Optional.empty();
        for (Map.Entry<String, WeComCardKind> e : byTaskIdPrefix.entrySet()) {
            if (taskId.startsWith(e.getKey())) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /** Visible for tests / logs. */
    public List<String> registeredKindNames() {
        return byMessageType.values().stream().map(WeComCardKind::name).toList();
    }
}
