package vip.mate.agent.runtime.dsh.management;

import org.springframework.stereotype.Service;
import vip.mate.system.service.SystemSettingService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class DshManagementService {
    private static final String ENABLED_KEY = "dsh.enabled";

    private final DshRuntimeConfigService configService;
    private final DshArtifactInstaller installer;
    private final SystemSettingService settings;

    public DshManagementService(DshRuntimeConfigService configService,
                                DshArtifactInstaller installer,
                                SystemSettingService settings) {
        this.configService = configService;
        this.installer = installer;
        this.settings = settings;
    }

    public Map<String, Object> status() {
        DshRuntimeConfiguration configuration = configService.resolve();
        boolean executableAvailable = isExecutable(configuration.executablePath());
        boolean workingDirectoryAvailable = configuration.workingDirectory() != null
                && Files.isDirectory(Path.of(configuration.workingDirectory()));
        boolean cordisAvailable = configuration.cordisConfigPath() == null
                || configuration.cordisConfigPath().isBlank()
                || Files.isRegularFile(Path.of(configuration.cordisConfigPath()));
        // An empty managed key is valid: DshRuntimeService can reuse the
        // existing DeepSeek provider key. The page may still store a managed
        // key when the operator wants DSH to be independent from model rows.
        boolean enabled = settings.getBool(ENABLED_KEY, false);
        DshManagementState state;
        if (!executableAvailable) state = DshManagementState.NOT_INSTALLED;
        else if (!workingDirectoryAvailable || !cordisAvailable) state = DshManagementState.CONFIG_INVALID;
        else if (enabled) state = DshManagementState.ENABLED;
        else state = DshManagementState.READY;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state.name());
        result.put("installed", executableAvailable);
        result.put("enabled", enabled);
        result.put("config", configuration.publicStatus());
        result.put("managed", configService.managedValues());
        result.put("artifactManifestConfigured", installer.manifestConfigured());
        result.put("privateArtifactManifestConfigured", installer.privateManifestConfigured());
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    public Map<String, Object> saveConfig(Map<String, String> values) {
        configService.save(values);
        return status();
    }

    public Map<String, Object> install() throws Exception {
        DshArtifactManifest manifest = installer.loadManifest();
        Path executable = installer.install(manifest);
        Map<String, String> installed = new LinkedHashMap<>();
        installed.put("dsh.executable_path", executable.toString());
        Path cordis = installer.installedCordisConfig();
        if (cordis != null) installed.put("dsh.cordis_config_path", cordis.toString());
        configService.save(installed);
        return status();
    }

    public Map<String, Object> verify() {
        Map<String, Object> result = status();
        boolean ok = "ENABLED".equals(result.get("state")) || "READY".equals(result.get("state"));
        result.put("verified", ok);
        result.put("verificationMessage", ok ? "DSH executable and configuration are available" : "DSH executable or configuration is unavailable");
        return result;
    }

    public Map<String, Object> testConnection() {
        DshRuntimeConfiguration configuration = configService.resolve();
        if (!isExecutable(configuration.executablePath())) return Map.of("success", false, "message", "DSH executable is unavailable");
        if (configuration.cordisConfigPath() == null || configuration.cordisConfigPath().isBlank()) {
            return Map.of("success", false, "message", "DSH Cordis configuration is unavailable");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(configuration.executablePath(), configuration.cordisConfigPath())
                    .directory(Path.of(configuration.workingDirectory()).toFile())
                    .redirectErrorStream(true);
            builder.environment().put("DSH_CWD", configuration.workingDirectory());
            builder.environment().put("DSH_CORDIS_CONFIG", configuration.cordisConfigPath());
            if (configuration.apiKey() != null && !configuration.apiKey().isBlank()) {
                builder.environment().put("DEEPSEEK_API_KEY", configuration.apiKey());
            }
            if (configuration.baseUrl() != null && !configuration.baseUrl().isBlank()) {
                builder.environment().put("DEEPSEEK_BASE_URL", configuration.baseUrl());
            }
            Process process = builder.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Map.of("success", true, "message", "DSH process started");
            }
            String output = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) throw new IllegalStateException(output.isBlank() ? "DSH process exited with code " + process.exitValue() : output.trim());
            return Map.of("success", true, "message", output.trim());
        } catch (Exception error) {
            return Map.of("success", false, "message", "DSH connection test failed: " + error.getMessage());
        }
    }

    public Map<String, Object> enable() {
        Map<String, Object> current = verify();
        if (!Boolean.TRUE.equals(current.get("verified"))) throw new IllegalStateException("DSH must pass verification before enabling");
        settings.saveBool(ENABLED_KEY, true, "Enable managed DeepSeek Harness runtime");
        return status();
    }

    public Map<String, Object> disable() {
        settings.saveBool(ENABLED_KEY, false, "Enable managed DeepSeek Harness runtime");
        return status();
    }

    private boolean isExecutable(String path) {
        return path != null && !path.isBlank() && Files.isExecutable(Path.of(path));
    }
}
