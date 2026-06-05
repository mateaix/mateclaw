package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

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
        return scanWithPatterns(kbId, patterns);
    }

    // ==================== private ====================

    private ScanResult scanWithPatterns(Long kbId, List<String> patterns) {
        List<FileCandidate> candidates = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int maxFiles = properties.getMaxScanFiles();
        long maxFileSize = properties.getMaxScanFileSize();

        for (String pattern : patterns) {
            if (candidates.size() >= maxFiles) break;
            collectCandidates(pattern, candidates, errors, maxFiles, maxFileSize);
        }

        // Deduplicate: the same file can be matched by multiple overlapping patterns.
        // Keep first-match order; first-match scanRoot wins for the symlink escape check.
        Set<Path> seen = new LinkedHashSet<>();
        candidates.removeIf(c -> !seen.add(c.file().toAbsolutePath().normalize()));

        int scanned = candidates.size();
        int added = 0;
        int skipped = 0;

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

    private void collectCandidates(String pattern, List<FileCandidate> candidates,
                                   List<String> errors, int maxFiles, long maxFileSize) {
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
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
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

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }
}
