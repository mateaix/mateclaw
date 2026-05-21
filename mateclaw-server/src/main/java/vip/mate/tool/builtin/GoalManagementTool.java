package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.exception.MateClawException;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.service.GoalService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in tools that let an agent create and manipulate its own
 * persistent goal. The agent should reach for these when the user states
 * an objective that spans multiple turns — the runtime then tracks
 * progress across the entire conversation.
 *
 * <p>All four tool names are added to
 * {@code DelegateAgentTool.DEFAULT_CHILD_DENIED_TOOLS} so a child agent
 * cannot mutate the parent conversation's goal. Goal ownership is bound
 * to the parent conversation, period.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoalManagementTool {

    private final GoalService goalService;
    private final GoalProperties properties;
    private final ObjectMapper objectMapper;
    private final ChatStreamTracker streamTracker;

    @Tool(description = """
            Set a persistent goal for the current conversation. The agent will \
            self-evaluate progress after every reply and surface what is still \
            missing. Use ONLY when the user states an objective that genuinely \
            spans multiple turns (e.g. 'deploy this to production', 'rewrite \
            this module to use async I/O'). Single-question Q&A does not need \
            a goal.""")
    public String setGoal(
            @ToolParam(description = "Short title under 80 chars; shown in UI hover.") String title,
            @ToolParam(description = "Full description of what success looks like.",
                    required = false) String description,
            @ToolParam(description = "Exit criteria the evaluator scores against (e.g. 'tests pass + deployed').",
                    required = false) String exitCriteria,
            @ToolParam(description = "Max evaluation turns before exhaustion. Default 20.",
                    required = false) Integer turnBudget,
            @ToolParam(description = "If true, the agent may auto-followup when progress is incomplete. Default false.",
                    required = false) Boolean autoFollowup,
            @Nullable ToolContext ctx) {

        if (!properties.isEnabled()) {
            return errorJson("Goal subsystem is disabled on this server");
        }
        if (title == null || title.isBlank()) {
            return errorJson("title is required");
        }

        ChatOrigin origin = ChatOrigin.from(ctx);
        if (origin == null || origin.conversationId() == null || origin.conversationId().isBlank()) {
            return errorJson("setGoal requires a bound conversation context");
        }
        if (origin.agentId() == null) {
            return errorJson("setGoal requires an agent context");
        }

        GoalCreateRequest req = new GoalCreateRequest();
        req.setConversationId(origin.conversationId());
        req.setAgentId(origin.agentId());
        req.setWorkspaceId(origin.workspaceId() != null ? origin.workspaceId() : 1L);
        req.setTitle(title.trim());
        req.setDescription(description != null ? description : title.trim());
        req.setExitCriteria(exitCriteria);
        if (turnBudget != null) req.setTurnBudget(turnBudget);
        if (autoFollowup != null) req.setAutoFollowupEnabled(autoFollowup);

        String username = origin.requesterId() != null && !origin.requesterId().isBlank()
                ? origin.requesterId() : "system";
        try {
            GoalEntity created = goalService.create(req, username);
            broadcastGoalEvent(created.getConversationId(), "goal_created", created);
            return successJson(Map.of(
                    "goalId", String.valueOf(created.getId()),
                    "status", created.getStatus().getValue(),
                    "turnBudget", created.getTurnBudget(),
                    "llmCallBudget", created.getLlmCallBudget(),
                    "autoFollowup", created.getAutoFollowupEnabled()));
        } catch (MateClawException e) {
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = """
            Append a sub-criterion to the active goal without restarting it. \
            Use when the user adds a new requirement mid-task (e.g. 'also make \
            sure it works on Safari'). No-op if no active goal is bound.""")
    public String addGoalCriterion(
            @ToolParam(description = "Single new criterion sentence.") String criterion,
            @Nullable ToolContext ctx) {

        if (!properties.isEnabled()) return errorJson("Goal subsystem is disabled");
        if (criterion == null || criterion.isBlank()) {
            return errorJson("criterion must not be empty");
        }
        GoalEntity goal = resolveActive(ctx);
        if (goal == null) {
            return errorJson("No active goal on this conversation");
        }
        String username = resolveUsername(ctx);
        try {
            GoalEntity updated = goalService.appendCriterion(goal.getId(), criterion.trim(), username);
            broadcastGoalEvent(updated.getConversationId(), "goal_updated", updated);
            return successJson(Map.of(
                    "goalId", String.valueOf(updated.getId()),
                    "exitCriteria", updated.getExitCriteria() == null ? "" : updated.getExitCriteria()));
        } catch (MateClawException e) {
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = """
            Explicitly mark the active goal as completed. Use ONLY when all \
            exit criteria are satisfied (e.g. tests passed, feature deployed, \
            user confirmed). The runtime evaluator will also mark goals \
            completed automatically when score >= 0.95 — prefer that path.""")
    public String completeGoal(@Nullable ToolContext ctx) {
        if (!properties.isEnabled()) return errorJson("Goal subsystem is disabled");
        GoalEntity goal = resolveActive(ctx);
        if (goal == null) {
            return errorJson("No active goal on this conversation");
        }
        // Synthesize a completion-style evaluation result for the audit trail.
        GoalEvaluationResult synthetic = new GoalEvaluationResult(
                1.0, "completed by agent", GoalEvaluationResult.DECISION_COMPLETED,
                true, "manual", 0, 0L);
        try {
            GoalEntity completed = goalService.markCompleted(goal.getId(), synthetic);
            // Broadcast a goal_completed event with the same shape as the
            // GoalEvaluationNode auto-completed path, so the frontend
            // handler doesn't need to branch on which path completed it.
            if (streamTracker != null && completed.getConversationId() != null) {
                streamTracker.broadcastObject(completed.getConversationId(), "goal_completed", Map.of(
                        "goalId", String.valueOf(completed.getId()),
                        "score", synthetic.score()));
            }
            return successJson(Map.of(
                    "goalId", String.valueOf(completed.getId()),
                    "status", completed.getStatus().getValue()));
        } catch (MateClawException e) {
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = """
            Get the active goal's current status, progress score, and the most \
            recent gap text. Useful when the user asks 'how are we doing?' or \
            before deciding the next sub-step.""")
    public String getGoalStatus(@Nullable ToolContext ctx) {
        if (!properties.isEnabled()) return errorJson("Goal subsystem is disabled");
        GoalEntity goal = resolveActive(ctx);
        if (goal == null) {
            return successJson(Map.of("active", false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", true);
        out.put("goalId", String.valueOf(goal.getId()));
        out.put("title", goal.getTitle());
        out.put("status", goal.getStatus().getValue());
        out.put("turnsUsed", goal.getTurnsUsed());
        out.put("turnBudget", goal.getTurnBudget());
        out.put("agentLlmCallsUsed", goal.getAgentLlmCallsUsed());
        out.put("evalLlmCallsUsed", goal.getEvalLlmCallsUsed());
        out.put("totalLlmCallsUsed", goal.totalLlmCallsUsed());
        out.put("llmCallBudget", goal.getLlmCallBudget());
        out.put("completionScore", goal.getCompletionScore());
        out.put("progressSummary", goal.getProgressSummary());
        out.put("autoFollowupEnabled", goal.getAutoFollowupEnabled());
        return successJson(out);
    }

    // ==================== Internals ====================

    private GoalEntity resolveActive(ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        if (origin == null || origin.conversationId() == null) return null;
        return goalService.findActiveByConversation(origin.conversationId());
    }

    private String resolveUsername(ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        if (origin != null && origin.requesterId() != null && !origin.requesterId().isBlank()) {
            return origin.requesterId();
        }
        return "system";
    }

    private String successJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"ok\":true}";
        }
    }

    /**
     * Broadcast a goal-namespaced SSE event so the frontend store can
     * refresh its active-goal cache without waiting for the user to
     * reload. Best-effort: a missing stream (e.g. cron-origin tool call
     * with no SSE subscriber) is not an error path.
     */
    private void broadcastGoalEvent(String conversationId, String eventName, GoalEntity goal) {
        if (streamTracker == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        // Send the full goal payload so the store can hydrate without an
        // extra GET round-trip. Long IDs are stringified at the wire by
        // ToStringSerializer; the rest of the payload is plain JSON.
        try {
            streamTracker.broadcastObject(conversationId, eventName, Map.of(
                    "goalId", String.valueOf(goal.getId()),
                    "conversationId", conversationId,
                    "goal", goal));
        } catch (Exception e) {
            log.debug("[GoalManagementTool] broadcast {} failed: {}", eventName, e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "error", true,
                    "message", message != null ? message : ""));
        } catch (JsonProcessingException e) {
            return "{\"error\":true}";
        }
    }
}
