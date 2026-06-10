package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 文件类型检测工具
 * 使用系统 file 命令或扩展名识别 MIME 类型
 */
@Slf4j
@Component
public class FileTypeDetectorTool {

    private static final int COMMAND_TIMEOUT_SECONDS = 5;

    @Tool(description = """
        检测文件的 MIME 类型和文件类别。

        使用场景：
        - 读取文件前判断文件类型
        - 区分文本文件和二进制文档（PDF/Office）
        - 选择合适的读取/提取工具

        返回信息：
        - mimeType: MIME 类型（如 text/plain, application/pdf）
        - fileCategory: 文件类别（text, document, image, archive, binary）
        - suggestedTool: 建议使用的工具（read_file, extract_document_text 等）

        注意：对于 .docx/.pdf 等文档，不会返回 read_file，而是 extract_document_text
        """)
    public String detect_file_type(
            @ToolParam(description = "文件的绝对路径或相对路径") String filePath,
            // RFC-063r §2.5: hidden from LLM by JsonSchemaGenerator. Carries the
            // ChatOrigin so the workspace boundary check honors per-agent basePath.
            @Nullable ToolContext ctx) {

        JSONObject result = new JSONObject();
        result.set("filePath", filePath);

        try {
            Path path;
            try {
                path = vip.mate.tool.guard.WorkspacePathGuard.validatePath(filePath, ctx);
            } catch (IllegalArgumentException e) {
                // Sandbox rejected the literal path. Fall back to chat-upload
                // basename matching before surfacing the boundary error — the
                // LLM may have hallucinated a system path for a real attachment.
                Path attachment = ChatUploadResolver.resolve(filePath);
                if (attachment == null) {
                    return errorResult(filePath, e.getMessage());
                }
                path = attachment;
            }

            if (!Files.exists(path)) {
                // Fall back to chat-upload basename matching for filenames that were
                // sanitized at upload time (e.g. Chinese characters → underscores).
                Path attachment = ChatUploadResolver.resolve(filePath);
                if (attachment == null) {
                    return errorResult(filePath, "文件不存在: " + path);
                }
                log.info("[FileTypeDetector] Resolved chat-upload attachment fallback: {} -> {}", filePath, attachment);
                path = attachment;
            }

            if (Files.isDirectory(path)) {
                return errorResult(filePath, "路径是目录而非文件");
            }

            // 1. 优先使用系统 file 命令（最准确）
            String mimeType = detectWithFileCommand(path);

            // 2. 降级：基于扩展名检测
            if (mimeType == null || mimeType.isBlank() || "application/octet-stream".equals(mimeType)) {
                mimeType = detectByExtension(path);
            }

            // 3. 降级：基于内容魔数检测
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = detectByMagicNumbers(path);
            }

            String fileCategory = categorizeMimeType(mimeType);
            String suggestedTool = suggestTool(fileCategory, mimeType);

            result.set("mimeType", mimeType);
            result.set("fileCategory", fileCategory);
            result.set("suggestedTool", suggestedTool);
            result.set("fileName", path.getFileName().toString());
            result.set("fileSize", Files.size(path));

            // 添加针对不同类别的指导
            result.set("guidance", buildGuidance(fileCategory, suggestedTool));

            log.info("[FileTypeDetector] {} -> {} (category: {}, tool: {})",
                    filePath, mimeType, fileCategory, suggestedTool);

        } catch (Exception e) {
            log.error("[FileTypeDetector] 检测失败: {}", e.getMessage(), e);
            return errorResult(filePath, "检测失败: " + e.getMessage());
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    /**
     * 使用系统 file 命令检测 MIME 类型
     */
    private String detectWithFileCommand(Path path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "file", "-b", "--mime-type", path.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("file 命令超时");
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    return line.trim();
                }
            }
        } catch (IOException e) {
            log.debug("file 命令不可用: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("file 命令被中断");
        }
        return null;
    }

    /**
     * 基于文件扩展名检测 MIME 类型
     */
    private String detectByExtension(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();

        // 文档类
        if (fileName.endsWith(".pdf")) return "application/pdf";
        if (fileName.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (fileName.endsWith(".doc")) return "application/msword";
        if (fileName.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (fileName.endsWith(".xls")) return "application/vnd.ms-excel";
        if (fileName.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (fileName.endsWith(".ppt")) return "application/vnd.ms-powerpoint";

        // 文本类
        if (fileName.endsWith(".txt")) return "text/plain";
        if (fileName.endsWith(".md")) return "text/markdown";
        if (fileName.endsWith(".json")) return "application/json";
        if (fileName.endsWith(".xml")) return "application/xml";
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) return "application/yaml";
        if (fileName.endsWith(".csv")) return "text/csv";
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) return "text/html";
        if (fileName.endsWith(".css")) return "text/css";
        if (fileName.endsWith(".js")) return "application/javascript";
        if (fileName.endsWith(".java")) return "text/x-java-source";
        if (fileName.endsWith(".py")) return "text/x-python";
        if (fileName.endsWith(".sh")) return "text/x-shellscript";
        if (fileName.endsWith(".sql")) return "text/x-sql";
        if (fileName.endsWith(".log")) return "text/plain";
        if (fileName.endsWith(".ini")) return "text/plain";
        if (fileName.endsWith(".conf")) return "text/plain";
        if (fileName.endsWith(".properties")) return "text/plain";

        // 图片类
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
        if (fileName.endsWith(".gif")) return "image/gif";
        if (fileName.endsWith(".bmp")) return "image/bmp";
        if (fileName.endsWith(".svg")) return "image/svg+xml";
        if (fileName.endsWith(".webp")) return "image/webp";

        // 压缩包类
        if (fileName.endsWith(".zip")) return "application/zip";
        if (fileName.endsWith(".tar")) return "application/x-tar";
        if (fileName.endsWith(".gz")) return "application/gzip";
        if (fileName.endsWith(".bz2")) return "application/x-bzip2";
        if (fileName.endsWith(".7z")) return "application/x-7z-compressed";
        if (fileName.endsWith(".rar")) return "application/vnd.rar";

        return null;
    }

    /**
     * 基于文件内容魔数检测 MIME 类型
     */
    private String detectByMagicNumbers(Path path) {
        try {
            byte[] header = Files.readAllBytes(path);
            if (header.length < 4) return null;

            // PDF: %PDF
            if (header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46) {
                return "application/pdf";
            }

            // ZIP (DOCX/XLSX/PPTX): PK
            if (header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04) {
                // 尝试进一步识别 Office Open XML
                return detectOfficeType(path);
            }

            // PNG
            if (header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) {
                return "image/png";
            }

            // JPEG
            if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8) {
                return "image/jpeg";
            }

            // GIF
            if (header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46) {
                return "image/gif";
            }

        } catch (IOException e) {
            log.debug("魔数检测失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 检测 Office Open XML 文档类型
     */
    private String detectOfficeType(Path path) {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                Files.newInputStream(path))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                // DOCX 包含 word/document.xml
                if (name.equals("word/document.xml")) {
                    return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                }
                // XLSX 包含 xl/workbook.xml
                if (name.equals("xl/workbook.xml")) {
                    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                }
                // PPTX 包含 ppt/presentation.xml
                if (name.equals("ppt/presentation.xml")) {
                    return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                }
            }
        } catch (IOException e) {
            log.debug("Office 类型检测失败: {}", e.getMessage());
        }
        return "application/zip";
    }

    /**
     * 根据 MIME 类型分类文件
     */
    private String categorizeMimeType(String mimeType) {
        if (mimeType == null) return "unknown";

        if (mimeType.startsWith("text/")) return "text";
        if (mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("yaml")) return "text";
        if (mimeType.contains("javascript") || mimeType.contains("sql")) return "text";
        if (mimeType.contains("markdown") || mimeType.contains("csv")) return "text";

        if (mimeType.equals("application/pdf")) return "document";
        if (mimeType.contains("officedocument") || mimeType.contains("msword") ||
                mimeType.contains("ms-excel") || mimeType.contains("ms-powerpoint")) return "document";

        if (mimeType.startsWith("image/")) return "image";

        if (mimeType.contains("zip") || mimeType.contains("tar") || mimeType.contains("gzip") ||
                mimeType.contains("bzip") || mimeType.contains("7z") || mimeType.contains("rar")) {
            return "archive";
        }

        return "binary";
    }

    /**
     * 根据文件类别建议工具
     */
    private String suggestTool(String fileCategory, String mimeType) {
        return switch (fileCategory) {
            case "text" -> "read_file";
            case "document" -> "extract_document_text";
            case "image" -> "(images not yet supported for text extraction)";
            case "archive" -> "(archives require extraction first)";
            case "binary" -> "(binary files cannot be read as text)";
            default -> "unknown";
        };
    }

    /**
     * 构建使用指导
     */
    private String buildGuidance(String fileCategory, String suggestedTool) {
        return switch (fileCategory) {
            case "text" -> "这是文本文件，可以使用 read_file 工具读取";
            case "document" -> "这是 Office/PDF 文档，请使用 " + suggestedTool + " 工具提取文本内容";
            case "image" -> "这是图片文件，当前不支持直接提取文本，如需 OCR 请先转换";
            case "archive" -> "这是压缩包，需要先解压才能读取内容";
            case "binary" -> "这是二进制文件，无法作为文本读取";
            default -> "无法确定文件类型，请谨慎处理";
        };
    }

    private String errorResult(String filePath, String message) {
        JSONObject result = new JSONObject();
        result.set("filePath", filePath);
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
