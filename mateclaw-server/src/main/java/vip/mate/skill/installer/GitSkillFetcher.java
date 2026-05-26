package vip.mate.skill.installer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.skill.installer.model.SkillBundle;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git 仓库 skill 拉取器
 * <p>
 * 使用 git CLI（ProcessBuilder）进行 shallow clone，不引入 JGit 依赖。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class GitSkillFetcher {

    private static final long CLONE_TIMEOUT_SECONDS = 120;

    private final SkillFrontmatterParser frontmatterParser;

    /**
     * GitHub access token used when cloning private repositories.
     * Resolution order: {@code mateclaw.skill.github-token} property → {@code GITHUB_TOKEN}
     * environment variable → empty (public repos only). Kept as a plain field so the value
     * is never logged or embedded in URLs — it is passed to the git subprocess through
     * dedicated environment variables (see {@link #cloneRepo}).
     */
    private final String githubToken;

    public GitSkillFetcher(
            SkillFrontmatterParser frontmatterParser,
            @Value("${mateclaw.skill.github-token:}") String configuredGithubToken) {
        this.frontmatterParser = frontmatterParser;
        String token = (configuredGithubToken != null && !configuredGithubToken.isBlank())
                ? configuredGithubToken
                : System.getenv("GITHUB_TOKEN");
        this.githubToken = (token == null) ? "" : token.trim();
    }

    /**
     * 从 GitHub 仓库获取 skill bundle
     *
     * @param repoUrl GitHub 仓库 URL
     * @param ref     git ref（branch/tag），null 时使用默认分支
     * @param subPath 仓库内子目录路径（如 "skills/my-skill"），null 时使用根目录
     * @return 解析后的 SkillBundle，失败返回 null
     */
    public SkillBundle fetch(String repoUrl, String ref, String subPath) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("mateclaw-skill-install-");
            cloneRepo(repoUrl, ref, tempDir);

            // 定位 skill 根目录
            Path skillRoot = tempDir;
            if (subPath != null && !subPath.isBlank()) {
                skillRoot = tempDir.resolve(subPath);
                if (!Files.exists(skillRoot)) {
                    log.error("subPath '{}' not found in cloned repo", subPath);
                    return null;
                }
            }

            // 定位 SKILL.md
            Path skillMd = locateSkillMd(skillRoot);
            if (skillMd == null) {
                log.error("SKILL.md not found in {}", skillRoot);
                return null;
            }

            // 解析内容
            String content = Files.readString(skillMd);
            SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);

            // 提取 skill 名称
            String name = parsed.getName();
            if (name == null || name.isBlank()) {
                // 从 URL 推导名称
                name = extractRepoName(repoUrl);
            }

            // 收集 references/ 和 scripts/
            Map<String, String> references = collectFiles(skillRoot.resolve("references"));
            Map<String, String> scripts = collectFiles(skillRoot.resolve("scripts"));

            return new SkillBundle(
                    name,
                    content,
                    references,
                    scripts,
                    "github",
                    repoUrl,
                    parsed.getFrontmatter().getOrDefault("version", "1.0.0").toString(),
                    parsed.getDescription(),
                    parsed.getFrontmatter().getOrDefault("author", "").toString(),
                    parsed.getFrontmatter().getOrDefault("icon", "").toString()
            );
        } catch (Exception e) {
            log.error("Failed to fetch skill from GitHub {}: {}", repoUrl, e.getMessage());
            return null;
        } finally {
            cleanup(tempDir);
        }
    }

    /**
     * git clone --depth 1 to a temporary directory.
     * <p>
     * When a GitHub token is configured and the repository is hosted on github.com,
     * the credential is forwarded to the git subprocess through {@code GIT_CONFIG_*}
     * environment variables — equivalent to {@code git -c http.extraHeader=...} but
     * without ever placing the token in the process command line (visible to {@code ps})
     * or the repository URL (visible in logs and error messages). Requires git 2.31+.
     */
    private void cloneRepo(String repoUrl, String ref, Path targetDir) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("clone");
        command.add("--depth");
        command.add("1");
        if (ref != null && !ref.isBlank()) {
            command.add("--branch");
            command.add(ref);
        }
        command.add(repoUrl);
        command.add(targetDir.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        // Inject credentials for private GitHub repos via the git subprocess environment.
        // Keeping the token out of argv and out of the URL ensures it cannot leak through
        // process listings, the INFO log below, or the IOException message on clone failure.
        if (!githubToken.isEmpty() && isGithubHost(repoUrl)) {
            var env = pb.environment();
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Bearer " + githubToken);
            // Fail fast on auth errors instead of blocking on an interactive password prompt.
            env.put("GIT_TERMINAL_PROMPT", "0");
        }

        Process process = pb.start();

        boolean finished = process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git clone timed out after " + CLONE_TIMEOUT_SECONDS + "s");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new IOException("git clone failed (exit=" + exitCode + "): " + output);
        }

        log.info("Cloned {} (ref={}) to {}", repoUrl, ref, targetDir);
    }

    /**
     * Match GitHub host conservatively (scheme + host boundary) so a malicious URL like
     * {@code https://evil.com/?u=github.com/...} cannot smuggle the token to a third party.
     */
    private static boolean isGithubHost(String repoUrl) {
        return repoUrl != null
                && (repoUrl.startsWith("https://github.com/")
                        || repoUrl.startsWith("http://github.com/")
                        || repoUrl.startsWith("git@github.com:"));
    }

    /**
     * 在 skill 根目录中定位 SKILL.md
     */
    private Path locateSkillMd(Path skillRoot) {
        Path direct = skillRoot.resolve("SKILL.md");
        if (Files.exists(direct)) {
            return direct;
        }
        // 尝试 skill.md（小写）
        Path lower = skillRoot.resolve("skill.md");
        if (Files.exists(lower)) {
            return lower;
        }
        return null;
    }

    /**
     * 收集目录下的所有文件为 relativePath → content 映射
     */
    private Map<String, String> collectFiles(Path dir) throws IOException {
        Map<String, String> files = new HashMap<>();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return files;
        }

        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && attrs.size() < 1_000_000) { // 跳过超过 1MB 的文件
                    String relativePath = dir.relativize(file).toString();
                    files.put(relativePath, Files.readString(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * 从 GitHub URL 提取仓库名作为 skill 名称
     */
    private String extractRepoName(String repoUrl) {
        String url = repoUrl.replaceAll("\\.git$", "");
        int lastSlash = url.lastIndexOf('/');
        return lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
    }

    /**
     * 清理临时目录
     */
    private void cleanup(Path tempDir) {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try {
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to cleanup temp dir {}: {}", tempDir, e.getMessage());
        }
    }
}
