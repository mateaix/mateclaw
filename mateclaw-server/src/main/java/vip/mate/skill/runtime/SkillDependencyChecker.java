package vip.mate.skill.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.runtime.SkillFrontmatterParser.SkillDependencies;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.repository.ToolMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 技能依赖检查器
 * 检查 commands / env / tools / platforms / endpoints 依赖是否满足
 */
@Slf4j
@Service
public class SkillDependencyChecker {

    private final ToolMapper toolMapper;
    private final ToolRegistry toolRegistry;

    /**
     * {@code @Lazy} on {@link ToolRegistry}: this bean is constructed during startup,
     * and ToolRegistry transitively depends on MCP / plugin infrastructure that also
     * runs early — the lazy proxy breaks that cycle.
     */
    public SkillDependencyChecker(ToolMapper toolMapper, @Lazy ToolRegistry toolRegistry) {
        this.toolMapper = toolMapper;
        this.toolRegistry = toolRegistry;
    }

    private static final String CURRENT_OS = detectOS();

    /**
     * 60s in-memory cache for command-availability probes (RFC-090 §14.7).
     * Each {@code refreshActiveSkills()} pass calls
     * {@link #isCommandAvailable(String)} once per declared binary; without
     * caching that triples ProcessBuilder calls when many skills share the
     * same prereq (e.g. python3 / ffmpeg).
     */
    private final Cache<String, Boolean> commandAvailability = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(256)
            .build();

    /** Timeout for a single endpoint reachability probe (TCP connect). */
    private static final int ENDPOINT_PROBE_TIMEOUT_MS = 1500;

    /**
     * 60s cache for endpoint reachability probes, keyed by {@code host:port}.
     * Same rhythm as {@link #commandAvailability}: every active-skills
     * refresh re-evaluates each feature requirement, and without caching,
     * N skills declaring the same service address would each open a socket
     * (blocking up to the probe timeout) per pass. Network state is also
     * the most volatile requirement class, so a short TTL keeps the
     * "service unreachable" verdict from going stale after a VPN connect.
     */
    private final Cache<String, Boolean> endpointReachability = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(256)
            .build();

    /**
     * 检查依赖
     */
    public DependencyCheckResult check(SkillDependencies dependencies, List<String> platforms, String skillName) {
        List<String> missing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean allSatisfied = true;

        // 1. 平台检查
        if (platforms != null && !platforms.isEmpty()) {
            boolean platformMatch = platforms.stream()
                .anyMatch(p -> p.equalsIgnoreCase(CURRENT_OS));
            if (!platformMatch) {
                missing.add("platform:" + CURRENT_OS + " (requires: " + String.join(", ", platforms) + ")");
                allSatisfied = false;
            }
        }

        if (dependencies == null || dependencies.isEmpty()) {
            return DependencyCheckResult.builder()
                .skillName(skillName)
                .satisfied(allSatisfied)
                .missing(missing)
                .warnings(warnings)
                .summary(allSatisfied ? "All dependencies satisfied" : buildSummary(missing))
                .build();
        }

        // 2. 命令检查
        for (String cmd : dependencies.getCommands()) {
            if (!isCommandAvailable(cmd)) {
                missing.add("command:" + cmd);
                allSatisfied = false;
            }
        }

        // 3. 环境变量检查
        for (String envVar : dependencies.getEnv()) {
            String value = System.getenv(envVar);
            if (value == null || value.isBlank()) {
                missing.add("env:" + envVar);
                allSatisfied = false;
            }
        }

        // 4. 内部工具检查 — fetch the runtime function-name set once per skill
        //    so we don't hit reflection N times when a skill lists many tools.
        Set<String> runtimeFunctionNames = dependencies.getTools().isEmpty()
                ? Set.of()
                : fetchRuntimeFunctionNames();
        for (String toolName : dependencies.getTools()) {
            if (!isToolAvailable(toolName, runtimeFunctionNames)) {
                missing.add("tool:" + toolName);
                allSatisfied = false;
            }
        }

        return DependencyCheckResult.builder()
            .skillName(skillName)
            .satisfied(allSatisfied)
            .missing(missing)
            .warnings(warnings)
            .summary(allSatisfied ? "All dependencies satisfied" : buildSummary(missing))
            .build();
    }

    // ==================== 检查方法 ====================

    private boolean isCommandAvailable(String command) {
        Boolean cached = commandAvailability.getIfPresent(command);
        if (cached != null) return cached;
        boolean available = probeCommand(command);
        commandAvailability.put(command, available);
        return available;
    }

    private boolean probeCommand(String command) {
        try {
            String checkCmd = isWindows() ? "where" : "which";
            ProcessBuilder pb = new ProcessBuilder(checkCmd, command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 快速消耗输出
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* drain */ }
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("Command check failed for '{}': {}", command, e.getMessage());
            return false;
        }
    }

    /**
     * The runtime registry (ToolRegistry) is authoritative: it knows the exact
     * function names LLMs and skills call by ({@code @Tool} method name / MCP
     * tool id / plugin tool name). The {@code mate_tool} DB overlay stores
     * class names + bean names and does NOT match that vocabulary, so checking
     * the DB alone mis-reports every real skill dependency as "missing".
     *
     * <p>We keep the DB lookup as a secondary fallback for the edge case where
     * someone has inserted a custom row whose {@code name} happens to equal the
     * function name.
     */
    private boolean isToolAvailable(String toolName, Set<String> runtimeFunctionNames) {
        if (runtimeFunctionNames.contains(toolName)) {
            return true;
        }
        try {
            Long count = toolMapper.selectCount(new LambdaQueryWrapper<ToolEntity>()
                .eq(ToolEntity::getName, toolName)
                .eq(ToolEntity::getEnabled, true));
            return count > 0;
        } catch (Exception e) {
            log.debug("Tool check failed for '{}': {}", toolName, e.getMessage());
            return false;
        }
    }

    private Set<String> fetchRuntimeFunctionNames() {
        try {
            return toolRegistry.availableFunctionNames();
        } catch (Exception e) {
            log.warn("Failed to fetch runtime tool function names, falling back to DB check only: {}",
                    e.getMessage());
            return Set.of();
        }
    }

    // ==================== Endpoint reachability ====================

    /** Parsed {@code host:port} target of an endpoint requirement. */
    record EndpointTarget(String host, int port) {}

    /**
     * Parse an endpoint requirement's check target into {@code host:port}.
     * Accepted forms:
     * <ul>
     *   <li>{@code http(s)://host[:port][/path]} — port defaults to the
     *       scheme's standard port when omitted</li>
     *   <li>{@code host:port}</li>
     *   <li>{@code host} — port defaults to 80</li>
     * </ul>
     * Returns {@code null} when the target is blank or unparseable, which
     * the caller maps to {@code UNKNOWN} — a misdeclared manifest must not
     * flip a skill to setup-needed.
     */
    static EndpointTarget parseEndpointTarget(String target) {
        if (target == null || target.isBlank()) return null;
        String t = target.strip();
        try {
            if (t.contains("://")) {
                URI uri = URI.create(t);
                String host = uri.getHost();
                if (host == null || host.isBlank()) return null;
                int port = uri.getPort();
                if (port <= 0) {
                    port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
                }
                return new EndpointTarget(host, port);
            }
            int colon = t.lastIndexOf(':');
            if (colon > 0 && colon < t.length() - 1) {
                String maybePort = t.substring(colon + 1);
                if (!maybePort.isEmpty() && maybePort.chars().allMatch(Character::isDigit)) {
                    return new EndpointTarget(t.substring(0, colon), Integer.parseInt(maybePort));
                }
            }
            if (t.contains("/") || t.contains(":")) return null;
            return new EndpointTarget(t, 80);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isEndpointReachable(EndpointTarget target) {
        String key = target.host() + ":" + target.port();
        Boolean cached = endpointReachability.getIfPresent(key);
        if (cached != null) return cached;
        boolean reachable = probeEndpoint(target);
        endpointReachability.put(key, reachable);
        return reachable;
    }

    private boolean probeEndpoint(EndpointTarget target) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), ENDPOINT_PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            log.debug("Endpoint probe failed for {}:{} — {}", target.host(), target.port(), e.getMessage());
            return false;
        }
    }

    private static boolean isWindows() {
        return CURRENT_OS.equals("windows");
    }

    private static String detectOS() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        if (os.contains("linux")) return "linux";
        return os;
    }

    private String buildSummary(List<String> missing) {
        if (missing.isEmpty()) return "All dependencies satisfied";
        return "Missing: " + String.join(", ", missing);
    }

    // ==================== RFC-090 Phase 2 — per-feature checks (§14.1 / §14.7) ====================

    /**
     * RFC-090 §14.7 — typed requirement classes.
     *
     * <p>Drives {@link #checkRequirement} so callers can ask for a specific
     * probe regardless of how the manifest expressed it. {@code ANY} is the
     * fallback when the manifest doesn't declare a type — we infer.
     */
    public enum RequirementType { BINARY, ENV_VAR, API_KEY, ENDPOINT, ANY }

    /**
     * Status for a single requirement after probing.
     */
    public enum RequirementStatus { SATISFIED, MISSING, UNKNOWN }

    /**
     * Per-feature evaluation result (RFC-090 §14.1).
     *
     * <p>Used by {@code SkillPackageResolver} to populate
     * {@code featureStatuses} on {@code ResolvedSkill}.
     */
    @Data
    @Builder
    public static class FeatureCheckResult {
        private String featureId;
        /** READY | SETUP_NEEDED | UNSUPPORTED — mirrors ResolvedSkill.FeatureStatus. */
        private String status;
        @Builder.Default
        private List<String> missing = new ArrayList<>();
        private String reason;
    }

    /**
     * Probe one manifest requirement.
     *
     * <p>{@code env_var} / {@code api_key} are equivalent for the purposes
     * of this check — both go through {@link System#getenv(String)} on
     * the {@code check} target.
     */
    public RequirementStatus checkRequirement(SkillManifest.RequirementDef req) {
        if (req == null || req.getKey() == null) return RequirementStatus.UNKNOWN;
        RequirementType type = inferType(req);
        String target = req.getCheck() == null || req.getCheck().isBlank() ? req.getKey() : req.getCheck();
        try {
            return switch (type) {
                case BINARY -> isCommandAvailable(target) ? RequirementStatus.SATISFIED : RequirementStatus.MISSING;
                case ENV_VAR, API_KEY -> {
                    String value = System.getenv(target);
                    yield (value != null && !value.isBlank()) ? RequirementStatus.SATISFIED : RequirementStatus.MISSING;
                }
                case ENDPOINT -> {
                    // Reachability, not liveness: a TCP connect proves the
                    // current deployment can route to the service (intranet
                    // address + wrong network segment is the classic failure),
                    // without depending on the service answering a particular
                    // HTTP verb on its base path.
                    EndpointTarget ep = parseEndpointTarget(target);
                    if (ep == null) yield RequirementStatus.UNKNOWN;
                    yield isEndpointReachable(ep) ? RequirementStatus.SATISFIED : RequirementStatus.MISSING;
                }
                case ANY -> RequirementStatus.UNKNOWN;
            };
        } catch (Exception e) {
            log.debug("Requirement check failed for '{}': {}", req.getKey(), e.getMessage());
            return RequirementStatus.UNKNOWN;
        }
    }

    private RequirementType inferType(SkillManifest.RequirementDef req) {
        String declared = req.getType();
        if (declared != null) {
            return switch (declared.toLowerCase(Locale.ROOT)) {
                case "binary" -> RequirementType.BINARY;
                case "env_var", "env" -> RequirementType.ENV_VAR;
                case "api_key", "key" -> RequirementType.API_KEY;
                case "endpoint", "url", "service" -> RequirementType.ENDPOINT;
                default -> RequirementType.ANY;
            };
        }
        // Fall back to a key-prefix heuristic so legacy manifests work:
        // synthesized "cmd:..." / "env:..." keys get the right type.
        if (req.getKey() != null) {
            String k = req.getKey().toLowerCase(Locale.ROOT);
            if (k.startsWith("cmd:") || k.startsWith("bin:")) return RequirementType.BINARY;
            if (k.startsWith("env:")) return RequirementType.ENV_VAR;
            if (k.endsWith("_api_key") || k.endsWith("_key")) return RequirementType.API_KEY;
        }
        // A check target that looks like a URL is an endpoint probe even
        // without a declared type.
        if (req.getCheck() != null && req.getCheck().contains("://")) {
            return RequirementType.ENDPOINT;
        }
        return RequirementType.ANY;
    }

    /**
     * Evaluate a single feature against a manifest's requirement table.
     *
     * <p>Status semantics:
     * <ul>
     *   <li>{@code UNSUPPORTED} — current OS not in {@code feature.platforms}</li>
     *   <li>{@code SETUP_NEEDED} — at least one referenced requirement is missing</li>
     *   <li>{@code READY} — all referenced requirements satisfied (or unknown)</li>
     * </ul>
     * Empty {@code requires}/{@code platforms} on a feature means
     * "no constraint" — the feature is always READY for that axis.
     */
    public FeatureCheckResult checkFeature(SkillManifest.FeatureDef feature,
                                           Map<String, SkillManifest.RequirementDef> requirementsByKey) {
        FeatureCheckResult.FeatureCheckResultBuilder b = FeatureCheckResult.builder()
                .featureId(feature == null ? null : feature.getId());
        if (feature == null) {
            return b.status("READY").build();
        }

        // 1. Platform gate
        List<String> platforms = feature.getPlatforms();
        if (platforms != null && !platforms.isEmpty()) {
            boolean platformMatch = platforms.stream().anyMatch(p -> p.equalsIgnoreCase(CURRENT_OS));
            if (!platformMatch) {
                String reason = feature.getUnsupportedMessage() != null && !feature.getUnsupportedMessage().isBlank()
                        ? feature.getUnsupportedMessage()
                        : "Unsupported on " + CURRENT_OS;
                return b.status("UNSUPPORTED").reason(reason).build();
            }
        }

        // 2. Requirement gate
        List<String> missing = new ArrayList<>();
        if (feature.getRequires() != null && !feature.getRequires().isEmpty()
                && requirementsByKey != null) {
            for (String key : feature.getRequires()) {
                SkillManifest.RequirementDef req = requirementsByKey.get(key);
                if (req == null) {
                    missing.add(key + " (undeclared)");
                    continue;
                }
                RequirementStatus st = checkRequirement(req);
                if (st == RequirementStatus.MISSING) missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            return b.status("SETUP_NEEDED").missing(missing)
                    .reason(feature.getFallbackMessage())
                    .build();
        }
        return b.status("READY").build();
    }

    // ==================== 结果模型 ====================

    @Data
    @Builder
    public static class DependencyCheckResult {
        private String skillName;
        private boolean satisfied;
        @Builder.Default
        private List<String> missing = new ArrayList<>();
        @Builder.Default
        private List<String> warnings = new ArrayList<>();
        private String summary;
    }
}
