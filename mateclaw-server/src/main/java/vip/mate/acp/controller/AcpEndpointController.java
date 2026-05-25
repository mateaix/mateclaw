package vip.mate.acp.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.acp.service.AcpConnectionTester;
import vip.mate.acp.service.AcpEndpointService;
import vip.mate.common.result.R;

import java.util.List;
import java.util.Map;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * RFC-090 Phase 7 — REST surface for managing ACP endpoints.
 *
 * <p>Mirrors the McpServers controller so the frontend page can be a
 * close cousin of {@code McpServers.vue}.
 */
@Tag(name = "ACP Endpoints (RFC-090 Phase 7)")
@RestController
@RequestMapping("/api/v1/acp/endpoints")
@RequiredArgsConstructor
public class AcpEndpointController {

    private final AcpEndpointService service;
    private final AcpConnectionTester tester;

    @Operation(summary = "List ACP endpoints")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<AcpEndpointEntity>> list() {
        return R.ok(service.list());
    }

    @Operation(summary = "Get ACP endpoint by id")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<AcpEndpointEntity> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @Operation(summary = "Create a custom ACP endpoint")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<AcpEndpointEntity> create(@RequestBody AcpEndpointEntity body) {
        return R.ok(service.create(body));
    }

    @Operation(summary = "Update an ACP endpoint")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<AcpEndpointEntity> update(@PathVariable Long id,
                                        @RequestBody AcpEndpointEntity body) {
        return R.ok(service.update(id, body));
    }

    @Operation(summary = "Delete an ACP endpoint (builtins are protected)")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @Operation(summary = "Enable / disable an ACP endpoint")
    @PutMapping("/{id}/toggle")
    @RequireWorkspaceRole("admin")
    public R<AcpEndpointEntity> toggle(@PathVariable Long id,
                                        @RequestParam boolean enabled) {
        return R.ok(service.toggle(id, enabled));
    }

    /**
     * Spawn the configured CLI, run {@code initialize} + {@code
     * session/new}, persist the outcome, and return diagnostics.
     */
    @Operation(summary = "Test ACP endpoint connection (initialize handshake)")
    @PostMapping("/{id}/test")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> test(@PathVariable Long id) {
        AcpEndpointEntity endpoint = service.get(id);
        return R.ok(tester.testEndpoint(endpoint));
    }
}
