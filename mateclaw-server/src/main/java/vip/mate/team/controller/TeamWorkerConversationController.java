package vip.mate.team.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.team.service.TeamWorkerConversationContext;
import vip.mate.team.service.TeamWorkerConversationGovernanceService;
import vip.mate.workspace.conversation.ConversationService;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class TeamWorkerConversationController {

    private final ConversationService conversationService;
    private final TeamWorkerConversationGovernanceService governanceService;

    @GetMapping("/{conversationId}/team-worker-context")
    public R<TeamWorkerConversationContext> context(
            @PathVariable String conversationId,
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Long taskId,
            Authentication authentication) {
        String username = authentication == null ? "anonymous" : authentication.getName();
        if (!conversationService.isConversationOwner(conversationId, username)
                && !governanceService.canReadTranscript(conversationId, runId, taskId, username)) {
            return R.fail(403, "无权访问该会话");
        }
        return governanceService.resolve(conversationId, runId, taskId)
                .map(R::ok)
                .orElseGet(() -> R.ok(null));
    }
}
