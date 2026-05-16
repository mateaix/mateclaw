package vip.mate.plugin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.plugin.PluginManager;
import vip.mate.plugin.model.PluginInfo;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * Plugin management REST API.
 *
 * @author MateClaw Team
 */
@Tag(name = "Plugin Management")
@RestController
@RequestMapping("/api/v1/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginManager pluginManager;

    @Operation(summary = "List all plugins")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<PluginInfo>> list() {
        return R.ok(pluginManager.listPlugins());
    }

    @Operation(summary = "Get plugin detail")
    @GetMapping("/{name}")
    @RequireWorkspaceRole("admin")
    public R<PluginInfo> get(@PathVariable String name) {
        return R.ok(pluginManager.getPlugin(name));
    }

    @Operation(summary = "Disable a plugin")
    @PostMapping("/{name}/disable")
    @RequireWorkspaceRole("admin")
    public R<Void> disable(@PathVariable String name) {
        pluginManager.disablePlugin(name);
        return R.ok();
    }

    @Operation(summary = "Enable a plugin")
    @PostMapping("/{name}/enable")
    @RequireWorkspaceRole("admin")
    public R<Void> enable(@PathVariable String name) {
        pluginManager.enablePlugin(name);
        return R.ok();
    }

    @Operation(summary = "Update plugin configuration")
    @PutMapping("/{name}/config")
    @RequireWorkspaceRole("admin")
    public R<Void> updateConfig(@PathVariable String name,
                                @RequestBody Map<String, Object> config) {
        pluginManager.updateConfig(name, config);
        return R.ok();
    }
}
