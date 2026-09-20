package vip.mate.execution.evidence.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;
import vip.mate.execution.evidence.service.JsonArtifactRecipe;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.execution.evidence.service.ExecutionEvidenceQueryService;

/** Source-authorized, read-only observations. There is deliberately no evidence write endpoint. */
@RestController
@RequestMapping("/api/v1/execution-evidence")
@RequiredArgsConstructor
public class ExecutionEvidenceController {
    private final ExecutionEvidenceQueryService queries;

    @GetMapping
    public R<ExecutionEvidenceQueryService.Page> list(Authentication auth,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam String conversationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long goalId,
            @RequestParam(required = false) Long teamTaskId) {
        return R.ok(queries.list(auth == null ? null : auth.getName(), workspaceId, conversationId,
                cursor, limit, goalId, teamTaskId));
    }

    @GetMapping("/{id}")
    public R<ExecutionEvidenceQueryService.View> detail(Authentication auth,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @PathVariable Long id) {
        return R.ok(queries.detail(auth == null ? null : auth.getName(), workspaceId, id));
    }
    public record JsonCheckRequest(List<String> requiredFields) { }

    @PostMapping("/{id}/json-check")
    public R<JsonArtifactRecipe.Result> checkJson(Authentication auth,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @PathVariable Long id, @RequestBody JsonCheckRequest request) {
        return R.ok(queries.checkJson(auth == null ? null : auth.getName(), workspaceId, id,
                request == null ? null : request.requiredFields()));
    }

}
