package vip.mate.planning.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.planning.model.PlanEntity;
import vip.mate.planning.service.PlanningService;

import java.util.List;

/**
 * 任务规划接口
 *
 * @author MateClaw Team
 */
@Tag(name = "任务规划")
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanningController {

    private final PlanningService planningService;

    @Operation(summary = "获取计划列表（带 agentId 则按员工，否则跨员工取最近 N 条）")
    @GetMapping
    public R<List<PlanEntity>> list(@RequestParam(required = false) String agentId,
                                    @RequestParam(required = false, defaultValue = "100") int limit) {
        if (agentId != null && !agentId.isBlank()) {
            return R.ok(planningService.listPlansByAgent(agentId));
        }
        return R.ok(planningService.listRecentPlans(limit));
    }

    @Operation(summary = "获取计划详情（含步骤）")
    @GetMapping("/{id}")
    public R<PlanEntity> getPlan(@PathVariable Long id) {
        return R.ok(planningService.getPlanWithSteps(id));
    }
}
