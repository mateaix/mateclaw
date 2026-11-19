package vip.mate.agent.runtime.dsh.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Installs exact SDK packages into a candidate prefix without touching global npm state. */
@Component
public class DshPinnedInstaller {
    public record Release(String version, String channel, String integrity) {}
    public record Installation(String version, String platform, String executablePath, String integrity) {}
    private static final Map<String, Release> CATALOG = Map.of(
            "0.2.2-rc.1", new Release("0.2.2-rc.1", "candidate",
                    "sha512-ijR9UEVYkG15Q/Q79JzvzLBzQOQfc63VLr9n6flpwHPhr5IKn2UxthnSoto/P9l7qGd7pPvI1Ra6TW7LH3oKpg=="),
            "0.2.1-rc.2", new Release("0.2.1-rc.2", "candidate",
                    "sha512-tvY+yhfKRifthbqPe/OK0AYxM5ZkvqfYq/jMYgJcEUxiRRL1Vwra3Ti5TB6AXgSWQdiPHYh/Vp6SJa9yHwMNuw=="),
            "0.2.0-rc.1", new Release("0.2.0-rc.1", "next",
                    "sha512-F6hKNVoGgBDIzSiyRaIlobq4UD6cwxUjh+nwXqcDmufDh87TE1izsYzs8L5cZNpF2JmPnFM1mXRNnRJ0cs43ng=="),
            "0.1.7-rc.2", new Release("0.1.7-rc.2", "latest",
                    "sha512-SQFhriLvza8GnFApnC5/32AgpcyKxrWnYXhvwDOLJdgWpkCX2EexyR9c8kCkMITJXnFLEN3Qb2CEh0W36vkLyw=="));
    private final ObjectMapper mapper;
    public DshPinnedInstaller(ObjectMapper mapper) { this.mapper = mapper; }

    public static Release release(String version) {
        Release release = CATALOG.get(version);
        if (release == null) throw new IllegalArgumentException("dsh.unsupported_version: select an exact catalog version");
        return release;
    }
    public static List<Release> catalog() { return List.of(release("0.2.0-rc.1"), release("0.2.2-rc.1"), release("0.2.1-rc.2"), release("0.1.7-rc.2")); }

    public static String platform(String os, String arch) {
        if (os.toLowerCase().contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))) return "linux-x64";
        if (os.toLowerCase().contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) return "darwin-arm64";
        throw new IllegalArgumentException("dsh.unsupported_platform: managed install supports Linux x64 and macOS arm64 candidates");
    }

    public Installation install(String version, Path prefix) throws Exception {
        Release release = release(version);
        String platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        if (Files.exists(prefix)) throw new IllegalArgumentException("dsh.candidate_directory_exists");
        Files.createDirectories(prefix);
        Path log = prefix.resolve("install.log");
        String nodeVersion = execute(List.of("node", "--version"), prefix, log, Duration.ofSeconds(10)).trim();
        requireNodeVersion(version, nodeVersion);
        // npm ci verifies every tarball against the reviewed lock before lifecycle execution.
        try (var input = new ClassPathResource("dsh/locks/" + version + ".json").getInputStream()) {
            Files.copy(input, prefix.resolve("package-lock.json"));
        }
        JsonNode trusted = mapper.readTree(prefix.resolve("package-lock.json").toFile());
        if (!release.integrity().equals(trusted.path("packages").path("node_modules/@deepseek-ai/dsh").path("integrity").asText())) {
            throw new IllegalStateException("dsh.catalog_integrity_mismatch");
        }
        mapper.writeValue(prefix.resolve("package.json").toFile(), Map.of("name", "mateclaw-dsh-runtime",
                "version", "1.0.0", "private", true, "dependencies", Map.of("@deepseek-ai/dsh", version),
                "allowScripts", installScripts(trusted.path("packages"))));
        execute(List.of("npm", "ci", "--prefix", prefix.toString(), "--registry=https://registry.npmjs.org",
                "--no-audit", "--no-fund"), prefix, log, Duration.ofMinutes(5));
        JsonNode lock = mapper.readTree(prefix.resolve("package-lock.json").toFile());
        JsonNode installed = lock.path("packages").path("node_modules/@deepseek-ai/dsh");
        if (!version.equals(installed.path("version").asText()) || !release.integrity().equals(installed.path("integrity").asText())) {
            throw new IllegalStateException("dsh.package_integrity_mismatch");
        }
        Path executable = prefix.resolve("node_modules/.bin/dsh");
        if (!Files.isExecutable(executable)) throw new IllegalStateException("dsh.executable_missing");
        JsonNode packageJson = mapper.readTree(prefix.resolve("node_modules/@deepseek-ai/dsh/package.json").toFile());
        if (!version.equals(packageJson.path("version").asText())) throw new IllegalStateException("dsh.package_version_mismatch");
        return new Installation(version, platform, managedExecutable(prefix, version).toString(), release.integrity());
    }

    /** npm's script policy is scoped to packages with scripts in the reviewed lock. */
    static Map<String, Boolean> installScripts(JsonNode packages) {
        Map<String, Boolean> scripts = new java.util.TreeMap<>();
        packages.fields().forEachRemaining(entry -> {
            if (entry.getValue().path("hasInstallScript").asBoolean(false)) {
                String path = entry.getKey();
                int index = path.lastIndexOf("node_modules/");
                if (index >= 0) scripts.put(path.substring(index + "node_modules/".length()), true);
            }
        });
        return scripts;
    }

    /** Only reviewed LTS majors; newer upstream engines do not certify untested majors. */
    static void requireNodeVersion(String version, String node) {
        release(version);
        var match = java.util.regex.Pattern.compile("v(22|24)\\.([0-9]+)\\.([0-9]+)").matcher(node);
        boolean valid = match.matches();
        if (valid && "0.2.2-rc.1".equals(version)) {
            valid = "24".equals(match.group(1)) && Long.parseLong(match.group(2)) >= 9;
        }
        if (!valid) throw new IllegalStateException("dsh.node_version_unverified: " + version
                + ("0.2.2-rc.1".equals(version) ? " requires Node.js 24.9+ (24.x)" : " requires Node.js 22.x or 24.x"));
    }

    /** Keep the package untouched; a generation-owned launcher supplies reviewed defaults. */
    static Path managedExecutable(Path prefix, String version) throws IOException {
        Path upstream = prefix.resolve("node_modules/.bin/dsh");
        if (!"0.2.2-rc.1".equals(version)) return upstream;
        try (var input = new ClassPathResource("dsh/mateclaw-sdk.patch.yml").getInputStream()) {
            Files.copy(input, prefix.resolve("mateclaw-sdk.patch.yml"));
        }
        Path executable = Files.createDirectories(prefix.resolve("bin")).resolve("dsh");
        try (var input = new ClassPathResource("dsh/mateclaw-sdk-launcher.sh").getInputStream()) {
            Files.copy(input, executable);
        }
        if (!executable.toFile().setExecutable(true, true)) throw new IOException("dsh.executable_unavailable");
        return executable;
    }

    private static String execute(List<String> argv, Path cwd, Path log, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Process process = builder.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("dsh.install_timeout");
            if (process.exitValue() != 0) throw new IllegalStateException("dsh.install_failed: inspect the protected candidate install log");
            try (RandomAccessFile output = new RandomAccessFile(log.toFile(), "r")) {
                output.seek(Math.max(0, output.length() - 65536));
                byte[] tail = new byte[(int) Math.min(65536, output.length())];
                output.readFully(tail);
                return new String(tail, StandardCharsets.UTF_8);
            }
        } finally {
            process.descendants().forEach(child -> { child.destroy(); if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
