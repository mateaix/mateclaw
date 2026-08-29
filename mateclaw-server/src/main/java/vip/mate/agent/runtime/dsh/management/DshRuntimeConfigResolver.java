package vip.mate.agent.runtime.dsh.management;

import java.util.Map;

/** Resolves managed settings first, then application properties, then legacy environment variables. */
public final class DshRuntimeConfigResolver {

    private DshRuntimeConfigResolver() {
    }

    public static DshRuntimeConfiguration resolve(
            Map<String, String> managed,
            Map<String, String> properties,
            Map<String, String> environment) {
        return new DshRuntimeConfiguration(
                firstNonBlank(managed, properties, environment,
                        "dsh.executable_path", "mateclaw.agent.runtime.dsh.command", "DSH_JSONRPC_AGENT"),
                firstNonBlank(managed, properties, environment,
                        "dsh.cordis_config_path", "mateclaw.agent.runtime.dsh.cordis-config", "DSH_CORDIS_CONFIG"),
                firstNonBlank(managed, properties, environment,
                        "dsh.working_directory", "mateclaw.agent.runtime.dsh.working-directory", "DSH_CWD"),
                firstNonBlank(managed, properties, environment,
                        "dsh.base_url", "mateclaw.agent.runtime.dsh.base-url", "DEEPSEEK_BASE_URL"),
                firstNonBlank(managed, properties, environment,
                        "dsh.model_name", "mateclaw.agent.runtime.dsh.model-name", "DEEPSEEK_MODEL"),
                firstNonBlank(managed, properties, environment,
                        "dsh.api_key", "mateclaw.agent.runtime.dsh.api-key", "DEEPSEEK_API_KEY"));
    }

    private static String firstNonBlank(
            Map<String, String> managed,
            Map<String, String> properties,
            Map<String, String> environment,
            String managedKey,
            String propertyKey,
            String environmentKey) {
        String value = value(managed, managedKey);
        if (value != null) {
            return value;
        }
        value = value(properties, propertyKey);
        if (value != null) {
            return value;
        }
        return value(environment, environmentKey);
    }

    private static String value(Map<String, String> values, String key) {
        if (values == null) {
            return null;
        }
        String value = values.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
