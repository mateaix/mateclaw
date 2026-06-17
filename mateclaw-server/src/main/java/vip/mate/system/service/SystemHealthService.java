package vip.mate.system.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.config.DatabaseBootstrapRunner;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ProviderInfoDTO;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.tool.browser.BrowserDiagnosticsService;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.runtime.McpClientManager;
import vip.mate.tool.mcp.runtime.McpClientManager.ConnectionResult;
import vip.mate.tool.mcp.service.McpServerService;

import java.util.ArrayList;
import java.util.List;

/**
 * System health check service.
 * <p>
 * Inspects default model, provider configurations, MCP server connections,
 * and database initialization status.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemHealthService {

    private final ModelConfigService modelConfigService;
    private final ModelProviderService modelProviderService;
    private final McpClientManager mcpClientManager;
    private final McpServerService mcpServerService;
    private final DatabaseBootstrapRunner bootstrapRunner;
    private final BrowserDiagnosticsService browserDiagnostics;

    public HealthResponse check() {
        List<HealthCheck> checks = new ArrayList<>();

        // 1. Default model check
        checks.add(checkDefaultModel());

        // 2. Provider checks (only providers that require API keys)
        checks.addAll(checkProviders());

        // 3. MCP server checks (enabled servers only)
        checks.addAll(checkMcpServers());

        // 4. Database initialization check
        checks.add(checkDatabase());

        // 5. Browser launch pre-flight (common failure source on fresh win/linux hosts)
        checks.add(checkBrowser());

        // Determine overall status
        String overall = "healthy";
        for (HealthCheck c : checks) {
            if ("error".equals(c.status())) {
                overall = "error";
                break;
            }
            if ("warning".equals(c.status())) {
                overall = "warning";
            }
        }

        return new HealthResponse(overall, checks, bootstrapRunner.getDatabaseLabel());
    }

    private HealthCheck checkDefaultModel() {
        try {
            var model = modelConfigService.getDefaultModel();
            return new HealthCheck(
                    "default-model",
                    "healthy",
                    "Default model: " + model.getName(),
                    null
            );
        } catch (MateClawException e) {
            return new HealthCheck(
                    "default-model",
                    "error",
                    e.getMessage(),
                    new HealthAction("Configure Model", "/settings/models")
            );
        }
    }

    private List<HealthCheck> checkProviders() {
        List<HealthCheck> results = new ArrayList<>();
        try {
            List<ProviderInfoDTO> providers = modelProviderService.listProviders();
            for (ProviderInfoDTO provider : providers) {
                // Only check providers that require an API key
                if (!Boolean.TRUE.equals(provider.getRequireApiKey())) {
                    continue;
                }
                String providerId = provider.getId();
                boolean configured = modelProviderService.isProviderConfigured(providerId);
                if (!configured) {
                    String reason = modelProviderService.getProviderUnavailableReason(providerId);
                    results.add(new HealthCheck(
                            "provider:" + providerId,
                            "warning",
                            provider.getName() + " - " + (reason != null ? reason : "Not configured"),
                            new HealthAction("Configure", "/settings/models")
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check providers: {}", e.getMessage());
            results.add(new HealthCheck(
                    "providers",
                    "warning",
                    "Unable to check providers: " + e.getMessage(),
                    null
            ));
        }
        return results;
    }

    private List<HealthCheck> checkMcpServers() {
        List<HealthCheck> results = new ArrayList<>();
        try {
            List<McpServerEntity> servers = mcpServerService.listAll();
            for (McpServerEntity server : servers) {
                if (!Boolean.TRUE.equals(server.getEnabled())) {
                    continue;
                }
                ConnectionResult cr = mcpClientManager.getConnectionResult(server.getId());
                if (cr == null || !cr.success()) {
                    String msg = server.getName() + " - "
                            + (cr != null ? cr.message() : "Not connected");
                    results.add(new HealthCheck(
                            "mcp:" + server.getName(),
                            "warning",
                            msg,
                            new HealthAction("View Servers", "/settings/mcp-servers")
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check MCP servers: {}", e.getMessage());
            results.add(new HealthCheck(
                    "mcp-servers",
                    "warning",
                    "Unable to check MCP servers: " + e.getMessage(),
                    null
            ));
        }
        return results;
    }

    private HealthCheck checkDatabase() {
        if (bootstrapRunner.isInitialized()) {
            return new HealthCheck("database", "healthy", "Database initialized", null);
        }
        return new HealthCheck(
                "database",
                "error",
                "Database not initialized",
                new HealthAction("Setup", "/setup")
        );
    }

    private HealthCheck checkBrowser() {
        try {
            BrowserDiagnosticsService.Report report = browserDiagnostics.run();
            String status = switch (report.overall()) {
                case "healthy" -> "healthy";
                case "warning" -> "warning";
                default -> "error";
            };
            String message = "healthy".equals(report.overall())
                    ? "Browser launch ready"
                    : String.join(" | ", report.advice());
            HealthAction action = "healthy".equals(report.overall())
                    ? null
                    : new HealthAction("Diagnose", "/api/v1/system/browser-health");
            return new HealthCheck("browser", status, message, action);
        } catch (Exception e) {
            log.warn("Browser diagnostics failed: {}", e.getMessage());
            return new HealthCheck("browser", "warning",
                    "Browser diagnostics failed: " + e.getMessage(), null);
        }
    }

    // ==================== Response Records ====================

    public record HealthResponse(String overall, List<HealthCheck> checks, String database) {}

    public record HealthCheck(String name, String status, String message, HealthAction action) {}

    public record HealthAction(String label, String route) {}
}
