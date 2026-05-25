package vip.mate.workflow.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reusable stub for the workflow tests. Each test that needs to drive
 * the runtime without booting real agents binds this as the
 * {@link AgentInvoker} primary bean and pre-loads canned responses
 * keyed by agent name.
 */
public class StubAgentInvoker implements AgentInvoker {
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> errors = new ConcurrentHashMap<>();
    private final Map<String, String> lastPrompts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
    private final Map<String, Long> nameToId = new HashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(1000);

    public synchronized void reset() {
        responses.clear();
        errors.clear();
        lastPrompts.clear();
        invocations.clear();
        nameToId.clear();
        idSeq.set(1000);
    }

    public void respond(String agentName, String text) { responses.put(agentName, text); }

    public void respondWithThrow(String agentName, RuntimeException ex) { errors.put(agentName, ex); }

    public String lastPromptFor(String agentName) { return lastPrompts.get(agentName); }

    public int invocationCount(String agentName) {
        AtomicInteger c = invocations.get(agentName);
        return c == null ? 0 : c.get();
    }

    @Override
    public String invoke(long agentId, String prompt, String conversationId) {
        String name = nameById(agentId);
        invocations.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        lastPrompts.put(name, prompt);
        RuntimeException err = errors.get(name);
        if (err != null) throw err;
        return responses.getOrDefault(name, "");
    }

    @Override
    public synchronized Long resolveAgentId(long workspaceId, String agentName) {
        return nameToId.computeIfAbsent(agentName, k -> (long) idSeq.incrementAndGet());
    }

    private synchronized String nameById(long id) {
        return nameToId.entrySet().stream()
                .filter(e -> e.getValue() == id)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown agentId " + id));
    }
}
