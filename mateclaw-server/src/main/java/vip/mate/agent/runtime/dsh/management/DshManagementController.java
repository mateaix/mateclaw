package vip.mate.agent.runtime.dsh.management;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

import java.util.Map;

@Tag(name = "DeepSeek Harness Runtime Management")
@RestController
@RequestMapping("/api/v1/admin/dsh")
@RequiredArgsConstructor
public class DshManagementController {
    private final DshManagementService managementService;

    @Operation(summary = "Get managed DSH runtime status")
    @GetMapping("/status")
    @RequireGlobalAdmin
    public R<Map<String, Object>> status() { return R.ok(managementService.status()); }

    @Operation(summary = "Save managed DSH runtime configuration")
    @PutMapping("/config")
    @RequireGlobalAdmin
    public R<Map<String, Object>> saveConfig(@RequestBody Map<String, String> values) {
        return R.ok(managementService.saveConfig(values));
    }

    @Operation(summary = "Install the server-selected DSH artifact")
    @PostMapping("/install")
    @RequireGlobalAdmin
    public R<Map<String, Object>> install() throws Exception { return R.ok(managementService.install()); }

    @Operation(summary = "Verify DSH runtime configuration")
    @PostMapping("/verify")
    @RequireGlobalAdmin
    public R<Map<String, Object>> verify() { return R.ok(managementService.verify()); }

    @Operation(summary = "Test starting the DSH process")
    @PostMapping("/test-connection")
    @RequireGlobalAdmin
    public R<Map<String, Object>> testConnection() { return R.ok(managementService.testConnection()); }

    @Operation(summary = "Enable managed DSH runtime")
    @PostMapping("/enable")
    @RequireGlobalAdmin
    public R<Map<String, Object>> enable() { return R.ok(managementService.enable()); }

    @Operation(summary = "Disable managed DSH runtime")
    @PostMapping("/disable")
    @RequireGlobalAdmin
    public R<Map<String, Object>> disable() { return R.ok(managementService.disable()); }
}
