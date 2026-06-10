package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.tool.document.FilenameSanitizer;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.document.GeneratedFileLink;
import vip.mate.tool.document.MarkdownDocxRenderer;
import vip.mate.tool.document.MarkdownInputResolver;
import vip.mate.tool.document.MarkdownInputResolver.Resolved;
import vip.mate.tool.document.MarkdownInputResolver.ResolveException;

import java.util.List;

/**
 * Render a brand-new .docx from Markdown without ever forking a process.
 *
 * <p>The previous path forwarded these requests to {@code skills/docx} which
 * runs {@code npm install docx} on first use (3-5 minutes). For "create new
 * document" intents that subprocess is wholly unnecessary; this tool produces
 * the bytes in the JVM, stashes them in {@link GeneratedFileCache}, and
 * returns a Markdown link the user can click to download.
 *
 * <p>The skill workflow is still authoritative for editing existing .docx,
 * tracked changes, and other XML-level operations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocxRenderTool {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final MarkdownDocxRenderer renderer;
    private final GeneratedFileCache cache;

    @Tool(description = """
        Render a new .docx (Microsoft Word) file from Markdown text and return a
        one-time download URL. Use for creating EDITABLE Word documents the user
        will continue to revise — reports, memos, contracts, letters, resumes.

        Supports: headings (# ## ###), bold (**text**), bullet lists (- item),
                  numbered lists (1. item), tables (| col | col |), plain paragraphs,
                  images (![alt](path/to/file.png|jpg|gif|bmp|svg)) — SVG is rasterized
                  to PNG; image lines must contain only the image syntax.

        For markdown bodies larger than ~5 KB, prefer renderDocxFromFile (read from
        disk) — passing huge markdown as a tool argument burns LLM tokens needlessly.

        Do NOT use for:
        - **Anything the user asked for in PDF / .pdf format — use `renderPdf` /
          `renderPdfFromFile` instead. PDF is a separate non-editable deliverable
          format; don't silently substitute docx for it.**
        - Spreadsheets / workbooks — use `renderXlsx` / `renderXlsxFromFile`.
        - Slide decks / presentations — use `renderPptx` / `renderPptxFromFile`.
        - Editing an existing .docx file (use run_skill_script with unpack/edit/pack)
        - Adding tracked changes or comments (use run_skill_script)
        - GB/T 9704 official documents (use writeGongwen tool, BmacClaw only)

        Returns a markdown link the user can click to download the file.
        The link is valid for 10 minutes.
        """)
    public String renderDocx(
            @ToolParam(description = "Document content in Markdown format")
            String markdown,
            @ToolParam(description = "Output filename without extension, e.g. 'monthly-report'")
            String filename,
            @ToolParam(description = "Page size: A4 or LETTER (default: A4)", required = false)
            String pageSize,
            @Nullable ToolContext ctx) {

        if (markdown == null || markdown.isBlank()) {
            return "错误：markdown 参数为空，无法生成文档。";
        }

        String displayName = FilenameSanitizer.sanitize(filename, "document", ".docx") + ".docx";
        String size = resolveSize(pageSize);

        try {
            long t0 = System.currentTimeMillis();
            byte[] bytes = renderer.render(markdown, size);
            log.info("[DocxRender] generated {} ({} bytes, {}ms)",
                    displayName, bytes.length, System.currentTimeMillis() - t0);
            return GeneratedFileLink.resultZh(bytes, displayName, DOCX_MIME, cache, "文档", ctx);
        } catch (Exception e) {
            log.error("[DocxRender] render failed for {}: {}", displayName, e.getMessage(), e);
            return "渲染失败：" + e.getMessage();
        }
    }

    /**
     * File-based renderer — reads markdown from disk instead of taking it as a
     * tool argument. Bypasses the LLM token-cost cliff: a 80 KB markdown body
     * would otherwise be streamed through the chat completion as part of
     * {@code renderDocx.markdown} args (≈ 20 K tokens, several minutes of
     * generation just to repeat back content the LLM already wrote to disk).
     * <p>
     * Workflow: agent uses {@code write_file} / {@code edit_file} to assemble
     * the markdown locally → calls this tool with the file path → docx is
     * rendered from disk in one IO call. Token cost ≈ 50 (just the path).
     */
    @Tool(description = """
        Render a .docx (Microsoft Word) file from a markdown FILE on disk and return
        a one-time download URL. Use this for EDITABLE Word documents only.

        **If the user asked for PDF / .pdf in any wording, use `renderPdfFromFile`
        instead. Do not silently substitute docx for PDF.** Same for spreadsheets
        (`renderXlsxFromFile`) and slide decks (`renderPptxFromFile`).

        Use this instead of `renderDocx` when the markdown body is large (>5 KB) — the
        LLM does not need to repeat its own previous output as a tool argument.

        Typical workflow:
          1. write_file(path="report.md", content="# Report\\n...")  // assemble markdown
          2. renderDocxFromFile(filePath="report.md", filename="monthly-report")
          3. return the download link to the user

        The markdown file is read with UTF-8. Path resolution honors the workspace
        boundary (same rules as read_file / write_file).

        Same supported markdown subset as renderDocx (headings, bold, lists, tables,
        images). Image references ![alt](path) are rendered when path resolves to a
        readable file in the workspace. SVG sources are rasterized to PNG via Batik;
        PNG/JPG/GIF/BMP are embedded directly.
        """)
    public String renderDocxFromFile(
            @ToolParam(description = "Absolute or workspace-relative path to a markdown file")
            String filePath,
            @ToolParam(description = "Output filename without extension, e.g. 'monthly-report'")
            String filename,
            @ToolParam(description = "Page size: A4 or LETTER (default: A4)", required = false)
            String pageSize,
            @Nullable ToolContext ctx) {

        Resolved input;
        try {
            input = MarkdownInputResolver.readSingle(filePath);
        } catch (ResolveException e) {
            return "Error: " + e.getMessage();
        }

        String displayName = FilenameSanitizer.sanitize(filename, "document", ".docx") + ".docx";
        String size = resolveSize(pageSize);

        try {
            long t0 = System.currentTimeMillis();
            byte[] bytes = renderer.render(input.markdown(), size);
            log.info("[DocxRender] generated {} ({} bytes from {} bytes md, {}ms)",
                    displayName, bytes.length, input.totalBytes(), System.currentTimeMillis() - t0);
            return GeneratedFileLink.resultEn(bytes, displayName, DOCX_MIME, cache, "Document", 1, ctx);
        } catch (Exception e) {
            log.error("[DocxRender] render failed for {} (source: {}): {}",
                    displayName, input.sources().get(0), e.getMessage(), e);
            return "Render failed: " + e.getMessage();
        }
    }

    /**
     * Multi-file renderer — read several markdown files in order and concatenate
     * them into one docx. Lets the agent split a long report into chapters
     * (cover.md, intro.md, ch1.md, ...) and render the whole thing in one call,
     * so a 30-page deliverable does not need to live in a single source file.
     * <p>
     * Files are joined with a blank line so heading hierarchy and paragraph
     * structure carry over cleanly; no extra separator markup is injected.
     * Empty / missing files abort the render with a clear error so the agent
     * can fix its file list before retrying.
     */
    @Tool(description = """
        Render a .docx by concatenating MULTIPLE markdown files in order and return a
        download URL. Use when a report is split into chapters / sections, or when the
        agent assembled the document piece by piece (cover, table of contents, body,
        appendix) across several files.

        Typical workflow:
          1. write_file(path="cover.md",  content="# Title\\n...")
          2. write_file(path="ch1.md",    content="## Chapter 1\\n...")
          3. write_file(path="ch2.md",    content="## Chapter 2\\n...")
          4. renderDocxFromFiles(filePaths=["cover.md","ch1.md","ch2.md"],
                                 filename="quarterly-report")

        Files are read with UTF-8, joined with one blank line between them, and
        rendered with the same markdown subset as renderDocx (headings, bold,
        lists, tables). All paths must pass the workspace boundary check.
        """)
    public String renderDocxFromFiles(
            @ToolParam(description = "List of markdown file paths in render order")
            List<String> filePaths,
            @ToolParam(description = "Output filename without extension, e.g. 'quarterly-report'")
            String filename,
            @ToolParam(description = "Page size: A4 or LETTER (default: A4)", required = false)
            String pageSize,
            @Nullable ToolContext ctx) {

        Resolved input;
        try {
            input = MarkdownInputResolver.readManyJoined(filePaths);
        } catch (ResolveException e) {
            return "Error: " + e.getMessage();
        }

        String displayName = FilenameSanitizer.sanitize(filename, "document", ".docx") + ".docx";
        String size = resolveSize(pageSize);

        try {
            long t0 = System.currentTimeMillis();
            byte[] bytes = renderer.render(input.markdown(), size);
            log.info("[DocxRender] generated {} ({} bytes from {} files / {} bytes md, {}ms)",
                    displayName, bytes.length, input.fileCount(), input.totalBytes(),
                    System.currentTimeMillis() - t0);
            return GeneratedFileLink.resultEn(bytes, displayName, DOCX_MIME, cache,
                    "Document", input.fileCount(), ctx);
        } catch (Exception e) {
            log.error("[DocxRender] render failed for {} (sources: {}): {}",
                    displayName, input.sources(), e.getMessage(), e);
            return "Render failed: " + e.getMessage();
        }
    }

    private static String resolveSize(String pageSize) {
        return (pageSize == null || pageSize.isBlank()) ? "A4" : pageSize.trim();
    }
}
