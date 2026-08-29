package vip.mate.agent.runtime;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/** Atomic local admission shared by interactive, replay and autonomous turns. */
@Component
public class ConversationTurnGate {
    private final ConcurrentHashMap<String, Permit> owners = new ConcurrentHashMap<>();
    private final ThreadLocal<Permit> admitted = new ThreadLocal<>();

    public Permit tryAcquire(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return new Permit(null);
        Permit current = admitted.get();
        if (current != null && conversationId.equals(current.conversationId)
                && owners.get(conversationId) == current) return new Permit(null);
        Permit permit = new Permit(conversationId);
        return owners.putIfAbsent(conversationId, permit) == null ? permit : null;
    }

    /** Enter the already-admitted call synchronously; inner lifecycle cleanup must not release its owner. */
    public <T> T withPermit(Permit permit, java.util.function.Supplier<T> call) {
        Permit previous=admitted.get();
        admitted.set(permit);
        try { return call.get(); }
        finally { if (previous==null) admitted.remove(); else admitted.set(previous); }
    }

    public final class Permit implements AutoCloseable {
        private final String conversationId;
        private Permit(String conversationId) { this.conversationId = conversationId; }
        @Override public void close() {
            if (conversationId != null) owners.remove(conversationId, this);
        }
    }
}
