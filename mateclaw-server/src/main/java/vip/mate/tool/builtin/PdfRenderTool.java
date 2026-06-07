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
import vip.mate.tool.document.MarkdownInputResolver;
import vip.mate.tool.document.MarkdownInputResolver.Resolved;
import vip.mate.tool.document.MarkdownInputResolver.ResolveException;
import vip.mate.tool.document.pdf.MarkdownPdfRenderer;
import vip.mate.tool.document.pdf.PdfProperties;

import java.util.Locale;

/**
 * Render a brand-new .pdf from Markdown. Two backends sit behind this tool:
 * a LibreOffice subprocess (preferred when {@code soffice} is available, best
 * Chinese typography) and an in-process OpenHTMLtoPDF path (always available,
 * supports cover / page header / page footer driven by YAML frontmatter).
 * The orchestrator picks one per call; see {@link MarkdownPdfRenderer}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfRenderTool {

    private static final String PDF_MIME = "application/pdf";

    private final MarkdownPdfRenderer renderer;
    private final GeneratedFileCache cache;

    @Tool(description = """
        Render a NEW .pdf file from Markdown and return a one-time download URL.

        **MUST use this tool (NOT renderDocx / renderDocxFromFile) whenever the user
        says any of: "PDF", ".pdf", "导出 PDF", "生成 pdf", "另存为 PDF", "出一份 PDF",
        "save as PDF", "export to PDF".** PDF is a final, non-editable deliverable
        format; if the user asked for it explicitly, do not silently substitute docx.

        **Do NOT bypass this tool by shelling out to `chrome --headless --print-to-pdf`,
        `wkhtmltopdf`, `weasyprint`, or any markdown-to-PDF Python skill. Those produce
        a PDF on local disk that is NOT registered in mateclaw's download cache, so
        the user has no clickable download link and the file leaks into the workspace.
        Always use this tool instead — it returns a `/api/v1/files/generated/<id>` URL
        the user can download from chat.**

        Use for FINAL deliverables — reports, white-papers, contracts, briefings —
        where the recipient should not edit the document.

        Markdown convention:
          - Standard subset: headings (# ## ###), bold, italic, lists, tables,
            blockquotes, code blocks, links.
          - Optional YAML frontmatter at the top of the markdown drives cover
            page and page header / footer:

              ---
              title: 季度业务回顾
              subtitle: Q1 2026
              header: 内部资料 - 仅限分发
              footer: Mate Inc. © 2026
              ---

              # 第一章
              ...

          - Without frontmatter, the first `# H1` heading is used as the cover
            title and pages are numbered automatically with no header / footer.

        For markdown bodies larger than ~5 KB, prefer renderPdfFromFile.

        Returns a markdown link the user can click to download the file.
        The link is valid for 10 minutes.
        """)
    public String renderPdf(
            @ToolParam(description = "Document content in Markdown format (optional YAML frontmatter for cover / header / footer)")
            String markdown,
            @ToolParam(description = "Output filename without extension, e.g. 'q1-review'")
            String filename,
            @ToolParam(description = "Page size: A4 or LETTER (default: A4)", required = false)
            String pageSize,
            @ToolParam(description = "Engine: 'auto' (default), 'html' (force in-process), or 'libreoffice' (force soffice)", required = false)
            String engine,
            @Nullable ToolContext ctx) {

        if (markdown == null || markdown.isBlank()) {
            return "错误：markdown 参数为空，无法生成 PDF。";
        }

        String displayName = FilenameSanitizer.sanitize(filename, "document", ".pdf") + ".pdf";
        String size = resolveSize(pageSize);
        PdfProperties.Engine eng = resolveEngine(engine);

        try {
            MarkdownPdfRenderer.Result result = renderer.render(markdown, size, eng);
            log.info("[PdfRender] generated {} ({} bytes via {})",
                    displayName, result.bytes().length, result.backend());
            return GeneratedFileLink.resultZh(result.bytes(), displayName, PDF_MIME, cache, "PDF", ctx);
        } catch (Exception e) {
            log.error("[PdfRender] render failed for {}: {}", displayName, e.getMessage(), e);
            return "渲染失败：" + e.getMessage();
        }
    }

    @Tool(description = """
        Render a .pdf from a markdown FILE on disk and return a one-time download URL.

        **MUST use this tool (NOT renderDocxFromFile) whenever the user asks for a
        PDF / .pdf / 导出 PDF / 生成 pdf and the markdown body is already on disk.**
        Do not silently substitute docx when the user explicitly requested PDF.

        Use this instead of `renderPdf` when the markdown body is large (>5 KB) — the
        LLM does not need to repeat its own previous output as a tool argument.

        Typical workflow:
          1. write_file(path="report.md", content="---\\ntitle: ...\\n---\\n# ...")
          2. renderPdfFromFile(filePath="report.md", filename="q1-review")
          3. return the download link to the user

        The markdown file is read with UTF-8. Path resolution honors the workspace
        boundary (same rules as read_file / write_file).

        Same supported markdown subset and frontmatter convention as renderPdf.
        """)
    public String renderPdfFromFile(
            @ToolParam(description = "Absolute or workspace-relative path to a markdown file")
            String filePath,
            @ToolParam(description = "Output filename without extension, e.g. 'q1-review'")
            String filename,
            @ToolParam(description = "Page size: A4 or LETTER (default: A4)", required = false)
            String pageSize,
            @ToolParam(description = "Engine: 'auto' (default), 'html', or 'libreoffice'", required = false)
            String engine,
            @Nullable ToolContext ctx) {

        Resolved input;
        try {
            input = MarkdownInputResolver.readSingle(filePath);
        } catch (ResolveException e) {
            return "Error: " + e.getMessage();
        }

        String displayName = FilenameSanitizer.sanitize(filename, "document", ".pdf") + ".pdf";
        String size = resolveSize(pageSize);
        PdfProperties.Engine eng = resolveEngine(engine);

        try {
            MarkdownPdfRenderer.Result result = renderer.render(input.markdown(), size, eng);
            log.info("[PdfRender] generated {} ({} bytes via {} from {} bytes md)",
                    displayName, result.bytes().length, result.backend(), input.totalBytes());
            return GeneratedFileLink.resultEn(result.bytes(), displayName, PDF_MIME, cache, "Document", 1, ctx);
        } catch (Exception e) {
            log.error("[PdfRender] render failed for {} (source: {}): {}",
                    displayName, input.sources().get(0), e.getMessage(), e);
            return "Render failed: " + e.getMessage();
        }
    }

    private static String resolveSize(String pageSize) {
        return (pageSize == null || pageSize.isBlank()) ? "A4" : pageSize.trim();
    }

    private static PdfProperties.Engine resolveEngine(String engine) {
        if (engine == null || engine.isBlank()) return PdfProperties.Engine.AUTO;
        try {
            return PdfProperties.Engine.valueOf(engine.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PdfProperties.Engine.AUTO;
        }
    }
}
