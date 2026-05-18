package vip.mate.datasource.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.datasource.service.DatasourceService;

import java.util.List;
import java.util.Map;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * 数据源管理接口
 *
 * @author MateClaw Team
 */
@Tag(name = "数据源管理")
@RestController
@RequestMapping("/api/v1/datasources")
@RequiredArgsConstructor
public class DatasourceController {

    private final DatasourceService datasourceService;

    @Operation(summary = "获取数据源列表")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<DatasourceEntity>> list() {
        return R.ok(datasourceService.listAll());
    }

    @Operation(summary = "获取数据源详情")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<DatasourceEntity> get(@PathVariable Long id) {
        return R.ok(datasourceService.getByIdMasked(id));
    }

    @Operation(summary = "创建数据源")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<DatasourceEntity> create(@RequestBody DatasourceEntity entity) {
        return R.ok(datasourceService.create(entity));
    }

    @Operation(summary = "更新数据源")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<DatasourceEntity> update(@PathVariable Long id, @RequestBody DatasourceEntity entity) {
        entity.setId(id);
        return R.ok(datasourceService.update(entity));
    }

    @Operation(summary = "删除数据源")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> delete(@PathVariable Long id) {
        datasourceService.delete(id);
        return R.ok();
    }

    @Operation(summary = "测试数据源连接")
    @PostMapping("/{id}/test")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> testConnection(@PathVariable Long id) {
        boolean ok = datasourceService.testConnection(id);
        return R.ok(Map.of("success", ok, "message", ok ? "连接成功" : "连接失败"));
    }

    @Operation(summary = "启用/禁用数据源")
    @PutMapping("/{id}/toggle")
    @RequireWorkspaceRole("admin")
    public R<DatasourceEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        return R.ok(datasourceService.toggle(id, enabled));
    }
}
