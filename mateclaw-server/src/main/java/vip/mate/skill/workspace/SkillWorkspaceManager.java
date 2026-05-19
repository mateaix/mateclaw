package vip.mate.skill.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skill 工作区管理器
 * <p>
 * 遵循 Maven Local Repository 模式：{root}/{skillName}/ 约定子目录。
 * 负责工作区的路径解析、初始化、归档、导出和状态查询。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillWorkspaceManager {

    private final SkillWorkspaceProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    private static final DateTimeFormatter ARCHIVE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // ==================== 路径解析 ====================

    /**
     * 获取工作区根目录（确保存在）
     */
    public Path getWorkspaceRoot() {
        Path root = Paths.get(properties.getRoot());
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("Failed to create workspace root {}: {}", root, e.getMessage());
        }
        return root;
    }

    /**
     * 按约定解析 skill 工作区路径：{root}/{skillName}/
     */
    public Path resolveConventionPath(String skillName) {
        return getWorkspaceRoot().resolve(sanitizeName(skillName));
    }

    /**
     * 智能解析 skill 工作区路径（三级优先级）：
     * <ol>
     *   <li>configuredDir（显式配置的 skillDir）</li>
     *   <li>{root}/{skillName}/（约定路径，目录存在时）</li>
     *   <li>null（无目录，回退数据库）</li>
     * </ol>
     */
    public Path resolveEffectivePath(String skillName, String configuredDir) {
        // 1. 显式配置
        if (configuredDir != null && !configuredDir.isBlank()) {
            Path explicit = Paths.get(configuredDir);
            if (Files.exists(explicit) && Files.isDirectory(explicit)) {
                return explicit;
            }
        }
        // 2. 约定路径
        Path convention = resolveConventionPath(skillName);
        if (Files.exists(convention) && Files.isDirectory(convention)) {
            return convention;
        }
        // 3. 无目录
        return null;
    }

    /**
     * 检查约定路径的 workspace 是否存在
     */
    public boolean conventionWorkspaceExists(String skillName) {
        Path convention = resolveConventionPath(skillName);
        return Files.exists(convention) && Files.isDirectory(convention);
    }

    // ==================== 生命周期操作 ====================

    /**
     * 初始化 skill 工作区目录（兼容旧调用：overwrite=false）
     *
     * @param skillName      skill 名称
     * @param initialContent SKILL.md 初始内容（可为 null）
     * @return 创建的工作区路径
     */
    public Path initWorkspace(String skillName, String initialContent) {
        return initWorkspace(skillName, initialContent, false);
    }

    /**
     * 初始化 skill 工作区目录
     *
     * @param skillName      skill 名称
     * @param initialContent SKILL.md 初始内容（可为 null）
     * @param overwrite      true 时无条件覆写 SKILL.md（用于重装 / 导出场景），
     *                       false 时仅在 SKILL.md 不存在时写入（用于首次创建）
     * @return 创建的工作区路径
     */
    public Path initWorkspace(String skillName, String initialContent, boolean overwrite) {
        Path workspaceDir = resolveConventionPath(skillName);
        try {
            Files.createDirectories(workspaceDir);
            Files.createDirectories(workspaceDir.resolve("references"));
            Files.createDirectories(workspaceDir.resolve("scripts"));

            Path skillMd = workspaceDir.resolve("SKILL.md");
            if (overwrite || !Files.exists(skillMd)) {
                String content = (initialContent != null && !initialContent.isBlank())
                        ? initialContent
                        : buildDefaultSkillMd(skillName);
                Files.writeString(skillMd, content);
            }

            log.info("Initialized skill workspace: {} (overwrite={})", workspaceDir, overwrite);
            eventPublisher.publishEvent(new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.CREATED, workspaceDir));
            return workspaceDir;
        } catch (IOException e) {
            log.warn("Failed to initialize workspace for skill '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    /**
     * RFC-090 §14.5 — physically remove the workspace directory. Used
     * by hard-delete only; uninstall still calls
     * {@link #archiveWorkspace} so users can recover by re-installing.
     */
    public void purgeWorkspace(String skillName) {
        Path workspaceDir = resolveConventionPath(skillName);
        if (!Files.exists(workspaceDir)) return;
        try {
            // Walk and delete bottom-up so non-empty dirs go away too.
            try (var stream = Files.walk(workspaceDir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        /* leave partial cleanup; best effort */
                    }
                });
            }
            log.info("Purged skill workspace: {}", workspaceDir);
            eventPublisher.publishEvent(new SkillWorkspaceEvent(skillName,
                    SkillWorkspaceEvent.Type.ARCHIVED, workspaceDir));
        } catch (IOException e) {
            log.warn("Failed to purge workspace for skill '{}': {}", skillName, e.getMessage());
        }
    }

    /**
     * Tri-state outcome of {@link #archiveWorkspace}. {@code MISSING} is a
     * commit-safe no-op — the runtime accepts skills that live only in
     * {@code mate_skill.skill_content} with no convention workspace — while
     * {@code FAILED} is a real error callers requiring atomicity must honor.
     */
    public enum ArchiveResult {
        /** Workspace directory existed and was moved to {@code .archived/}. */
        MOVED,
        /** No convention workspace directory — commit-safe no-op. */
        MISSING,
        /** Workspace existed but the move failed (IOException). */
        FAILED
    }

    /** Symmetric tri-state outcome of {@link #restoreWorkspace}. */
    public enum RestoreResult {
        /** Archive directory existed and was moved back into place. */
        MOVED,
        /** No archive directory found — DB-only skill or nothing to restore. */
        MISSING,
        /** Archive directory existed but the move-back failed (IOException). */
        FAILED
    }

    /**
     * Move {@code {root}/{name}/} to {@code {root}/.archived/{name}-{ts}/}.
     *
     * <p>Returns {@link ArchiveResult#MISSING} when the workspace doesn't
     * exist — callers may treat this as a successful no-op since the runtime
     * accepts skills that live only in {@code mate_skill.skill_content}.
     * Returns {@link ArchiveResult#FAILED} on IOException; callers requiring
     * atomicity must refuse to commit derived state. Returns
     * {@link ArchiveResult#MOVED} on success, having already published
     * {@link SkillWorkspaceEvent.Type#ARCHIVED}.
     */
    public ArchiveResult archiveWorkspace(String skillName) {
        Path workspaceDir = resolveConventionPath(skillName);
        if (!Files.exists(workspaceDir)) {
            return ArchiveResult.MISSING;
        }

        try {
            Path archiveRoot = getWorkspaceRoot().resolve(".archived");
            Files.createDirectories(archiveRoot);

            String archiveName = sanitizeName(skillName) + "-" + LocalDateTime.now().format(ARCHIVE_TS);
            Path archiveDir = archiveRoot.resolve(archiveName);

            Files.move(workspaceDir, archiveDir, StandardCopyOption.ATOMIC_MOVE);
            log.info("Archived skill workspace: {} → {}", workspaceDir, archiveDir);
            eventPublisher.publishEvent(new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.ARCHIVED, archiveDir));
            return ArchiveResult.MOVED;
        } catch (IOException e) {
            log.warn("Failed to archive workspace for skill '{}': {}", skillName, e.getMessage());
            return ArchiveResult.FAILED;
        }
    }

    /**
     * Move the most recent {@code .archived/{name}-{ts}/} directory back to
     * the convention path. Symmetric to {@link #archiveWorkspace}.
     *
     * <p>Returns {@link RestoreResult#MISSING} when there is no archive
     * directory to restore (a DB-only skill, or the convention path is
     * already populated) — callers treat this as a no-op. Returns
     * {@link RestoreResult#FAILED} when an archive directory exists but the
     * move-back fails.
     */
    public RestoreResult restoreWorkspace(String skillName) {
        Path target = resolveConventionPath(skillName);
        if (Files.exists(target)) {
            log.warn("restoreWorkspace skipped: target {} already exists", target);
            return RestoreResult.MISSING;
        }
        Path archiveRoot = getWorkspaceRoot().resolve(".archived");
        if (!Files.exists(archiveRoot)) {
            return RestoreResult.MISSING;
        }

        Optional<Path> newest = listArchivedFor(archiveRoot, sanitizeName(skillName));
        if (newest.isEmpty()) {
            return RestoreResult.MISSING;
        }

        try {
            Files.move(newest.get(), target, StandardCopyOption.ATOMIC_MOVE);
            log.info("Restored skill workspace: {} → {}", newest.get(), target);
            eventPublisher.publishEvent(new SkillWorkspaceEvent(
                    skillName, SkillWorkspaceEvent.Type.CREATED, target));
            return RestoreResult.MOVED;
        } catch (IOException e) {
            log.warn("Failed to restore workspace for skill '{}': {}", skillName, e.getMessage());
            return RestoreResult.FAILED;
        }
    }

    /**
     * Most recent archive directory for {@code sanitizedName}. Archive names
     * are {@code {sanitizedName}-{yyyyMMdd-HHmmss}}; the timestamp suffix is
     * matched exactly so a name like {@code foo} never picks up an archive of
     * {@code foo-bar}. Lexical order on the fixed-width timestamp equals
     * chronological order.
     */
    private Optional<Path> listArchivedFor(Path archiveRoot, String sanitizedName) {
        java.util.regex.Pattern suffix =
                java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(sanitizedName) + "-\\d{8}-\\d{6}");
        try (var stream = Files.list(archiveRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> suffix.matcher(p.getFileName().toString()).matches())
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            log.warn("Failed to list archive directory {}: {}", archiveRoot, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 将数据库 skill 内容导出到工作区目录
     */
    public Path exportToWorkspace(String skillName, String skillContent) {
        // 始终覆写 SKILL.md（initWorkspace 内部已写入），无需再做一次冗余 IO
        Path workspaceDir = initWorkspace(skillName, skillContent, true);
        if (workspaceDir != null) {
            log.info("Exported skill '{}' to workspace: {}", skillName, workspaceDir);
            eventPublisher.publishEvent(new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.EXPORTED, workspaceDir));
        }
        return workspaceDir;
    }

    /**
     * 将文件写入 skill 工作区（用于安装时写入 references/scripts）
     * <p>
     * 安全边界：
     * <ul>
     *   <li>relativePath 必须以 references/ 或 scripts/ 开头</li>
     *   <li>禁止 .. 路径遍历</li>
     *   <li>normalize 后必须仍在 workspace 目录内</li>
     * </ul>
     *
     * @param skillName    skill 名称
     * @param relativePath 相对路径（如 references/config.md）
     * @param content      文件内容
     * @throws IllegalArgumentException 如果路径不安全
     */
    public void writeWorkspaceFile(String skillName, String relativePath, String content) {
        Path workspaceDir = resolveConventionPath(skillName);

        // 路径安全校验
        Path safePath = validateWritePath(workspaceDir, relativePath);
        if (safePath == null) {
            log.error("Rejected unsafe write path for skill '{}': {}", skillName, relativePath);
            throw new IllegalArgumentException("Unsafe file path rejected: " + relativePath);
        }

        try {
            Files.createDirectories(safePath.getParent());
            Files.writeString(safePath, content);
        } catch (IOException e) {
            log.warn("Failed to write workspace file {}/{}: {}", skillName, relativePath, e.getMessage());
        }
    }

    /**
     * 清空 skill 工作区中的 references/ 和 scripts/ 目录内容（保留目录本身）
     * 用于 overwrite 安装前清除旧版本残留文件
     */
    public void cleanWorkspaceDataDirs(String skillName) {
        Path workspaceDir = resolveConventionPath(skillName);
        cleanDirectoryContents(workspaceDir.resolve("references"));
        cleanDirectoryContents(workspaceDir.resolve("scripts"));
    }

    /**
     * Outcome of {@link #applyBundleFiles}, exposing per-bucket counters so
     * the installer can log a meaningful summary and the admin UI can show
     * what actually changed.
     */
    public record ApplyBundleResult(
            int referencesWritten,
            int referencesPruned,
            boolean referencesPreservedDueToEmptyBundle,
            int scriptsWritten,
            int scriptsPruned,
            boolean scriptsPreservedDueToEmptyBundle
    ) {}

    /**
     * Apply a bundle's references/ + scripts/ to the workspace using
     * write-then-prune semantics:
     * <ol>
     *   <li>Write every entry from the bundle (overwrites same paths).</li>
     *   <li>Delete any pre-existing file under references/ or scripts/ that
     *       is NOT in the bundle.</li>
     * </ol>
     *
     * <p>Empty-bundle safety: if the bundle has zero entries for a bucket
     * AND the workspace already has files in that bucket, the bucket is
     * left untouched (no pruning) unless {@code force=true}. This protects
     * against malformed uploads, network truncation, and parser bugs that
     * would otherwise wipe a user's scripts on reinstall — the same class
     * of regression that an earlier patch fixed for SKILL.md.
     *
     * @param skillName  workspace owner
     * @param references new bundle's references map (key = path under references/)
     * @param scripts    new bundle's scripts map (key = path under scripts/)
     * @param force      bypass the empty-bundle guard (admin-only switch)
     * @return per-bucket apply summary (never null)
     */
    public ApplyBundleResult applyBundleFiles(String skillName,
                                              Map<String, String> references,
                                              Map<String, String> scripts,
                                              boolean force) {
        Path workspaceDir = resolveConventionPath(skillName);
        try {
            Files.createDirectories(workspaceDir.resolve("references"));
            Files.createDirectories(workspaceDir.resolve("scripts"));
        } catch (IOException e) {
            log.warn("Failed to ensure data dirs for skill '{}': {}", skillName, e.getMessage());
        }

        int refsWritten = applyBucket(skillName, "references/", references);
        int scriptsWritten = applyBucket(skillName, "scripts/", scripts);

        var refsPrune = pruneBucket(workspaceDir.resolve("references"),
                normalizeKeys(references), force, skillName, "references");
        var scriptsPrune = pruneBucket(workspaceDir.resolve("scripts"),
                normalizeKeys(scripts), force, skillName, "scripts");

        return new ApplyBundleResult(
                refsWritten, refsPrune.deleted(), refsPrune.preservedDueToEmpty(),
                scriptsWritten, scriptsPrune.deleted(), scriptsPrune.preservedDueToEmpty()
        );
    }

    private int applyBucket(String skillName, String bucketPrefix, Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return 0;
        int written = 0;
        for (var e : entries.entrySet()) {
            String key = e.getKey();
            String relative = key.startsWith(bucketPrefix) ? key : (bucketPrefix + key);
            try {
                writeWorkspaceFile(skillName, relative, e.getValue());
                written++;
            } catch (RuntimeException ex) {
                log.warn("Failed to write {} for skill '{}': {}", relative, skillName, ex.getMessage());
            }
        }
        return written;
    }

    /** Strip a leading "<bucket>/" prefix so the key matches the path relative to the bucket dir. */
    private Set<String> normalizeKeys(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>(entries.size() * 2);
        for (String key : entries.keySet()) {
            String k = key.replace('\\', '/');
            int firstSlash = k.indexOf('/');
            if (firstSlash > 0 && (k.startsWith("references/") || k.startsWith("scripts/"))) {
                out.add(k.substring(firstSlash + 1));
            } else {
                out.add(k);
            }
        }
        return out;
    }

    private record PruneOutcome(int deleted, boolean preservedDueToEmpty) {}

    private PruneOutcome pruneBucket(Path bucketDir, Set<String> keep, boolean force,
                                     String skillName, String bucketLabel) {
        if (!Files.exists(bucketDir) || !Files.isDirectory(bucketDir)) {
            return new PruneOutcome(0, false);
        }

        // Empty-bundle guard: if the new bundle has nothing for this bucket
        // and there's at least one file on disk, refuse to prune unless the
        // caller explicitly asked for it. Logged so the operator can see why
        // their "clean install" didn't actually clean.
        if (keep.isEmpty() && !force) {
            try (var stream = Files.walk(bucketDir)) {
                boolean hasAny = stream.filter(Files::isRegularFile).findFirst().isPresent();
                if (hasAny) {
                    log.warn("Refusing to prune {}/{}/ — new bundle is empty and would wipe existing files. " +
                            "Pass force=true to override.", skillName, bucketLabel);
                    return new PruneOutcome(0, true);
                }
            } catch (IOException e) {
                log.warn("Failed to inspect {}/{}/: {}", skillName, bucketLabel, e.getMessage());
                return new PruneOutcome(0, false);
            }
        }

        int deleted = 0;
        try (var stream = Files.walk(bucketDir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String relative = bucketDir.relativize(file).toString().replace('\\', '/');
                if (!keep.contains(relative)) {
                    try {
                        Files.delete(file);
                        deleted++;
                    } catch (IOException e) {
                        log.warn("Failed to prune {}/{}/{}: {}", skillName, bucketLabel, relative, e.getMessage());
                    }
                }
            }
            // Best-effort: tidy up emptied subdirs (leave the bucket root in place).
            try (var dirs = Files.walk(bucketDir)) {
                dirs.sorted(java.util.Comparator.reverseOrder())
                        .filter(p -> Files.isDirectory(p) && !p.equals(bucketDir))
                        .forEach(p -> {
                            try (var children = Files.list(p)) {
                                if (children.findAny().isEmpty()) Files.delete(p);
                            } catch (IOException ignored) {
                                /* leave non-empty / locked dirs in place */
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("Failed to prune {}/{}/: {}", skillName, bucketLabel, e.getMessage());
        }
        return new PruneOutcome(deleted, false);
    }

    /**
     * 验证写入路径安全性，防止路径逃逸
     *
     * @return 安全的绝对路径，不安全返回 null
     */
    private Path validateWritePath(Path workspaceDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }

        // 归一化分隔符
        String normalized = relativePath.replace("\\", "/");

        // 必须以 references/ 或 scripts/ 开头
        if (!normalized.startsWith("references/") && !normalized.startsWith("scripts/")) {
            return null;
        }

        // 禁止路径遍历元素
        if (normalized.contains("..") || normalized.startsWith("/")) {
            return null;
        }

        // resolve + normalize，然后检查是否仍在 workspace 内
        Path resolved = workspaceDir.resolve(normalized).normalize();
        if (!resolved.startsWith(workspaceDir.normalize())) {
            return null;
        }

        return resolved;
    }

    /**
     * 递归清空目录内容（保留目录本身）
     */
    private void cleanDirectoryContents(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    if (!d.equals(dir)) {
                        Files.delete(d);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean directory {}: {}", dir, e.getMessage());
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 获取 skill 工作区信息
     */
    public Map<String, Object> getWorkspaceInfo(String skillName) {
        Path workspaceDir = resolveConventionPath(skillName);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("skillName", skillName);
        info.put("conventionPath", workspaceDir.toString());
        info.put("exists", Files.exists(workspaceDir));

        if (Files.exists(workspaceDir)) {
            info.put("hasSkillMd", Files.exists(workspaceDir.resolve("SKILL.md")));
            info.put("hasReferences", Files.exists(workspaceDir.resolve("references")));
            info.put("hasScripts", Files.exists(workspaceDir.resolve("scripts")));

            // 计算目录大小
            try {
                AtomicLong size = new AtomicLong(0);
                List<String> files = new ArrayList<>();
                Files.walkFileTree(workspaceDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        size.addAndGet(attrs.size());
                        files.add(workspaceDir.relativize(file).toString());
                        return FileVisitResult.CONTINUE;
                    }
                });
                info.put("totalSizeBytes", size.get());
                info.put("files", files);
            } catch (IOException e) {
                info.put("error", "Failed to scan directory: " + e.getMessage());
            }
        }

        return info;
    }

    // ==================== 工具方法 ====================

    private String sanitizeName(String name) {
        // 移除不安全字符，只保留字母数字、下划线、短横线、点
        return name.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }

    private String buildDefaultSkillMd(String skillName) {
        return """
                ---
                name: %s
                description: ""
                ---

                # %s

                """.formatted(skillName, skillName);
    }
}
