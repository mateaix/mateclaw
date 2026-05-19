package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;

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

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "csv", "pdf", "docx", "doc",
            "pptx", "ppt", "xlsx", "xls", "html", "htm"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv");

    /**
     * 扫描结果
     */
    public record ScanResult(int scanned, int added, int skipped, List<String> errors) {}

    /**
     * 扫描指定知识库关联的目录
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
     * 扫描指定目录，为每个支持的文件创建原始材料
     */
    public ScanResult scanDirectory(Long kbId, String directoryPath) {
        Path dir = Paths.get(directoryPath).toAbsolutePath().normalize();

        if (!Files.exists(dir)) {
            return new ScanResult(0, 0, 0, List.of("Directory does not exist: " + dir));
        }
        if (!Files.isDirectory(dir)) {
            return new ScanResult(0, 0, 0, List.of("Path is not a directory: " + dir));
        }

        List<Path> files = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int maxFiles = properties.getMaxScanFiles();
        long maxFileSize = properties.getMaxScanFileSize();

        // 递归遍历目录
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    // 跳过隐藏目录
                    String name = d.getFileName().toString();
                    if (name.startsWith(".") && !d.equals(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (files.size() >= maxFiles) {
                        return FileVisitResult.TERMINATE;
                    }
                    String fileName = file.getFileName().toString();
                    // 跳过隐藏文件
                    if (fileName.startsWith(".")) return FileVisitResult.CONTINUE;
                    // 跳过过大文件
                    if (attrs.size() > maxFileSize) {
                        log.debug("[Wiki] Skipping large file: {} ({} bytes)", file, attrs.size());
                        return FileVisitResult.CONTINUE;
                    }
                    // 检查扩展名
                    String ext = getExtension(fileName);
                    if (SUPPORTED_EXTENSIONS.contains(ext)) {
                        files.add(file);
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
            return new ScanResult(0, 0, 0, List.of("Failed to scan directory: " + e.getMessage()));
        }

        int scanned = files.size();
        int added = 0;
        int skipped = 0;

        for (Path file : files) {
            try {
                String absolutePath = file.toAbsolutePath().normalize().toString();
                String fileName = file.getFileName().toString();
                String ext = getExtension(fileName);

                // 基于 sourcePath 去重
                WikiRawMaterialEntity existing = rawService.findBySourcePath(kbId, absolutePath);
                if (existing != null) {
                    skipped++;
                    continue;
                }

                if (TEXT_EXTENSIONS.contains(ext)) {
                    // 文本文件：读取内容
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    rawService.addText(kbId, fileName, content);
                } else {
                    // 二进制文件：直接引用原始路径，不复制
                    String sourceType = switch (ext) {
                        case "pdf" -> "pdf";
                        case "docx", "doc" -> "docx";
                        case "pptx", "ppt" -> "pptx";
                        case "xlsx", "xls" -> "xlsx";
                        case "html", "htm" -> "html";
                        default -> "text";
                    };
                    rawService.addFile(kbId, fileName, sourceType, absolutePath, Files.size(file));
                }
                added++;

            } catch (Exception e) {
                errors.add("Failed to import: " + file.getFileName() + " (" + e.getMessage() + ")");
            }
        }

        if (files.size() >= maxFiles) {
            errors.add("Scan limit reached (" + maxFiles + " files). Some files may have been skipped.");
        }

        log.info("[Wiki] Directory scan completed: dir={}, scanned={}, added={}, skipped={}, errors={}",
                directoryPath, scanned, added, skipped, errors.size());

        return new ScanResult(scanned, added, skipped, errors);
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }
}
