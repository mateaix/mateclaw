package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Team-scoped SSE event channel, backed by a synthetic conversation id on the
 * existing stream tracker so registration, ring buffering, replay-by-last-id
 * and heartbeats are all inherited instead of re-invented. One channel per
 * team, alive for the application's lifetime; publishing lazily (re)registers,
 * so a recycled channel heals on the next event and subscribers simply
 * reconnect.
 *
 * Task events are additionally mirrored onto the originating lead
 * conversation's stream (when the task has one) for in-chat observability.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamEventChannel {

    static final String CHANNEL_PREFIX = "team-events-";

    private final ChatStreamTracker streamTracker;

    /** Publish a task lifecycle event to the team channel (+ lead stream if any). */
    public void publishTaskEvent(TeamTaskEntity task, String event, Map<String, Object> extra) {
        if (task == null) {
            return;
        }
        try {
            Map<String, Object> payload = payload(extra);
            payload.put("taskId", String.valueOf(task.getId()));
            payload.put("taskNumber", task.getTaskNumber());
            payload.put("subject", task.getSubject());
            payload.put("teamId", String.valueOf(task.getTeamId()));
            payload.put("assigneeAgentId", String.valueOf(task.getAssigneeAgentId()));
            if (task.getRunId() != null) {
                payload.put("runId", String.valueOf(task.getRunId()));
            } else {
                payload.remove("runId");
            }

            String channelId = channelId(task.getTeamId());
            streamTracker.register(channelId);
            streamTracker.broadcastObject(channelId, event, payload);

            if (task.getLeadConversationId() != null) {
                streamTracker.broadcastObject(task.getLeadConversationId(), event, payload);
            }
            log.debug("Team event published runId={} teamId={} conversationId={} taskId={} event={}",
                    task.getRunId(), task.getTeamId(), task.getLeadConversationId(),
                    task.getId(), event);
        } catch (Exception e) {
            // Events are a side channel — never let them affect the task flow.
            log.debug("Team event skipped runId={} teamId={} conversationId={} taskId={} event={}: {}",
                    task.getRunId(), task.getTeamId(), task.getLeadConversationId(),
                    task.getId(), event, e.getMessage());
        }
    }

    /** Publish a run lifecycle projection to the team channel and lead stream. */
    public void publishRunEvent(TeamRunView run, String event, Map<String, Object> extra) {
        if (run == null) {
            return;
        }
        try {
            Map<String, Object> payload = payload(extra);
            payload.put("runId", String.valueOf(run.id()));
            payload.put("teamId", String.valueOf(run.teamId()));
            payload.put("leadConversationId", run.leadConversationId());
            payload.put("status", run.status());
            payload.put("progress", run.progress());

            String channelId = channelId(run.teamId());
            streamTracker.register(channelId);
            streamTracker.broadcastObject(channelId, event, payload);
            if (run.leadConversationId() != null) {
                streamTracker.broadcastObject(run.leadConversationId(), event, payload);
            }
            log.debug("Team event published runId={} teamId={} conversationId={} taskId={} event={}",
                    run.id(), run.teamId(), run.leadConversationId(), null, event);
        } catch (Exception e) {
            log.debug("Team event skipped runId={} teamId={} conversationId={} taskId={} event={}: {}",
                    run.id(), run.teamId(), run.leadConversationId(), null, event, e.getMessage());
        }
    }

    /** Attach a subscriber, replaying buffered events newer than lastEventId. */
    public boolean attach(Long teamId, SseEmitter emitter, long lastEventId) {
        String channelId = channelId(teamId);
        streamTracker.register(channelId);
        return streamTracker.attach(channelId, emitter, lastEventId);
    }

    static String channelId(Long teamId) {
        return CHANNEL_PREFIX + teamId;
    }

    private Map<String, Object> payload(Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>();
        if (extra != null) {
            extra.forEach((key, value) -> payload.put(key, stringifyLongs(value)));
        }
        return payload;
    }

    private Object stringifyLongs(Object value) {
        if (value instanceof Long longValue) {
            return String.valueOf(longValue);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new HashMap<>();
            map.forEach((key, nested) -> normalized.put(String.valueOf(key), stringifyLongs(nested)));
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            iterable.forEach(item -> normalized.add(stringifyLongs(item)));
            return normalized;
        }
        return value;
    }
}
