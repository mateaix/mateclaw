package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 内置工具：读取文件内容
 * <p>
 * 支持按行范围读取，自动截断超大输出。
 * 支持 line-based range、smart truncation、continuation hints。
 * <p>
 * 重要限制：此工具仅支持文本文件，不处理 PDF/Office 文档。
 * 对于 .pdf/.docx/.xlsx/.pptx 等文档，请使用 extract_document_text 工具。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class ReadFileTool {

    private final vip.mate.i18n.I18nService i18n;

    private static final int DEFAULT_MAX_LINES = 1000;
    private static final int MAX_OUTPUT_BYTES = 30 * 1024; // 30KB

    /**
     * 二进制文档扩展名集合 - 这些文件不应使用 read_file 读取
     */
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".odt", ".ods", ".odp", ".rtf"
    );

    @Tool(description = """
            Read the contents of a file. Supports line-range reading (1-based). \
            Returns structured JSON with filePath, totalLines, readLines, content. \
            Auto-truncates large files with continuation hints: when the result has \
            truncated=true, continue with the returned nextStartLine (and \
            nextStartColumn when present, to resume reading the rest of a very long \
            line). Text files only; use extract_document_text for PDF/Office documents.""")
    public String read_file(
            @ToolParam(description = "Absolute or relative file path") String filePath,
            @ToolParam(description = "Start line number (1-based, inclusive). Omit to start from line 1", required = false) Integer startLine,
            @ToolParam(description = "End line number (1-based, inclusive). Omit to read to EOF or truncation limit", required = false) Integer endLine,
            @ToolParam(description = "Start character position within startLine (1-based, inclusive). Used to resume reading the rest of a very long line; pass the nextStartColumn from a previous truncated result. Omit to start at the beginning of the line", required = false) Integer startColumn,
            // RFC-063r §2.5: hidden from LLM by JsonSchemaGenerator.
            @Nullable ToolContext ctx) {

        JSONObject result = new JSONObject();
        result.set("filePath", filePath);

        try {
            Path path;
            try {
                // RFC-063r §2.5: forward ToolContext so workspace boundary
                // honors ChatOrigin.workspaceBasePath when available.
                path = vip.mate.tool.guard.WorkspacePathGuard.validatePath(filePath, ctx);
            } catch (IllegalArgumentException e) {
                // Sandbox rejected the literal path. The LLM may have hallucinated
                // a Linux-style path (e.g. /app/Dockerfile) for a chat-upload that
                // actually lives under data/chat-uploads/{conversationId}/. Retry
                // by basename before surfacing the boundary error.
                Path attachment = ChatUploadResolver.resolve(filePath);
                if (attachment == null) {
                    return errorResult(filePath, e.getMessage());
                }
                path = attachment;
            }

            // 文件存在性和类型校验
            if (!Files.exists(path)) {
                // The user-uploaded chat attachment is rendered to the LLM as
                // "[附件] foo.txt" without its stored path, so LLMs often pass
                // just the basename or a guessed absolute path. Fall back to
                // looking up the basename inside the current conversation's
                // chat-upload directory before reporting not-found.
                Path attachment = ChatUploadResolver.resolve(filePath);
                if (attachment == null) {
                    return errorResult(filePath, i18n.msg("tool.read_file.error.not_found", path));
                }
                log.info("[ReadFile] Resolved chat-upload attachment fallback: {} -> {}", filePath, attachment);
                path = attachment;
            }
            if (Files.isDirectory(path)) {
                return errorResult(filePath, i18n.msg("tool.read_file.error.is_directory", path));
            }
            if (!Files.isReadable(path)) {
                return errorResult(filePath, i18n.msg("tool.read_file.error.not_readable", path));
            }

            // 检查是否是二进制文档 - 拒绝直接读取
            String fileName = path.getFileName().toString().toLowerCase();
            for (String ext : DOCUMENT_EXTENSIONS) {
                if (fileName.endsWith(ext)) {
                    return errorResult(filePath, buildDocumentErrorMessage(fileName, ext));
                }
            }

            // 读取所有行
            List<String> allLines = readLinesUtf8(path);
            int totalLines = allLines.size();
            result.set("totalLines", totalLines);

            // 解析行范围
            int start = (startLine != null && startLine > 0) ? startLine : 1;
            int end = (endLine != null && endLine > 0) ? endLine : totalLines;

            // 范围校验
            if (start > totalLines) {
                return errorResult(filePath, i18n.msg("tool.read_file.error.start_exceeds", start, totalLines));
            }
            start = Math.max(1, start);
            end = Math.min(end, totalLines);
            if (start > end) {
                return errorResult(filePath, i18n.msg("tool.read_file.error.start_gt_end", start, end));
            }

            // 提取指定范围的行（转为 0-based）
            List<String> selectedLines = allLines.subList(start - 1, end);

            // Character offset into the FIRST selected line, used to resume reading
            // the tail of a very long line across calls. 1-based on the wire, 0-based
            // here. Only applies to the first line of the selection.
            int firstLineOffset = (startColumn != null && startColumn > 1) ? startColumn - 1 : 0;

            // Truncation control. Each output line carries a "%6d\t" prefix and a
            // trailing newline, so the budget available for a line's own text is the
            // remaining budget minus that overhead.
            StringBuilder sb = new StringBuilder();
            int linesRead = 0;
            boolean truncated = false;
            boolean lineTruncated = false;
            int truncatedLineNum = 0;
            // Where a subsequent read_file call should resume. nextLine is 1-based;
            // nextColumn is a 1-based char offset (1 = start of the line).
            int nextLine = -1;
            int nextColumn = 1;

            for (int i = 0; i < selectedLines.size(); i++) {
                int lineNum = start + i;
                String fullLine = selectedLines.get(i);
                // The offset only applies to the first line of the selection.
                int offset = (i == 0) ? Math.min(firstLineOffset, fullLine.length()) : 0;
                String line = offset > 0 ? fullLine.substring(offset) : fullLine;

                if (linesRead >= DEFAULT_MAX_LINES) {
                    // Hit the line-count cap; resume at this line from the same offset.
                    truncated = true;
                    nextLine = lineNum;
                    nextColumn = offset + 1;
                    break;
                }

                String prefix = String.format("%6d\t", lineNum);
                int lineCost = prefix.length() + line.length() + 1; // +1 for '\n'

                if (sb.length() + lineCost <= MAX_OUTPUT_BYTES) {
                    sb.append(prefix).append(line).append('\n');
                    linesRead++;
                    continue;
                }

                // This line does not fit in the remaining budget.
                boolean fitsAlone = prefix.length() + line.length() + 1 <= MAX_OUTPUT_BYTES;
                if (fitsAlone || linesRead > 0) {
                    // EITHER the line would fit in a fresh budget (normal truncation
                    // at a clean line boundary), OR we have already emitted lines and
                    // defer this oversized line to the next call. Either way, resume
                    // at this line; for non-first lines offset is 0 so column is 1.
                    truncated = true;
                    nextLine = lineNum;
                    nextColumn = offset + 1;
                    break;
                }

                // linesRead == 0 AND the line is larger than the whole budget even on
                // its own. Returning empty content here would yield readLines=0 with a
                // continuation hint that never advances — the infinite retry loop from
                // the original bug. Emit as much of this line as fits (a window),
                // flagged truncated, and advance by exactly the chars consumed so the
                // caller can page through the rest of the line with nextStartColumn.
                String marker = i18n.msg("tool.read_file.line_truncated_marker");
                int windowBudget = MAX_OUTPUT_BYTES - prefix.length() - marker.length() - 1;
                String window = safeTruncate(line, Math.max(0, windowBudget));
                sb.append(prefix).append(window).append(marker).append('\n');
                linesRead++;
                truncated = true;
                lineTruncated = true;
                truncatedLineNum = lineNum;
                int consumed = offset + window.length();
                if (consumed < fullLine.length()) {
                    nextLine = lineNum;       // more of this line remains
                    nextColumn = consumed + 1;
                } else {
                    nextLine = lineNum + 1;   // line exactly consumed; move on
                    nextColumn = 1;
                }
                break;
            }

            result.set("startLine", start);
            result.set("startColumn", firstLineOffset + 1);
            result.set("endLine", start + linesRead - 1);
            result.set("readLines", linesRead);
            result.set("content", sb.toString());

            if (truncated) {
                result.set("truncated", true);
                result.set("nextStartLine", nextLine);
                int kb = MAX_OUTPUT_BYTES / 1024;
                if (lineTruncated && nextColumn > 1) {
                    // A long line was windowed and more of it remains. Surface the
                    // column so the caller can resume reading the same line's tail.
                    result.set("lineTruncated", true);
                    result.set("nextStartColumn", nextColumn);
                    result.set("message", i18n.msg("tool.read_file.line_truncated",
                            truncatedLineNum, kb, nextLine, nextColumn, nextLine + 1));
                } else {
                    if (lineTruncated) {
                        result.set("lineTruncated", true);
                    }
                    result.set("message", i18n.msg("tool.read_file.truncated", DEFAULT_MAX_LINES, kb, nextLine));
                }
            } else {
                result.set("truncated", false);
            }

            log.info("[ReadFile] Read {} lines from {} (lines {}-{})", linesRead, path, start, start + linesRead - 1);

        } catch (Exception e) {
            log.error("[ReadFile] Failed to read file: {}", e.getMessage(), e);
            return errorResult(filePath, i18n.msg("tool.read_file.error.read_exception", e.getMessage()));
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    /**
     * 构建文档类型错误消息，引导用户使用正确的工具
     */
    private String buildDocumentErrorMessage(String fileName, String ext) {
        StringBuilder sb = new StringBuilder();
        sb.append("无法直接读取二进制文档: ").append(fileName).append("\n\n");
        sb.append("这是 ").append(ext.toUpperCase()).append(" 格式的 Office/PDF 文档，");
        sb.append("不能作为纯文本读取。\n\n");
        sb.append("请使用以下工具之一：\n");

        switch (ext) {
            case ".pdf" -> sb.append("- extract_pdf_text(filePath=\"").append(fileName).append("\")\n");
            case ".docx", ".doc" -> sb.append("- extract_docx_text(filePath=\"").append(fileName).append("\")\n");
            default -> sb.append("- extract_document_text(filePath=\"").append(fileName).append("\")\n");
        }
        sb.append("- extract_document_text(filePath=\"").append(fileName).append("\") - 通用文档提取\n");

        sb.append("\n或者先检测文件类型：\n");
        sb.append("- detect_file_type(filePath=\"").append(fileName).append("\")");

        return sb.toString();
    }

    /**
     * Truncate a string to at most {@code maxChars} characters without splitting
     * a UTF-16 surrogate pair. If the cut would land between a high and low
     * surrogate, drop the dangling high surrogate so the result stays valid.
     */
    private static String safeTruncate(String s, int maxChars) {
        if (s.length() <= maxChars) {
            return s;
        }
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * 以 UTF-8 读取文件全部行，对非 UTF-8 文件做容错处理
     */
    private List<String> readLinesUtf8(Path path) throws IOException {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException e) {
            // 回退：以字节读取再忽略不合法字符
            log.warn("[ReadFile] Non-UTF8 file, fallback with replacement: {}", path);
            byte[] bytes = Files.readAllBytes(path);
            String content = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String line : content.split("\n", -1)) {
                lines.add(line);
            }
            return lines;
        }
    }

    private String errorResult(String filePath, String message) {
        JSONObject result = new JSONObject();
        result.set("filePath", filePath);
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
