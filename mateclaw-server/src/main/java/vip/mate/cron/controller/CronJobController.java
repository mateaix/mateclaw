package vip.mate.cron.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.cron.model.CronJobDTO;
import vip.mate.cron.service.CronJobService;
import vip.mate.dashboard.model.ActiveCronRunVO;
import vip.mate.dashboard.service.CronJobRunService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/**
 * 定时任务管理接口
 *
 * @author MateClaw Team
 */
@Tag(name = "定时任务管理")
@RestController
@RequestMapping("/api/v1/cron-jobs")
@RequiredArgsConstructor
public class CronJobController {

    private final CronJobService cronJobService;
    private final CronJobRunService cronJobRunService;

    /**
     * RFC-083: every endpoint reads {@code X-Workspace-Id} (the frontend
     * axios interceptor already injects it). Service-layer filtering is
     * required because {@link vip.mate.config.WorkspaceAccessInterceptor}
     * skips its membership check entirely for global {@code admin} users —
     * relying on the interceptor alone leaks cron jobs across workspaces.
     */
    private static final long DEFAULT_WORKSPACE_ID = 1L;

    @Operation(summary = "获取定时任务列表")
    @GetMapping
    @RequireWorkspaceRole("member")
    public R<List<CronJobDTO>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(cronJobService.list(resolve(workspaceId)));
    }

    @Operation(summary = "获取定时任务详情")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<CronJobDTO> get(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(cronJobService.getById(id, resolve(workspaceId)));
    }

    @Operation(summary = "创建定时任务")
    @PostMapping
    @RequireWorkspaceRole("member")
    public R<CronJobDTO> create(@RequestBody CronJobDTO dto,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(cronJobService.create(dto, resolve(workspaceId)));
    }

    @Operation(summary = "更新定时任务")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<CronJobDTO> update(@PathVariable Long id, @RequestBody CronJobDTO dto,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(cronJobService.update(id, dto, resolve(workspaceId)));
    }

    @Operation(summary = "删除定时任务")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<Void> delete(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        cronJobService.delete(id, resolve(workspaceId));
        return R.ok();
    }

    @Operation(summary = "启用/禁用定时任务")
    @PutMapping("/{id}/toggle")
    @RequireWorkspaceRole("member")
    public R<Void> toggle(@PathVariable Long id, @RequestParam boolean enabled,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        cronJobService.toggle(id, enabled, resolve(workspaceId));
        return R.ok();
    }

    @Operation(summary = "立即执行定时任务")
    @PostMapping("/{id}/run")
    @RequireWorkspaceRole("member")
    public R<Void> runNow(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        cronJobService.runNow(id, resolve(workspaceId));
        return R.ok();
    }

    /**
     * Lightweight poll target for the chat console. Returns the cron job runs
     * currently in {@code running} state for the given conversation, so the
     * UI can render a "executing…" placeholder bubble between T1 (run row
     * inserted) and T2 (assistant message persisted).
     */
    @Operation(summary = "查询会话下正在执行的定时任务运行")
    @GetMapping("/active-runs")
    @RequireWorkspaceRole("member")
    public R<List<ActiveCronRunVO>> activeRuns(
            @RequestParam("conversationId") String conversationId) {
        return R.ok(cronJobRunService.listActiveByConversation(conversationId));
    }

    private static long resolve(Long headerWorkspaceId) {
        return headerWorkspaceId != null ? headerWorkspaceId : DEFAULT_WORKSPACE_ID;
    }
}
