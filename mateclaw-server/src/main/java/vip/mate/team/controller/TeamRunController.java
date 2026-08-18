package vip.mate.team.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.service.TeamRunApplicationService;
import vip.mate.team.service.TeamRunService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.function.Supplier;

/** Workspace-scoped REST API for team run reads and cancellation. */
@Tag(name = "Team Runs")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TeamRunController {

    private final TeamRunService runService;
    private final TeamRunApplicationService applicationService;

    @Operation(summary = "Get team run")
    @GetMapping("/team-runs/{runId}")
    @RequireWorkspaceRole("viewer")
    public R<TeamRunView> get(@PathVariable Long runId,
                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return guarded(() -> R.ok(runService.getRun(runId, workspaceId(workspaceId))));
    }

    @Operation(summary = "List team runs")
    @GetMapping("/teams/{teamId}/runs")
    @RequireWorkspaceRole("viewer")
    public R<List<TeamRunView>> listTeamRuns(
            @PathVariable Long teamId,
            @RequestParam(value = "activeOnly", defaultValue = "false") boolean activeOnly,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return guarded(() -> R.ok(runService.listTeamRuns(teamId, workspaceId(workspaceId), activeOnly)));
    }

    @Operation(summary = "Page team runs")
    @GetMapping("/teams/{teamId}/runs/page")
    @RequireWorkspaceRole("viewer")
    public R<TeamRunService.RunPage> pageTeamRuns(
            @PathVariable Long teamId,
            @RequestParam(value = "activeOnly", defaultValue = "false") boolean activeOnly,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return guarded(() -> R.ok(runService.pageTeamRuns(
                teamId, workspaceId(workspaceId), activeOnly, cursor, limit)));
    }

    @Operation(summary = "List conversation team runs")
    @GetMapping("/conversations/{conversationId}/team-runs")
    @RequireWorkspaceRole("viewer")
    public R<List<TeamRunView>> listConversationRuns(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return guarded(() -> R.ok(runService.listConversationRuns(
                conversationId, workspaceId(workspaceId))));
    }

    @Operation(summary = "Page conversation team runs")
    @GetMapping("/conversations/{conversationId}/team-runs/page")
    @RequireWorkspaceRole("viewer")
    public R<TeamRunService.RunPage> pageConversationRuns(
            @PathVariable String conversationId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return guarded(() -> R.ok(runService.pageConversationRuns(
                conversationId, workspaceId(workspaceId), cursor, limit)));
    }

    @Operation(summary = "Cancel team run")
    @PostMapping("/team-runs/{runId}/cancel")
    @RequireWorkspaceRole("admin")
    public R<TeamRunView> cancel(
            @PathVariable Long runId,
            @RequestBody(required = false) CancelRunRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return guarded(() -> R.ok(applicationService.cancelRun(runId, workspaceId(workspaceId),
                request == null ? null : request.getReason())));
    }

    private long workspaceId(Long workspaceId) {
        return workspaceId == null ? 1L : workspaceId;
    }

    private <T> R<T> guarded(Supplier<R<T>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @Data
    public static class CancelRunRequest {
        private String reason;
    }
}
