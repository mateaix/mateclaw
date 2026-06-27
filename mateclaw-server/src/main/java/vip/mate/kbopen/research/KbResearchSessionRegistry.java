package vip.mate.kbopen.research;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.wiki.service.WikiResearchService.ResearchResult;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active Deep Research sessions started via the Open API.
 *
 * <p>Each session records the owning API key id + kbId so that status/cancel
 * endpoints can authorize access (a caller can only query/cancel their own
 * sessions). Results are stored on completion for the status endpoint to
 * return synchronously.
 *
 * <p>This is an in-memory registry (single-node). For multi-node, sessions
 * would need to live in a shared store — but research is short-lived (< 1 min
 * typical) and the SSE stream must connect to the node running the job, so
 * sticky routing is a prerequisite anyway.
 */
@Slf4j
@Component
public class KbResearchSessionRegistry {

    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

    public record Session(String sessionId, Long keyId, Long kbId, String topic, Status status,
                          ResearchResult result, String error) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, Long keyId, Long kbId, String topic) {
        sessions.put(sessionId, new Session(sessionId, keyId, kbId, topic, Status.RUNNING, null, null));
    }

    public Optional<Session> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void complete(String sessionId, ResearchResult result) {
        sessions.computeIfPresent(sessionId, (k, s) ->
                new Session(s.sessionId(), s.keyId(), s.kbId(), s.topic(), Status.COMPLETED, result, null));
    }

    public void fail(String sessionId, String error) {
        sessions.computeIfPresent(sessionId, (k, s) ->
                new Session(s.sessionId(), s.keyId(), s.kbId(), s.topic(), Status.FAILED, null, error));
    }

    public boolean cancel(String sessionId) {
        return sessions.computeIfPresent(sessionId, (k, s) ->
                s.status() == Status.RUNNING
                        ? new Session(s.sessionId(), s.keyId(), s.kbId(), s.topic(), Status.CANCELLED, null, null)
                        : s) != null;
    }
}
