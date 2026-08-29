package vip.mate.agent.runtime.dsh.management;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolved DSH settings. The API key is deliberately omitted from public projections. */
public record DshRuntimeConfiguration(
        String executablePath,
        String cordisConfigPath,
        String workingDirectory,
        String baseUrl,
        String modelName,
        String apiKey) {

    public Map<String, Object> publicStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("executablePath", executablePath);
        status.put("cordisConfigPath", cordisConfigPath);
        status.put("workingDirectory", workingDirectory);
        status.put("baseUrl", baseUrl);
        status.put("modelName", modelName);
        status.put("apiKeyConfigured", apiKey != null && !apiKey.isBlank());
        return status;
    }
}
