package vip.mate.goal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEventEntity;
import vip.mate.goal.model.GoalUpdateRequest;
import vip.mate.goal.service.GoalService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;

import java.util.List;
import java.util.Map;

/**
 * REST surface for persistent goals.
 *
 * <p>Authorization model: every write authorizes the caller against the
 * goal's bound conversation via
 * {@link ConversationService#isConversationOwner}, mirroring the
 * {@code SubagentController} pattern. Read endpoints follow the same rule
 * so non-owners cannot enumerate someone else's goals by guessing IDs.
 *
 * <p>Snowflake IDs are serialized as strings by the project-wide Jackson
 * config (see CLAUDE.md "ID Handling"); request DTOs accept either number
 * or string forms.
 */
@Slf4j
@Tag(name = "Goals")
@RestController
@RequestMapping("/api/v1/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;
    private final ConversationService conversationService;

    @Operation(summary = "Create a persistent goal for a conversation")
    @PostMapping
    public R<GoalEntity> create(@RequestBody GoalCreateRequest req, Authentication auth) {
        String username = currentUsername(auth);
        requireOwner(req.getConversationId(), username);
        // Derive agentId/workspaceId from the conversation itself so the
        // caller cannot attribute the goal to an unrelated agent/workspace
        // they don't own. The request fields for these two are intentionally
        // ignored — audit, memory sync, listing, and future policy hooks all
        // assume the goal mirrors its conversation's identity.
        ConversationEntity conv = conversationService.findByConversationId(req.getConversationId());
        if (conv == null) {
            throw new MateClawException("err.goal.conversation_not_found", 404,
                    "Conversation not found: " + req.getConversationId());
        }
        if (conv.getAgentId() == null) {
            throw new MateClawException("err.goal.conversation_has_no_agent", 409,
                    "Conversation " + req.getConversationId() + " has no bound agent");
        }
        req.setAgentId(conv.getAgentId());
        req.setWorkspaceId(conv.getWorkspaceId() != null ? conv.getWorkspaceId() : 1L);
        GoalEntity g = goalService.create(req, username);
        return R.ok(g);
    }

    @Operation(summary = "Get the active goal bound to a conversation (or null)")
    @GetMapping("/by-conversation/{conversationId}")
    public R<GoalEntity> findActive(@PathVariable String conversationId, Authentication auth) {
        requireOwner(conversationId, currentUsername(auth));
        return R.ok(goalService.findActiveByConversation(conversationId));
    }

    @Operation(summary = "Get goal detail by id")
    @GetMapping("/{id}")
    public R<GoalEntity> get(@PathVariable Long id, Authentication auth) {
        GoalEntity g = goalService.getById(id);
        requireOwner(g.getConversationId(), currentUsername(auth));
        return R.ok(g);
    }

    @Operation(summary = "Get the event timeline for a goal")
    @GetMapping("/{id}/events")
    public R<List<GoalEventEntity>> events(@PathVariable Long id,
                                            @RequestParam(defaultValue = "100") int limit,
                                            Authentication auth) {
        GoalEntity g = goalService.getById(id);
        requireOwner(g.getConversationId(), currentUsername(auth));
        return R.ok(goalService.listEvents(id, limit));
    }

    @Operation(summary = "List goals (optionally filtered by status)")
    @GetMapping
    public R<List<GoalEntity>> list(@RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "50") int limit,
                                     Authentication auth) {
        // List is owner-scoped — only your own goals are visible.
        return R.ok(goalService.list(status, currentUsername(auth), limit));
    }

    @Operation(summary = "Sparse update of a non-terminal goal")
    @PatchMapping("/{id}")
    public R<GoalEntity> update(@PathVariable Long id,
                                 @RequestBody GoalUpdateRequest req,
                                 Authentication auth) {
        GoalEntity g = goalService.getById(id);
        String username = currentUsername(auth);
        requireOwner(g.getConversationId(), username);
        return R.ok(goalService.update(id, req, username));
    }

    @Operation(summary = "Pause an active goal")
    @PostMapping("/{id}/pause")
    public R<GoalEntity> pause(@PathVariable Long id, Authentication auth) {
        GoalEntity g = goalService.getById(id);
        String username = currentUsername(auth);
        requireOwner(g.getConversationId(), username);
        return R.ok(goalService.pause(id, username));
    }

    @Operation(summary = "Resume a paused goal")
    @PostMapping("/{id}/resume")
    public R<GoalEntity> resume(@PathVariable Long id, Authentication auth) {
        GoalEntity g = goalService.getById(id);
        String username = currentUsername(auth);
        requireOwner(g.getConversationId(), username);
        return R.ok(goalService.resume(id, username));
    }

    @Operation(summary = "Abandon a goal (terminal)")
    @PostMapping("/{id}/abandon")
    public R<GoalEntity> abandon(@PathVariable Long id, Authentication auth) {
        GoalEntity g = goalService.getById(id);
        String username = currentUsername(auth);
        requireOwner(g.getConversationId(), username);
        return R.ok(goalService.abandon(id, username));
    }

    @Operation(summary = "Append a sub-criterion to an active goal")
    @PostMapping("/{id}/criteria")
    public R<GoalEntity> addCriterion(@PathVariable Long id,
                                       @RequestBody Map<String, String> body,
                                       Authentication auth) {
        GoalEntity g = goalService.getById(id);
        String username = currentUsername(auth);
        requireOwner(g.getConversationId(), username);
        String criterion = body != null ? body.get("criterion") : null;
        return R.ok(goalService.appendCriterion(id, criterion, username));
    }

    // ==================== Helpers ====================

    private String currentUsername(Authentication auth) {
        return auth != null ? auth.getName() : "anonymous";
    }

    private void requireOwner(String conversationId, String username) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new MateClawException("err.goal.bad_request", 400, "conversationId required");
        }
        if (!conversationService.isConversationOwner(conversationId, username)) {
            throw new MateClawException("err.goal.forbidden", 403,
                    "Not the owner of conversation " + conversationId);
        }
    }
}
