package vip.mate.llm.anthropic.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC-062: detect the locally-installed Claude Code version.
 *
 * <p>Anthropic's OAuth infrastructure validates the User-Agent version on
 * Bearer-auth requests. A UA that drifts too far behind the actual Claude Code
 * release returns 400 / 5xx. Detecting dynamically (via {@code claude --version}
 * or {@code claude-code --version}) keeps users who upgrade Claude Code
 * automatically aligned; the static fallback covers headless/server boxes
 * where Claude Code isn't installed locally (the OAuth flow may still work via
 * a manually-imported credentials file).
 *
 * <p>Result is cached for the JVM lifetime (Anthropic's UA validation tolerates
 * a stable version per process). Restart MateClaw to pick up a Claude Code
 * upgrade.
 */
@Slf4j
@Component
public class ClaudeCodeVersionDetector {

    /**
     * Static fallback version. Update this when bumping the floor at which
     * Anthropic accepts spoofed Claude Code traffic (track Anthropic's
     * announcements for cadence).
     */
    static final String FALLBACK_VERSION = "2.1.74";

    /** Match leading semver-like number from a {@code --version} stdout. */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)");

    private final AtomicReference<String> cache = new AtomicReference<>();

    /**
     * Returns the detected (or fallback) Claude Code version. Cached after
     * first call.
     */
    public String get() {
        String cached = cache.get();
        if (cached != null) return cached;

        String detected = detect();
        cache.compareAndSet(null, detected);
        return cache.get();
    }

    /** Force a re-detection. Useful for testing. */
    public void invalidate() {
        cache.set(null);
    }

    private String detect() {
        for (String cmd : new String[]{"claude", "claude-code"}) {
            String version = runVersionCommand(cmd);
            if (version != null) {
                log.info("[ClaudeCodeVersion] detected {} (from {} --version)", version, cmd);
                return version;
            }
        }
        log.debug("[ClaudeCodeVersion] no Claude Code binary on PATH; using fallback {}", FALLBACK_VERSION);
        return FALLBACK_VERSION;
    }

    /** Returns the version string from {@code <cmd> --version}, or {@code null} on any failure. */
    private String runVersionCommand(String cmd) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
            pb.redirectErrorStream(true);
            process = pb.start();
            // Bound the wait — a hung claude binary should not block startup.
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("[ClaudeCodeVersion] {} --version timed out after 5s", cmd);
                return null;
            }
            if (process.exitValue() != 0) return null;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                return parseVersion(line);
            }
        } catch (Exception e) {
            log.debug("[ClaudeCodeVersion] {} --version failed: {}", cmd, e.getMessage());
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Extract the leading {@code N.N[.N]} from a {@code --version} line. Package-private for tests. */
    static String parseVersion(String line) {
        if (line == null) return null;
        Matcher m = VERSION_PATTERN.matcher(line.trim());
        return m.find() ? m.group(1) : null;
    }
}
