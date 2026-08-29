package vip.mate.agent.runtime.contract;

import java.util.ArrayList;
import java.util.List;

public final class RuntimeEventLog {
    private final String sessionId;
    private final List<RuntimeEvent> events = new ArrayList<>();
    private boolean terminal;
    private long lastSequence = -1;

    public RuntimeEventLog(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        this.sessionId = sessionId;
    }

    public synchronized void append(RuntimeEvent event) {
        if (!sessionId.equals(event.sessionId())) {
            throw new IllegalArgumentException("event belongs to another session");
        }
        if (event.sequence() <= lastSequence) {
            throw new IllegalArgumentException("event sequence must increase");
        }
        if (terminal) {
            throw new IllegalStateException("terminal event already appended");
        }
        events.add(event);
        lastSequence = event.sequence();
        terminal = event.terminal();
    }

    public synchronized List<RuntimeEvent> snapshot() {
        return List.copyOf(events);
    }

    public synchronized boolean terminal() {
        return terminal;
    }
}
