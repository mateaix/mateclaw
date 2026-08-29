package vip.mate.agent.runtime.dsh.management;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DshRuntimeConfigResolverTest {

    @Test
    void databaseValuesOverrideEnvironmentFallback() {
        DshRuntimeConfiguration resolved = DshRuntimeConfigResolver.resolve(
                Map.of(
                        "dsh.executable_path", "/managed/dsh-agent",
                        "dsh.cordis_config_path", "/managed/cordis.yml",
                        "dsh.working_directory", "/managed/workspace",
                        "dsh.base_url", "https://managed.example.com",
                        "dsh.model_name", "managed-model",
                        "dsh.api_key", "managed-secret"),
                Map.of(
                        "mateclaw.agent.runtime.dsh.command", "/legacy/dsh-agent",
                        "mateclaw.agent.runtime.dsh.cordis-config", "/legacy/cordis.yml"),
                Map.of(
                        "DSH_JSONRPC_AGENT", "/env/dsh-agent",
                        "DSH_CORDIS_CONFIG", "/env/cordis.yml",
                        "DSH_CWD", "/env/workspace"));

        assertEquals("/managed/dsh-agent", resolved.executablePath());
        assertEquals("/managed/cordis.yml", resolved.cordisConfigPath());
        assertEquals("/managed/workspace", resolved.workingDirectory());
        assertEquals("https://managed.example.com", resolved.baseUrl());
        assertEquals("managed-model", resolved.modelName());
        assertEquals("managed-secret", resolved.apiKey());
    }

    @Test
    void blankDatabaseValuesFallBackToPropertiesThenEnvironment() {
        DshRuntimeConfiguration resolved = DshRuntimeConfigResolver.resolve(
                Map.of(
                        "dsh.executable_path", "",
                        "dsh.cordis_config_path", "",
                        "dsh.working_directory", ""),
                Map.of(
                        "mateclaw.agent.runtime.dsh.command", "/properties/dsh-agent",
                        "mateclaw.agent.runtime.dsh.cordis-config", "/properties/cordis.yml"),
                Map.of(
                        "DSH_JSONRPC_AGENT", "/env/dsh-agent",
                        "DSH_CORDIS_CONFIG", "/env/cordis.yml",
                        "DSH_CWD", "/env/workspace"));

        assertEquals("/properties/dsh-agent", resolved.executablePath());
        assertEquals("/properties/cordis.yml", resolved.cordisConfigPath());
        assertEquals("/env/workspace", resolved.workingDirectory());
    }

    @Test
    void apiKeyIsExcludedFromPublicStatusProjection() {
        DshRuntimeConfiguration resolved = DshRuntimeConfigResolver.resolve(
                Map.of("dsh.api_key", "super-secret"), Map.of(), Map.of());

        Map<String, Object> status = resolved.publicStatus();

        assertEquals(true, status.get("apiKeyConfigured"));
        assertFalse(status.containsKey("apiKey"));
        assertNull(status.get("apiKey"));
    }
}
