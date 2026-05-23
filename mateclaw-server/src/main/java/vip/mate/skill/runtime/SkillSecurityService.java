package vip.mate.skill.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.skill.runtime.model.ResolvedSkill;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 技能安全扫描服务
 * 静态规则扫描：路径逃逸、可疑脚本内容、非法结构
 * 按文件角色分层处理：scripts/ 严格扫描，文档类降级处理
 */
@Slf4j
@Service
public class SkillSecurityService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_FILES = 200;

    /**
     * 文件角色：决定扫描策略
     */
    public enum FileRole {
        /** 脚本文件：最严格扫描，可触发 block */
        SCRIPT,
        /** 文档文件：降级扫描，示例命令不触发 block */
        DOCUMENTATION,
        /** 配置文件：中等严格度 */
        CONFIG
    }

    // ==================== 扫描规则定义 ====================

    private record ScanRule(
        String ruleId,
        String category,
        SkillValidationResult.Severity severity,
        Pattern pattern,
        String title,
        String description,
        String remediation
    ) {}

    /**
     * 脚本级规则：用于 scripts/ 目录，严格扫描，可触发 block
     */
    private final List<ScanRule> scriptRules = List.of(
        // Critical: 反向 shell
        rule("REVERSE_SHELL", "CODE_EXECUTION", SkillValidationResult.Severity.CRITICAL,
            "(?i)(bash\\s+-i\\s+>\\s*&|/dev/tcp/|nc\\s+-e|ncat\\s+-e|python\\s+-c.*socket|perl\\s+-e.*socket|php\\s+-r.*fsockopen)",
            "Reverse shell pattern detected",
            "Code contains patterns commonly used in reverse shell attacks",
            "Remove network socket code that establishes outbound connections to arbitrary hosts"),
        // Critical: 系统破坏
        rule("DESTRUCTIVE_RM", "SYSTEM_DESTRUCTION", SkillValidationResult.Severity.CRITICAL,
            "\\brm\\s+-r?f\\s+/(?!tmp\\b)(?!var/tmp\\b)",
            "Destructive rm -rf on system paths",
            "Attempts to recursively delete files outside safe temporary directories",
            "Limit rm operations to skill-local or /tmp paths"),
        rule("DISK_FORMAT", "SYSTEM_DESTRUCTION", SkillValidationResult.Severity.CRITICAL,
            "\\b(mkfs|fdisk|parted)\\b",
            "Disk formatting command detected",
            "Contains commands that can format or partition disks",
            "Remove disk management commands"),
        rule("DD_IF", "SYSTEM_DESTRUCTION", SkillValidationResult.Severity.CRITICAL,
            "\\bdd\\s+if=",
            "Low-level disk write (dd) detected",
            "dd with if= can overwrite disk partitions",
            "Remove dd commands or restrict to safe input/output"),
        // High: 权限提升
        rule("SUDO_USAGE", "PRIVILEGE_ESCALATION", SkillValidationResult.Severity.HIGH,
            "\\bsudo\\b",
            "sudo command detected",
            "Script uses sudo which may escalate privileges",
            "Remove sudo; skills should not require root privileges"),
        rule("CHMOD_777", "PRIVILEGE_ESCALATION", SkillValidationResult.Severity.HIGH,
            "\\bchmod\\s+777\\b",
            "chmod 777 detected",
            "Setting world-writable permissions is a security risk",
            "Use restrictive permissions (e.g., chmod 755 or 644)"),
        rule("CHOWN_ROOT", "PRIVILEGE_ESCALATION", SkillValidationResult.Severity.HIGH,
            "\\bchown\\s+(root|0:)",
            "chown to root detected",
            "Changing file ownership to root is suspicious",
            "Skills should not change file ownership to root"),
        // High: 远程代码执行
        rule("CURL_PIPE_SH", "CODE_EXECUTION", SkillValidationResult.Severity.HIGH,
            "(?i)(curl|wget)\\s+[^|;]*\\|\\s*(sh|bash|zsh|python|perl|ruby)",
            "Remote code execution: curl/wget piped to shell",
            "Downloading and executing remote code is a high security risk",
            "Download files first, verify integrity, then execute separately"),
        rule("EVAL_EXEC", "CODE_EXECUTION", SkillValidationResult.Severity.HIGH,
            "(?i)\\b(eval|exec)\\s*\\(",
            "Dynamic code execution (eval/exec)",
            "Dynamic code execution can be exploited for injection attacks",
            "Use structured data processing instead of eval/exec"),
        rule("BASH_C", "CODE_EXECUTION", SkillValidationResult.Severity.MEDIUM,
            "\\b(bash|sh|zsh)\\s+-c\\s+",
            "Shell invocation with -c flag",
            "Executing shell commands via -c can be used for injection",
            "Use structured command arguments instead of shell -c"),
        // Medium: 可疑网络活动
        rule("NETWORK_EXFIL", "DATA_EXFILTRATION", SkillValidationResult.Severity.MEDIUM,
            "(?i)(curl|wget|nc|ncat)\\s+.*(-d|--data|--upload|-T)\\s+",
            "Data upload/exfiltration pattern",
            "Script may be sending data to external services",
            "Review data being sent and ensure it's expected behavior"),
        // Medium: 环境变量泄露
        rule("ENV_DUMP", "DATA_EXFILTRATION", SkillValidationResult.Severity.MEDIUM,
            "(?i)(printenv|env\\s*$|set\\s*$|export\\s+-p)",
            "Environment variable dump",
            "Dumping all environment variables may expose secrets",
            "Only access specific required environment variables"),
        // Low: Python 危险导入
        rule("PYTHON_IMPORT_OS", "CODE_EXECUTION", SkillValidationResult.Severity.LOW,
            "(?i)import\\s+(os|subprocess|shutil)",
            "Python system module import",
            "Importing os/subprocess/shutil enables system-level operations",
            "Ensure system operations are necessary and scoped appropriately"),

        // ===== Python：不可信反序列化 / 沙箱逃逸 =====
        rule("PICKLE_DESERIALIZE", "DESERIALIZATION", SkillValidationResult.Severity.HIGH,
            "(?i)\\b(c?pickle|dill)\\.loads?\\s*\\(",
            "Untrusted deserialization (pickle)",
            "pickle/dill load executes arbitrary code embedded in the payload",
            "Use json or a vetted serializer; never unpickle untrusted data"),
        rule("MARSHAL_LOADS", "DESERIALIZATION", SkillValidationResult.Severity.HIGH,
            "(?i)\\bmarshal\\.loads?\\s*\\(",
            "Untrusted deserialization (marshal)",
            "marshal can execute crafted bytecode",
            "Avoid marshal for external data"),
        // MEDIUM (warn, not block): yaml.load is only unsafe WITHOUT SafeLoader,
        // and the line-by-line scan can't see a SafeLoader argument that wraps
        // onto the next line — so blocking here would false-positive legit
        // multi-line safe calls. Surface it for review instead of blocking.
        rule("YAML_UNSAFE_LOAD", "DESERIALIZATION", SkillValidationResult.Severity.MEDIUM,
            "(?i)\\byaml\\.load\\s*\\((?![^)]*(?i:safe))",
            "Possibly unsafe yaml.load",
            "yaml.load without SafeLoader can instantiate arbitrary Python objects",
            "Use yaml.safe_load or Loader=yaml.SafeLoader"),
        rule("PY_SANDBOX_ESCAPE", "SANDBOX_ESCAPE", SkillValidationResult.Severity.HIGH,
            "(__subclasses__|__mro__|__builtins__|__globals__)",
            "Python sandbox-escape primitive",
            "Introspection attributes commonly used to break out of restricted execution",
            "Remove reflection into builtins / class hierarchies"),
        rule("PY_CTYPES", "CODE_EXECUTION", SkillValidationResult.Severity.HIGH,
            "(?i)\\bctypes\\.(cdll|windll)\\b",
            "Native library loading via ctypes",
            "ctypes can load and call arbitrary native code",
            "Avoid ctypes; use safe Python APIs"),
        rule("PY_DYNAMIC_IMPORT", "CODE_EXECUTION", SkillValidationResult.Severity.LOW,
            "(?i)(\\b__import__\\s*\\(|\\bimportlib\\.import_module\\s*\\()",
            "Dynamic module import",
            "Dynamic imports can load attacker-controlled modules",
            "Import modules statically by name where possible"),

        // ===== Node.js：危险 API =====
        rule("NODE_CHILD_PROCESS", "CODE_EXECUTION", SkillValidationResult.Severity.MEDIUM,
            "(?i)(require\\s*\\(\\s*['\"]child_process['\"]\\s*\\)|child_process\\.(exec|execSync|spawn|spawnSync|fork))",
            "Node child_process execution",
            "child_process can run arbitrary system commands",
            "Confirm subprocess use is necessary and arguments are not attacker-controlled"),
        rule("NODE_NEW_FUNCTION", "CODE_EXECUTION", SkillValidationResult.Severity.HIGH,
            "(?i)\\bnew\\s+Function\\s*\\(",
            "Dynamic code execution (new Function)",
            "new Function compiles strings into executable code, like eval",
            "Use structured logic instead of constructing functions from strings"),
        rule("NODE_VM_MODULE", "CODE_EXECUTION", SkillValidationResult.Severity.MEDIUM,
            "(?i)require\\s*\\(\\s*['\"](vm|vm2)['\"]\\s*\\)",
            "Node vm/vm2 module",
            "vm/vm2 are frequently used (and escaped) for sandboxed eval",
            "Avoid the vm module for untrusted code"),
        rule("PROTOTYPE_POLLUTION", "SANDBOX_ESCAPE", SkillValidationResult.Severity.MEDIUM,
            "(\\[\\s*['\"]__proto__['\"]\\s*\\]|\\.__proto__\\s*=)",
            "Prototype pollution pattern",
            "Writing __proto__ can corrupt object prototypes platform-wide",
            "Validate keys before dynamic property assignment"),

        // ===== 混淆 / 资源耗尽 / 持久化 / 凭据读取 =====
        rule("BASE64_PIPE_EXEC", "OBFUSCATION", SkillValidationResult.Severity.HIGH,
            "(?i)base64\\s+(-d|--decode)\\b[^\\n|]*\\|\\s*(sh|bash|zsh|python|perl|node)\\b",
            "Obfuscated execution (base64 decode piped to interpreter)",
            "Decoding then piping to a shell hides the real command from review",
            "Ship the command in clear text"),
        rule("FORK_BOMB", "RESOURCE_EXHAUSTION", SkillValidationResult.Severity.CRITICAL,
            ":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:",
            "Fork bomb",
            "Self-replicating process that exhausts system resources",
            "Remove the fork bomb"),
        rule("PERSISTENCE", "PERSISTENCE", SkillValidationResult.Severity.MEDIUM,
            "(?i)(crontab\\s+-|/etc/cron|authorized_keys|/etc/rc\\.local|/etc/profile\\.d/|>>\\s*~?/?\\.?(bashrc|zshrc|profile))",
            "Persistence mechanism",
            "Modifies startup files / cron / SSH keys to persist across sessions",
            "Skills should not install persistence hooks"),
        rule("SECRET_FILE_READ", "DATA_EXFILTRATION", SkillValidationResult.Severity.HIGH,
            "(?i)(/etc/shadow|\\.ssh/id_(rsa|ed25519|ecdsa)|\\.aws/credentials|\\.kube/config)",
            "Sensitive credential file access",
            "References well-known secret files (private keys, cloud credentials)",
            "Skills must not read system or user credential files")
    );

    /**
     * 文档级规则：用于 SKILL.md / references/ / skillContent
     * 降级处理：示例命令只记 warning，不 block
     * 只保留真正危险的结构级问题（如反向 shell）作为 block 条件
     */
    private final List<ScanRule> docRules = List.of(
        // Critical: 即使在文档中，反向 shell 也是明确的恶意指标
        rule("REVERSE_SHELL", "CODE_EXECUTION", SkillValidationResult.Severity.CRITICAL,
            "(?i)(bash\\s+-i\\s+>\\s*&|/dev/tcp/\\d+|nc\\s+[^|]+-e|ncat\\s+.*-e)",
            "Reverse shell pattern in documentation",
            "Documentation contains suspicious reverse shell patterns",
            "Review and ensure this is clearly marked as example only"),
        // High → 降级为 Medium: 文档中的命令示例不直接 block
        rule("CURL_PIPE_SH_DOC", "CODE_EXECUTION", SkillValidationResult.Severity.MEDIUM,
            "(?i)(curl|wget)\\s+[^|;]*\\|\\s*(sh|bash|zsh)",
            "Documentation shows curl-to-shell pattern",
            "Example shows potentially unsafe curl | sh pattern",
            "Consider warning users about verifying scripts before execution"),
        rule("SUDO_DOC", "PRIVILEGE_ESCALATION", SkillValidationResult.Severity.MEDIUM,
            "\\bsudo\\b",
            "Documentation references sudo",
            "Example uses sudo for privilege escalation",
            "Consider documenting that skills should not require root"),
        // Medium: 文档中的绝对路径引用（只是提示，不 block）
        rule("ABSOLUTE_PATH_DOC", "PATH_REFERENCE", SkillValidationResult.Severity.LOW,
            "(?m)^[^#]*(/etc/|/usr/|/var/|/root/|/home/|C:\\\\)",
            "Documentation references absolute system paths",
            "Example references system directories",
            "Use relative paths or explain the system dependency")
    );

    // ==================== 路径逃逸规则 ====================

    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile("\\.\\./");
    private static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile("(?m)^[^#]*(?<!['\"])(/etc/|/usr/|/var/|/root/|/home/|C:\\\\)");
    private static final Set<String> ALLOWED_SCRIPT_EXTENSIONS = Set.of(
        ".py", ".sh", ".bash", ".js", ".ts", ".rb", ".pl"
    );
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        ".exe", ".dll", ".so", ".dylib", ".bin", ".class", ".jar",
        ".zip", ".tar", ".gz", ".7z", ".rar",
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
        ".mp3", ".mp4", ".avi", ".mov",
        ".wasm", ".o", ".a"
    );

    // ==================== 公开 API ====================

    /**
     * 根据文件路径判断文件角色
     */
    private FileRole determineFileRole(String relativePath) {
        String lower = relativePath.toLowerCase();
        if (lower.startsWith("scripts/") || lower.startsWith("scripts\\")) {
            return FileRole.SCRIPT;
        }
        if (lower.equals("skill.md")) {
            return FileRole.DOCUMENTATION;
        }
        if (lower.startsWith("references/") || lower.startsWith("references\\")) {
            return FileRole.DOCUMENTATION;
        }
        // 配置文件
        if (lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml") ||
            lower.endsWith(".toml") || lower.endsWith(".ini") || lower.endsWith(".conf")) {
            return FileRole.CONFIG;
        }
        // 默认按文档处理（保守策略）
        return FileRole.DOCUMENTATION;
    }

    /**
     * 获取角色对应的规则集
     */
    private List<ScanRule> getRulesForRole(FileRole role) {
        return switch (role) {
            case SCRIPT -> scriptRules;
            case DOCUMENTATION, CONFIG -> docRules;
        };
    }

    /**
     * 验证已解析的技能包
     */
    public SkillValidationResult validate(ResolvedSkill skill) {
        if (skill.getSkillDir() != null) {
            return scanDirectory(skill.getSkillDir(), skill.getName());
        }
        // database fallback: 扫描 skillContent，视为文档
        return scanContent(skill.getContent(), skill.getName());
    }

    /**
     * 扫描技能目录
     */
    public SkillValidationResult scanDirectory(Path skillDir, String skillName) {
        List<SkillValidationResult.Finding> findings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. 结构检查
        checkStructure(skillDir, skillName, findings, warnings);

        // 2. 扫描所有文件
        try {
            List<Path> files = collectFiles(skillDir);
            if (files.size() > MAX_FILES) {
                warnings.add("Skill contains " + files.size() + " files (limit: " + MAX_FILES + "), only first " + MAX_FILES + " scanned");
                files = files.subList(0, MAX_FILES);
            }

            for (Path file : files) {
                scanFile(file, skillDir, skillName, findings, warnings);
            }
        } catch (IOException e) {
            warnings.add("Failed to scan directory: " + e.getMessage());
        }

        return buildResult(skillName, findings, warnings);
    }

    /**
     * 扫描技能文本内容（database fallback）
     * 视为文档级内容，使用降级规则
     */
    public SkillValidationResult scanContent(String content, String skillName) {
        if (content == null || content.isBlank()) {
            return SkillValidationResult.pass(skillName);
        }

        List<SkillValidationResult.Finding> findings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 对 skillContent 做文档级规则扫描
        scanText(content, "skillContent", skillName, FileRole.DOCUMENTATION, findings, warnings);

        return buildResult(skillName, findings, warnings);
    }

    // ==================== 内部扫描逻辑 ====================

    private void checkStructure(Path skillDir, String skillName,
                                List<SkillValidationResult.Finding> findings,
                                List<String> warnings) {
        // 检查 SKILL.md 是否存在
        if (!Files.exists(skillDir.resolve("SKILL.md"))) {
            warnings.add("Missing SKILL.md — skill may not be properly configured");
        }

        // 检查 symlink 逃逸
        try (Stream<Path> walk = Files.walk(skillDir, 5)) {
            walk.forEach(p -> {
                if (Files.isSymbolicLink(p)) {
                    try {
                        Path target = Files.readSymbolicLink(p).normalize();
                        Path resolved = p.getParent().resolve(target).normalize();
                        if (!resolved.startsWith(skillDir)) {
                            findings.add(SkillValidationResult.Finding.builder()
                                .ruleId("SYMLINK_ESCAPE")
                                .severity(SkillValidationResult.Severity.CRITICAL)
                                .category("PATH_TRAVERSAL")
                                .title("Symlink escapes skill directory")
                                .description("Symlink " + skillDir.relativize(p) + " points outside skill boundary: " + target)
                                .filePath(skillDir.relativize(p).toString())
                                .remediation("Remove symlinks that point outside the skill directory")
                                .build());
                        }
                    } catch (IOException e) {
                        warnings.add("Failed to resolve symlink: " + p.getFileName());
                    }
                }
            });
        } catch (IOException e) {
            warnings.add("Failed to check symlinks: " + e.getMessage());
        }
    }

    private List<Path> collectFiles(Path skillDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(skillDir, 10)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !Files.isSymbolicLink(p))
                .forEach(files::add);
        }
        return files;
    }

    private void scanFile(Path file, Path skillDir, String skillName,
                          List<SkillValidationResult.Finding> findings,
                          List<String> warnings) {
        String relativePath = skillDir.relativize(file).toString();
        String fileName = file.getFileName().toString();
        String ext = getExtension(fileName);

        // 跳过二进制文件
        if (BINARY_EXTENSIONS.contains(ext)) {
            return;
        }

        // 文件大小检查
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) {
                warnings.add("File too large to scan: " + relativePath + " (" + (size / 1024) + "KB)");
                return;
            }
        } catch (IOException e) {
            return;
        }

        // 确定文件角色
        FileRole role = determineFileRole(relativePath);

        // scripts/ 目录下的文件类型检查
        if (role == FileRole.SCRIPT) {
            if (!ALLOWED_SCRIPT_EXTENSIONS.contains(ext) && !fileName.equals("Makefile") && !fileName.equals("Dockerfile")) {
                warnings.add("Unexpected file type in scripts/: " + relativePath);
            }
        }

        // 读取文件内容并扫描
        try {
            String content = Files.readString(file);
            scanText(content, relativePath, skillName, role, findings, warnings);
        } catch (IOException e) {
            // 可能是二进制文件，跳过
        }
    }

    private void scanText(String content, String filePath, String skillName, FileRole role,
                          List<SkillValidationResult.Finding> findings,
                          List<String> warnings) {
        String[] lines = content.split("\n");
        List<ScanRule> rules = getRulesForRole(role);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 跳过注释行（简单启发式）- 仅对脚本类文件严格检查注释中的 critical 内容
            String trimmed = line.trim();
            if (trimmed.startsWith("#") && !trimmed.contains("!") && trimmed.length() < 200) {
                if (role == FileRole.SCRIPT) {
                    // 脚本文件：注释中仍检查 critical 规则
                    for (ScanRule rule : rules) {
                        if (rule.severity == SkillValidationResult.Severity.CRITICAL && rule.pattern.matcher(line).find()) {
                            addFinding(findings, rule, filePath, i + 1, line);
                        }
                    }
                }
                // 文档文件：注释中的内容不扫描（避免文档中的代码示例被误报）
                continue;
            }

            // 路径逃逸检查 - 结构级问题，所有角色都检查，但文档类降级处理
            if (PATH_TRAVERSAL_PATTERN.matcher(line).find()) {
                if (role == FileRole.SCRIPT) {
                    // 脚本中：路径逃逸是 HIGH，可 block
                    findings.add(SkillValidationResult.Finding.builder()
                        .ruleId("PATH_TRAVERSAL")
                        .severity(SkillValidationResult.Severity.HIGH)
                        .category("PATH_TRAVERSAL")
                        .title("Path traversal pattern (../)")
                        .description("Line contains ../ which may escape the skill directory")
                        .filePath(filePath)
                        .lineNumber(i + 1)
                        .snippet(truncate(line, 150))
                        .remediation("Use relative paths within the skill directory")
                        .build());
                } else {
                    // 文档中：路径逃逸降级为 LOW warning，不 block
                    warnings.add(filePath + ":" + (i + 1) + " - Path example with ../ (informational)");
                }
            }

            // 绝对路径检查 - 文档类降级处理
            if (ABSOLUTE_PATH_PATTERN.matcher(line).find()) {
                if (role == FileRole.SCRIPT) {
                    findings.add(SkillValidationResult.Finding.builder()
                        .ruleId("ABSOLUTE_PATH")
                        .severity(SkillValidationResult.Severity.MEDIUM)
                        .category("PATH_TRAVERSAL")
                        .title("Absolute system path reference")
                        .description("References absolute path which may access system files")
                        .filePath(filePath)
                        .lineNumber(i + 1)
                        .snippet(truncate(line, 150))
                        .remediation("Use relative paths within the skill directory")
                        .build());
                }
                // 文档中的绝对路径引用不生成 finding，已在 docRules 中作为 LOW 级别处理
            }

            // 应用角色对应的规则集
            for (ScanRule rule : rules) {
                if (rule.pattern.matcher(line).find()) {
                    addFinding(findings, rule, filePath, i + 1, line);
                }
            }
        }
    }

    private void addFinding(List<SkillValidationResult.Finding> findings, ScanRule rule,
                            String filePath, int lineNumber, String line) {
        // 去重：同一规则在同一文件的同一行不重复报告
        boolean exists = findings.stream().anyMatch(f ->
            f.getRuleId().equals(rule.ruleId) &&
            Objects.equals(f.getFilePath(), filePath) &&
            Objects.equals(f.getLineNumber(), lineNumber));
        if (exists) return;

        findings.add(SkillValidationResult.Finding.builder()
            .ruleId(rule.ruleId)
            .severity(rule.severity)
            .category(rule.category)
            .title(rule.title)
            .description(rule.description)
            .filePath(filePath)
            .lineNumber(lineNumber)
            .snippet(truncate(line, 150))
            .remediation(rule.remediation)
            .build());
    }

    private SkillValidationResult buildResult(String skillName,
                                               List<SkillValidationResult.Finding> findings,
                                               List<String> warnings) {
        if (findings.isEmpty() && warnings.isEmpty()) {
            return SkillValidationResult.pass(skillName);
        }

        // 判断最高严重级别
        SkillValidationResult.Severity maxSeverity = findings.stream()
            .map(SkillValidationResult.Finding::getSeverity)
            .max(Enum::compareTo)
            .orElse(SkillValidationResult.Severity.INFO);

        // CRITICAL 或 HIGH → blocked
        if (maxSeverity.isBlockLevel()) {
            return SkillValidationResult.block(skillName, findings, warnings);
        }

        // MEDIUM 或更低 → warn
        if (!findings.isEmpty()) {
            return SkillValidationResult.warn(skillName, findings, warnings);
        }

        // 只有 warnings，无 findings
        return SkillValidationResult.builder()
            .skillName(skillName)
            .passed(true)
            .blocked(false)
            .maxSeverity(SkillValidationResult.Severity.INFO)
            .warnings(warnings)
            .summary(warnings.size() + " warning(s)")
            .build();
    }

    // ==================== 工具方法 ====================

    private static ScanRule rule(String id, String category, SkillValidationResult.Severity severity,
                                  String pattern, String title, String description, String remediation) {
        return new ScanRule(id, category, severity, Pattern.compile(pattern), title, description, remediation);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot).toLowerCase() : "";
    }
}
