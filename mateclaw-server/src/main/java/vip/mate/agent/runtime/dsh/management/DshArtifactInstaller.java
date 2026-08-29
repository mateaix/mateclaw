package vip.mate.agent.runtime.dsh.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Downloads and atomically installs the server-selected DSH artifact. */
@Service
public class DshArtifactInstaller {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI manifestUri;
    private final URI githubReleaseUri;
    private final Path installRoot;

    public DshArtifactInstaller(
            ObjectMapper objectMapper,
            @Value("${mateclaw.agent.runtime.dsh.manifest-url:}") String manifestUrl,
            @Value("${mateclaw.agent.runtime.dsh.github-release-url:https://api.github.com/repos/deepseek-ai/deepseek-harness/releases/latest}") String githubReleaseUrl,
            @Value("${mateclaw.agent.runtime.dsh.install-root:${user.home}/.mateclaw/runtimes/deepseek-harness}") String installRoot) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.manifestUri = manifestUrl == null || manifestUrl.isBlank() ? null : URI.create(manifestUrl.trim());
        this.githubReleaseUri = URI.create(githubReleaseUrl.trim());
        this.installRoot = Path.of(installRoot).toAbsolutePath().normalize();
    }

    public boolean isInstalled() {
        return Files.isExecutable(installRoot.resolve("dsh-jsonrpc-agent"))
                || Files.isExecutable(installRoot.resolve("bin/dsh-jsonrpc-agent"));
    }

    public boolean manifestConfigured() {
        return manifestUri != null || githubReleaseUri != null;
    }

    public boolean privateManifestConfigured() {
        return manifestUri != null;
    }

    public Path installedExecutable() {
        Path direct = installRoot.resolve("dsh-jsonrpc-agent");
        return Files.isExecutable(direct) ? direct : installRoot.resolve("bin/dsh-jsonrpc-agent");
    }

    public Path installedCordisConfig() {
        if (!Files.exists(installRoot)) return null;
        try (var paths = Files.walk(installRoot)) {
            return paths.filter(path -> path.getFileName().toString().equals("cordis.yml"))
                    .findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public DshArtifactManifest loadManifest() throws Exception {
        if (manifestUri != null) {
            HttpRequest request = HttpRequest.newBuilder(manifestUri).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 == 2) return objectMapper.readValue(response.body(), DshArtifactManifest.class);
        }
        return loadGithubManifest();
    }

    private DshArtifactManifest loadGithubManifest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(githubReleaseUri)
                .header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("DSH private manifest unavailable and GitHub fallback failed: HTTP " + response.statusCode());
        JsonNode root = objectMapper.readTree(response.body());
        for (JsonNode asset : root.path("assets")) {
            String name = asset.path("name").asText("").toLowerCase();
            String digest = asset.path("digest").asText("");
            if ((name.contains("macos") || name.contains("darwin")) && name.contains("arm64") && digest.startsWith("sha256:")) {
                return new DshArtifactManifest("deepseek-harness", root.path("tag_name").asText("latest"), "macos-arm64",
                        asset.path("browser_download_url").asText(), digest.substring("sha256:".length()), asset.path("size").asLong(0), null);
            }
        }
        throw new IllegalStateException("GitHub DSH release has no macos-arm64 asset with a SHA-256 digest");
    }

    public Path install(DshArtifactManifest manifest) throws Exception {
        validateManifest(manifest);
        Path parent = installRoot.getParent();
        Files.createDirectories(parent);
        Path archive = Files.createTempFile(parent, ".dsh-download-", ".tar.gz");
        Path staging = Files.createTempDirectory(parent, ".dsh-staging-");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(manifest.downloadUrl())).GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("DSH artifact request failed: HTTP " + response.statusCode());
            try (InputStream input = response.body()) {
                Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            if (manifest.size() > 0 && Files.size(archive) != manifest.size()) {
                throw new IllegalStateException("DSH artifact size mismatch");
            }
            verifyChecksum(archive, manifest.sha256());
            verifyArchiveEntries(archive);
            runTar(archive, staging);
            verifyExtractedTree(staging);
            Path executable = findExecutable(staging);
            Path executableRelativePath = staging.relativize(executable);
            executable.toFile().setExecutable(true, false);
            Path backup = parent.resolve(".dsh-previous");
            if (Files.exists(installRoot)) Files.move(installRoot, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staging, installRoot, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(backup);
            return installRoot.resolve(executableRelativePath);
        } finally {
            Files.deleteIfExists(archive);
            deleteTree(staging);
        }
    }

    private void validateManifest(DshArtifactManifest manifest) {
        if (manifest == null || manifest.downloadUrl() == null || manifest.downloadUrl().isBlank()
                || manifest.sha256() == null || !manifest.sha256().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("DSH artifact manifest is incomplete or has an invalid checksum");
        }
        URI uri = URI.create(manifest.downloadUrl());
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("DSH artifact must use HTTPS");
    }

    private void verifyChecksum(Path archive, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(archive)) {
            input.transferTo(new java.security.DigestOutputStream(OutputStreamDiscard.INSTANCE, digest));
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expected)) throw new IllegalStateException("DSH artifact checksum mismatch");
    }

    private void verifyArchiveEntries(Path archive) throws Exception {
        Process process = new ProcessBuilder("tar", "-tzf", archive.toString()).redirectErrorStream(true).start();
        List<String> entries;
        try (InputStream input = process.getInputStream()) {
            entries = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("DSH archive is not a readable tar.gz");
        for (String entry : entries) {
            Path normalized = Path.of(entry).normalize();
            if (entry.startsWith("/") || normalized.startsWith("..")) throw new IllegalArgumentException("DSH archive contains an unsafe path");
        }
    }

    private void runTar(Path archive, Path destination) throws Exception {
        Process process = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", destination.toString()).redirectErrorStream(true).start();
        String output;
        try (InputStream input = process.getInputStream()) { output = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("DSH archive extraction failed: " + output);
    }

    private Path findExecutable(Path staging) throws Exception {
        try (var paths = Files.walk(staging)) {
            return paths.filter(path -> path.getFileName().toString().equals("dsh-jsonrpc-agent"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("DSH artifact has no dsh-jsonrpc-agent executable"));
        }
    }

    private void verifyExtractedTree(Path staging) throws Exception {
        try (var paths = Files.walk(staging)) {
            for (Path path : paths.toList()) {
                if (!Files.isSymbolicLink(path)) continue;
                Path target = path.getParent().resolve(Files.readSymbolicLink(path)).normalize();
                if (!target.startsWith(staging)) throw new IllegalArgumentException("DSH archive contains a link outside its staging directory");
            }
        }
    }

    private void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }

    private static final class OutputStreamDiscard extends java.io.OutputStream {
        private static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();
        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
    }
}
