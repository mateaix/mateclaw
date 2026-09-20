package vip.mate.tool.guard.guardian;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.model.*;
import vip.mate.tool.guard.service.ToolGuardConfigService;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件路径安全守卫
 * <p>
 * 检测工具调用中对敏感文件/目录的访问。
 * alwaysRun=true，不受 guarded tools 范围限制。
 * <p>
 * 优先从 ToolGuardConfigService 读取配置的敏感路径，合并默认路径。
 */
@Slf4j
@Component
public class FilePathGuardian implements ToolGuardGuardian {

    private static final Set<String> DEFAULT_SENSITIVE_FILES = Set.of(
            "/etc/passwd",
            "/etc/shadow",
            "/etc/sudoers"
    );

    private static final Set<String> DEFAULT_SENSITIVE_DIRS;
    static {
        Set<String> dirs = new HashSet<>();
        String home = System.getProperty("user.home", "~");
        dirs.add(home + "/.ssh/");
        dirs.add(home + "/.aws/");
        dirs.add(home + "/.gnupg/");
        dirs.add("/etc/ssh/");
        DEFAULT_SENSITIVE_DIRS = Set.copyOf(dirs);
    }

    private static final Set<String> SENSITIVE_FILE_PATTERNS = Set.of(
            ".env",
            ".env.local",
            ".env.production",
            "credentials.json",
            "service-account.json"
    );

    /** 已知文件工具的路径参数名（必须与 @ToolParam 声明的 JSON 键名一致） */
    private static final Map<String, String> TOOL_FILE_PARAMS = Map.of(
            "read_file", "filePath",
            "write_file", "filePath",
            "append_file", "filePath",
            "edit_file", "filePath",
            "file_read", "file_path",
            "file_write", "file_path"
    );

    private static final Set<String> SHELL_TOOL_NAMES = Set.of(
            "execute_shell_command", "shell_execute", "run_command"
    );

    private static final Pattern PATH_LIKE = Pattern.compile(
            "(?:^|[\\s\"'])(/[\\w./-]+|~[/\\w./-]+|\\./[\\w./-]+)"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolGuardConfigService configService;

    public FilePathGuardian(ToolGuardConfigService configService) {
        this.configService = configService;
    }

    @Override
    public boolean supports(ToolInvocationContext context) {
        return true;
    }

    @Override
    public boolean alwaysRun() {
        return true;
    }

    @Override
    public int priority() {
        return 300;
    }

    @Override
    public List<GuardFinding> evaluate(ToolInvocationContext context) {
        // 若 file guard 被配置禁用，跳过检查
        if (!configService.isFileGuardEnabled()) {
            return List.of();
        }

        List<String> paths = extractPaths(context);
        if (paths.isEmpty()) return List.of();

        List<GuardFinding> findings = new ArrayList<>();
        for (String rawPath : paths) {
            String normalized = normalizePath(rawPath);
            if (normalized == null) continue;

            if (isSensitive(normalized, rawPath)) {
                findings.add(new GuardFinding(
                        "SENSITIVE_FILE_ACCESS",
                        GuardSeverity.HIGH,
                        GuardCategory.SENSITIVE_FILE_ACCESS,
                        "敏感文件访问",
                        "检测到对敏感路径的访问: " + rawPath,
                        "请确认是否需要访问此路径",
                        context.toolName(),
                        "path",
                        rawPath,
                        rawPath
                ));
            }
        }
        return findings;
    }

    private List<String> extractPaths(ToolInvocationContext context) {
        List<String> paths = new ArrayList<>();
        String toolName = context.toolName();
        String rawArgs = context.rawArguments();
        if (rawArgs == null || rawArgs.isEmpty()) return paths;

        // 1. 已知文件工具：提取特定参数
        if (TOOL_FILE_PARAMS.containsKey(toolName)) {
            String paramName = TOOL_FILE_PARAMS.get(toolName);
            String pathValue = extractJsonParam(rawArgs, paramName);
            if (pathValue != null) paths.add(pathValue);
            return paths;
        }

        // 2. Shell 工具：从命令中提取路径
        if (SHELL_TOOL_NAMES.contains(toolName)) {
            String command = extractJsonParam(rawArgs, "command");
            if (command == null) command = rawArgs;
            extractPathsFromShellCommand(command, paths);
            return paths;
        }

        // 3. 其他工具：扫描所有字符串值
        extractPathsFromGenericArgs(rawArgs, paths);
        return paths;
    }

    private void extractPathsFromShellCommand(String command, List<String> paths) {
        Matcher matcher = PATH_LIKE.matcher(command);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
    }

    private void extractPathsFromGenericArgs(String rawArgs, List<String> paths) {
        try {
            Map<String, Object> params = objectMapper.readValue(rawArgs, new TypeReference<>() {});
            for (Object value : params.values()) {
                if (value instanceof String strVal && looksLikePath(strVal)) {
                    paths.add(strVal);
                }
            }
        } catch (Exception ignored) {
            // rawArgs 可能不是 JSON
            Matcher matcher = PATH_LIKE.matcher(rawArgs);
            while (matcher.find()) {
                paths.add(matcher.group(1));
            }
        }
    }

    private String extractJsonParam(String rawArgs, String paramName) {
        try {
            Map<String, Object> params = objectMapper.readValue(rawArgs, new TypeReference<>() {});
            Object val = params.get(paramName);
            return val instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean looksLikePath(String value) {
        return value.startsWith("/") || value.startsWith("~/") || value.startsWith("./");
    }

    private String normalizePath(String rawPath) {
        try {
            String expanded = rawPath;
            if (expanded.startsWith("~")) {
                expanded = System.getProperty("user.home", "") + expanded.substring(1);
            }
            return Paths.get(expanded).normalize().toString();
        } catch (Exception e) {
            return rawPath;
        }
    }

    private boolean isSensitive(String normalized, String rawPath) {
        // 精确匹配（默认敏感文件）
        if (DEFAULT_SENSITIVE_FILES.contains(normalized)) return true;

        // 目录前缀匹配（默认敏感目录）
        for (String dir : DEFAULT_SENSITIVE_DIRS) {
            String normalizedDir = normalizePath(dir);
            if (normalizedDir != null && (normalized.startsWith(normalizedDir)
                    || normalized.equals(normalizedDir.substring(0, normalizedDir.length() - 1)))) {
                return true;
            }
        }

        // 文件名模式匹配（.env 等）
        String fileName = Path.of(normalized).getFileName().toString();
        if (SENSITIVE_FILE_PATTERNS.contains(fileName)) return true;

        // 路径中包含密钥/凭据相关目录
        String lower = normalized.toLowerCase();
        if (lower.contains("/secrets/") || lower.contains("/credentials/")
                || lower.contains("/private-keys/")) {
            return true;
        }

        // 配置的自定义敏感路径（从 ToolGuardConfigService 加载）
        List<String> configPaths = configService.getSensitivePaths();
        for (String configPath : configPaths) {
            String normalizedConfig = normalizePath(configPath);
            if (normalizedConfig != null) {
                if (normalized.equals(normalizedConfig) || normalized.startsWith(normalizedConfig + "/")
                        || normalized.startsWith(normalizedConfig)) {
                    return true;
                }
            }
        }

        return false;
    }
}
