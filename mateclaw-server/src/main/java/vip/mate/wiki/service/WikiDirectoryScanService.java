package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.model.WikiSourceGroupEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Wiki 目录扫描服务
 * <p>
 * 扫描本地目录中的文档文件，为每个文件创建原始材料。
 * 基于 sourcePath 去重，避免重复导入。
 * <p>
 * sourceDirectory 支持换行分隔的多条记录，每条可以是：
 * <ul>
 *   <li>普通目录路径（如 {@code /data/docs}）——递归扫描，按 SUPPORTED_EXTENSIONS 过滤</li>
 *   <li>Glob 模式（如 {@code /data/ocr/**}{@code /*.txt}）——从固定前缀出发，用 PathMatcher 过滤</li>
 * </ul>
 * 以 {@code #} 开头的行视为注释，忽略。路径解析与验证委托给 {@link WikiSourcePathValidator}。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiDirectoryScanService {

    private final WikiKnowledgeBaseService kbService;
    private final WikiRawMaterialService rawService;
    private final WikiProperties properties;
    private final WikiSourcePathValidator pathValidator;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "csv", "pdf", "docx", "doc",
            "pptx", "ppt", "xlsx", "xls", "html", "htm"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv");

    /** Raws mid-flight in these statuses must not be force-rerun by a full scan. */
    private static final Set<String> BUSY_PROCESSING_STATUSES = Set.of("processing", "pending");

    /** 一个待处理候选文件及其所属的扫描根（用于符号链接逃逸检测）。 */
    private record FileCandidate(Path file, Path scanRoot) {}

    /**
     * 扫描结果
     */
    public record ScanResult(int scanned, int added, int skipped, List<String> errors) {}

    /**
     * 扫描指定知识库关联的目录（支持多路径 + glob）
     */
    public ScanResult scan(Long kbId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            return new ScanResult(0, 0, 0, List.of("Knowledge base not found"));
        }
        String dirPath = kb.getSourceDirectory();
        if (dirPath == null || dirPath.isBlank()) {
            return new ScanResult(0, 0, 0, List.of("No source directory configured"));
        }
        return scanDirectory(kbId, dirPath);
    }

    /**
     * 扫描指定路径配置，支持换行分隔的多条路径/Glob 模式。
     * 单条普通路径时与旧行为完全兼容。
     */
    public ScanResult scanDirectory(Long kbId, String directoryPath) {
        List<String> patterns = WikiSourcePathValidator.parseSourcePatterns(directoryPath);
        if (patterns.isEmpty()) {
            return new ScanResult(0, 0, 0, List.of("No source directory configured"));
        }
        return scanWithPatterns(kbId, patterns, null, null, null);
    }

    /**
     * 按来源分组扫描。{@code full=false} 为增量扫描（沿用既有 dedup 语义）；
     * {@code full=true} 会对本次命中但内容未变（skip）的已有 raw 额外触发强制重跑，
     * 复用既有的 setLastProcessedHash(null)+reprocess 组合，不改动 ingest 内部去重逻辑。
     */
    public ScanResult scanGroup(Long kbId, WikiSourceGroupEntity group, boolean full) {
        List<String> patterns = WikiSourcePathValidator.parseSourcePatterns(group.getPath());
        if (patterns.isEmpty()) {
            return new ScanResult(0, 0, 0, List.of("No path configured for this source group"));
        }
        List<Long> skippedRawIds = full ? new ArrayList<>() : null;
        ScanResult result = scanWithPatterns(kbId, patterns, group.getId(), skippedRawIds, group.getFileFilter());
        if (full && skippedRawIds != null && !skippedRawIds.isEmpty()) {
            for (Long rawId : skippedRawIds) {
                rawService.setLastProcessedHash(rawId, null);
                rawService.reprocess(rawId);
            }
        }
        return result;
    }

    // ==================== private ====================

    private ScanResult scanWithPatterns(Long kbId, List<String> patterns, Long groupId, List<Long> skippedRawIdsOut,
                                         String fileFilter) {
        List<FileCandidate> candidates = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int maxFiles = properties.getMaxScanFiles();
        long maxFileSize = properties.getMaxScanFileSize();
        PathMatcher fileFilterMatcher = compileFileFilter(fileFilter);

        for (String pattern : patterns) {
            if (candidates.size() >= maxFiles) break;
            collectCandidates(pattern, candidates, errors, maxFiles, maxFileSize, fileFilterMatcher);
        }

        // Deduplicate: the same file can be matched by multiple overlapping patterns.
        // Keep first-match order; first-match scanRoot wins for the symlink escape check.
        Set<Path> seen = new LinkedHashSet<>();
        candidates.removeIf(c -> !seen.add(c.file().toAbsolutePath().normalize()));

        int scanned = candidates.size();
        int added = 0;
        int skipped = 0;

        // Pre-fetch existing raws for this KB into a sourcePath→raw map, so
        // tagGroupIfNeeded doesn't fire one query per file (N+1 on large scans).
        // Filter out null sourcePath (binary uploads may not have one) —
        // Collectors.toMap throws NPE on null keys (N4 fix).
        Map<String, WikiRawMaterialEntity> existingRawsByPath = groupId != null
                ? rawService.listByKbId(kbId).stream()
                        .filter(r -> r.getSourcePath() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                WikiRawMaterialEntity::getSourcePath, r -> r, (a, b) -> a))
                : Map.of();

        for (FileCandidate candidate : candidates) {
            Path file = candidate.file();
            Path scanRoot = candidate.scanRoot();
            try {
                // Per-file symlink guard: a symlinked file inside an allowed
                // directory could point outside it (e.g. secret.md ->
                // /etc/passwd). Resolve the real path and require it to stay
                // within the validated scan root; skip escapes.
                Path realFile;
                try {
                    realFile = file.toRealPath();
                } catch (IOException e) {
                    realFile = file.toAbsolutePath().normalize();
                }
                if (!realFile.startsWith(scanRoot)) {
                    errors.add("Skipped symlink escaping the scan root: " + file.getFileName());
                    skipped++;
                    continue;
                }
                // From here on operate ONLY on the resolved real path, never the
                // original entry. Reading `realFile` (a concrete, fully-resolved
                // path) closes the TOCTOU window: swapping the symlink after
                // resolution cannot redirect the read. The file name for the
                // title still comes from the directory entry the user sees.
                // Re-check the size on the resolved target — walkFileTree does
                // not follow links, so a symlink's attribute size (the link
                // length) can slip an oversized target past the visitFile gate.
                long realSize;
                try {
                    realSize = Files.size(realFile);
                } catch (IOException e) {
                    errors.add("Failed to stat: " + file.getFileName() + " (" + e.getMessage() + ")");
                    skipped++;
                    continue;
                }
                if (realSize > properties.getMaxScanFileSize()) {
                    errors.add("Skipped oversized file: " + file.getFileName() + " (" + realSize + " bytes)");
                    skipped++;
                    continue;
                }
                String absolutePath = realFile.toString();
                String fileName = file.getFileName().toString();
                String ext = getExtension(fileName);

                if (TEXT_EXTENSIONS.contains(ext)) {
                    // Text files: dedup by content hash, so an unchanged file is
                    // skipped while a modified file (new hash) is re-ingested.
                    String content = Files.readString(realFile, StandardCharsets.UTF_8);
                    boolean fresh = rawService.ingestTextFileFromScan(kbId, fileName, absolutePath, content);
                    tagGroupIfNeeded(kbId, absolutePath, groupId, fresh, skippedRawIdsOut, existingRawsByPath);
                    if (fresh) {
                        added++;
                    } else {
                        skipped++;
                    }
                    continue;
                }

                // Binary files: dedup by content hash too, so a modified file is
                // re-ingested. The unchanged case reads the file once to hash it;
                // only a new/changed file is read again to import.
                String sourceType = switch (ext) {
                    case "pdf" -> "pdf";
                    case "docx", "doc" -> "docx";
                    case "pptx", "ppt" -> "pptx";
                    case "xlsx", "xls" -> "xlsx";
                    case "html", "htm" -> "html";
                    default -> "text";
                };
                boolean freshBinary = rawService.ingestBinaryFileFromScan(
                        kbId, fileName, sourceType, absolutePath, realSize);
                tagGroupIfNeeded(kbId, absolutePath, groupId, freshBinary, skippedRawIdsOut, existingRawsByPath);
                if (freshBinary) {
                    added++;
                } else {
                    skipped++;
                }

            } catch (Exception e) {
                errors.add("Failed to import: " + file.getFileName() + " (" + e.getMessage() + ")");
            }
        }

        if (candidates.size() >= maxFiles) {
            errors.add("Scan limit reached (" + maxFiles + " files). Some files may have been skipped.");
        }

        log.info("[Wiki] Scan completed: patterns={}, scanned={}, added={}, skipped={}, errors={}",
                patterns, scanned, added, skipped, errors.size());

        return new ScanResult(scanned, added, skipped, errors);
    }

    /**
     * 分组扫描时打标 groupId：仅在历史 raw 尚未归属任何分组时补写，从不覆盖已有的
     * （包括手动改挂的）分组归属。这也是历史 raw 回填分组的唯一机制——
     * 不做一次性路径匹配脚本，靠下一次该分组的正常扫描自然回填。
     * <p>
     * 全量扫描的强制重跑队列在这里额外收窄两层：跳过仍在 {@code processing}/
     * {@code pending} 的 raw（否则会把管道里跑到一半的材料重新置 pending，引发并发
     * 处理），以及跳过已被手动迁到其它分组的 raw（否则全量扫描会绕开行级重处理按钮
     * 对处理中行的隐藏这层前端守卫，形成后门）。
     *
     * @param existingRawsByPath 扫描前一次性预取的 sourcePath→raw 映射，避免逐文件查询（N+1）
     */
    private void tagGroupIfNeeded(Long kbId, String absolutePath, Long groupId, boolean fresh,
                                  List<Long> skippedRawIdsOut, Map<String, WikiRawMaterialEntity> existingRawsByPath) {
        if (groupId == null) {
            return;
        }
        WikiRawMaterialEntity raw = existingRawsByPath.get(absolutePath);
        if (raw == null) {
            // freshly ingested in this scan pass — not in the pre-fetch snapshot
            raw = rawService.findBySourcePath(kbId, absolutePath);
            if (raw == null) {
                return;
            }
        }
        Long originalGroupId = raw.getGroupId();
        if (originalGroupId == null) {
            rawService.updateGroup(raw.getId(), groupId);
        }
        if (fresh || skippedRawIdsOut == null) {
            return;
        }
        boolean stillInThisGroup = originalGroupId == null || groupId.equals(originalGroupId);
        boolean busy = raw.getProcessingStatus() != null
                && BUSY_PROCESSING_STATUSES.contains(raw.getProcessingStatus());
        if (stillInThisGroup && !busy) {
            skippedRawIdsOut.add(raw.getId());
        }
    }

    private void collectCandidates(String pattern, List<FileCandidate> candidates,
                                   List<String> errors, int maxFiles, long maxFileSize,
                                   PathMatcher fileFilterMatcher) {
        boolean hasWildcard = containsWildcard(pattern);
        Path scanRoot;
        PathMatcher matcher;
        boolean requireSupportedExt;

        if (!hasWildcard) {
            // Plain directory: walk recursively, filter by SUPPORTED_EXTENSIONS.
            try {
                scanRoot = pathValidator.validateDirectory(pattern);
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
                return;
            }
            if (!Files.exists(scanRoot) || !Files.isDirectory(scanRoot)) {
                errors.add("Not a directory: " + scanRoot);
                return;
            }
            matcher = null;
            requireSupportedExt = true;
        } else {
            // Glob pattern: validate the fixed-prefix base, then apply PathMatcher.
            String basePath = WikiSourcePathValidator.extractBasePath(pattern);
            try {
                scanRoot = pathValidator.validateDirectory(basePath);
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
                return;
            }
            if (!Files.exists(scanRoot)) {
                errors.add("Base directory does not exist: " + scanRoot);
                return;
            }
            // validateDirectory canonicalizes via toRealPath, so scanRoot is the
            // symlink-resolved real path and walkFileTree yields real-path-prefixed
            // files. The matcher must use that resolved base, not the literal pattern
            // prefix — otherwise a symlinked base never matches. Rebuild the pattern
            // by swapping the literal base for the resolved scanRoot, keeping the
            // wildcard tail; escape glob metacharacters in the base so a real
            // directory name containing */?/{}/[] is treated literally.
            String wildcardTail = pattern.substring(basePath.length());
            // Normalise the resolved base to forward slashes: glob uses '/' as its
            // separator, and on Windows scanRoot.toString() yields backslashes that
            // globEscape would escape, producing a pattern that never matches.
            String effectivePattern = globEscape(scanRoot.toString().replace('\\', '/')) + wildcardTail;
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + effectivePattern);
            } catch (IllegalArgumentException e) {
                errors.add("Invalid glob pattern '" + pattern + "': " + e.getMessage());
                return;
            }
            // If the filename segment already specifies an extension (e.g. *.txt),
            // skip the secondary SUPPORTED_EXTENSIONS filter to respect the explicit choice.
            requireSupportedExt = !patternSpecifiesExtension(pattern);
        }

        final Path finalScanRoot = scanRoot;
        final PathMatcher finalMatcher = matcher;
        final boolean finalRequireExt = requireSupportedExt;
        final PathMatcher finalFileFilterMatcher = fileFilterMatcher;

        try {
            Files.walkFileTree(scanRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    String name = d.getFileName().toString();
                    if (name.startsWith(".") && !d.equals(finalScanRoot)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (candidates.size() >= maxFiles) return FileVisitResult.TERMINATE;
                    String fileName = file.getFileName().toString();
                    if (fileName.startsWith(".")) return FileVisitResult.CONTINUE;
                    if (attrs.size() > maxFileSize) {
                        log.debug("[Wiki] Skipping large file: {} ({} bytes)", file, attrs.size());
                        return FileVisitResult.CONTINUE;
                    }
                    String ext = getExtension(fileName);
                    boolean accept;
                    if (finalMatcher != null) {
                        accept = finalMatcher.matches(file.toAbsolutePath());
                        if (accept && finalRequireExt) {
                            accept = SUPPORTED_EXTENSIONS.contains(ext);
                        }
                    } else {
                        accept = SUPPORTED_EXTENSIONS.contains(ext);
                    }
                    if (accept && finalFileFilterMatcher != null) {
                        accept = finalFileFilterMatcher.matches(file.getFileName());
                    }
                    if (accept) {
                        candidates.add(new FileCandidate(file, finalScanRoot));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    errors.add("Cannot read: " + file.getFileName() + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errors.add("Failed to scan '" + pattern + "': " + e.getMessage());
        }
    }

    /**
     * 判断 glob 模式的文件名段是否已显式指定扩展名（如 {@code *.txt}、{@code *.{txt,md}}），
     * 是则不再叠加 SUPPORTED_EXTENSIONS 过滤，以尊重用户的明确选择。
     */
    private static boolean patternSpecifiesExtension(String pattern) {
        int lastSlash = pattern.lastIndexOf('/');
        String lastSeg = lastSlash >= 0 ? pattern.substring(lastSlash + 1) : pattern;
        return lastSeg.contains(".") && containsWildcard(lastSeg);
    }

    private static boolean containsWildcard(String s) {
        return s.contains("*") || s.contains("?") || s.contains("{") || s.contains("[");
    }

    /**
     * 编译分组的 {@code fileFilter}（如 {@code *.pdf}）为文件名级别的 glob matcher，
     * 在候选文件收集阶段作为已有匹配逻辑之外的附加过滤，一次编译、跨所有 pattern 复用。
     */
    private static PathMatcher compileFileFilter(String fileFilter) {
        if (fileFilter == null || fileFilter.isBlank()) {
            return null;
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + fileFilter);
    }

    /** Escape glob metacharacters so a literal path segment is matched verbatim. */
    private static String globEscape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\*?{}[]".indexOf(c) >= 0) {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }
}
