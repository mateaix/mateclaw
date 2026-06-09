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
import vip.mate.tool.document.MarkdownXlsxRenderer;

/**
 * Render a brand-new .xlsx workbook from a Markdown body, in-process via
 * Apache POI. Mirrors {@link DocxRenderTool}'s shape: the LLM produces a
 * Markdown body where each {@code # Heading} starts a sheet and the pipe-style
 * table beneath it becomes the sheet content.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XlsxRenderTool {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final MarkdownXlsxRenderer renderer;
    private final GeneratedFileCache cache;

    @Tool(description = """
        Render a NEW .xlsx workbook from Markdown and return a one-time download URL.
        Use for creating spreadsheets: financial reports, data tables, comparison
        matrices, plans, schedules.

        Markdown convention:
          - Each `# Sheet Name` starts a new sheet.
          - The pipe-style table under the heading becomes the sheet body.
          - The first table row is rendered as the header (bold, light-grey fill,
            frozen). Numeric cells are auto-detected and stored as numbers so
            Excel can sort / sum them; non-numeric cells stay as strings.
          - Sub-headings (## / ###) and free-form prose are ignored — xlsx is
            tabular and there is nowhere sensible to put them.

        Example:
            # Q1 Sales
            | Region | Revenue | Growth |
            | --- | --- | --- |
            | North | 12000 | 0.15 |
            | South | 8500 | 0.08 |

            # Q2 Sales
            | Region | Revenue |
            | --- | --- |
            | North | 14000 |

        For markdown bodies larger than ~5 KB, prefer renderXlsxFromFile (read
        from disk) — passing huge markdown as a tool argument burns LLM tokens.

        Returns a markdown link the user can click to download the file.
        The link is valid for 10 minutes.
        """)
    public String renderXlsx(
            @ToolParam(description = "Workbook content in Markdown format (sheets as `# Heading`, tables as `| ... |`)")
            String markdown,
            @ToolParam(description = "Output filename without extension, e.g. 'q1-sales'")
            String filename,
            @Nullable ToolContext ctx) {

        if (markdown == null || markdown.isBlank()) {
            return "错误：markdown 参数为空，无法生成工作簿。";
        }

        String displayName = FilenameSanitizer.sanitize(filename, "workbook", ".xlsx") + ".xlsx";

        try {
            long t0 = System.currentTimeMillis();
            byte[] bytes = renderer.render(markdown);
            log.info("[XlsxRender] generated {} ({} bytes, {}ms)",
                    displayName, bytes.length, System.currentTimeMillis() - t0);
            return GeneratedFileLink.resultZh(bytes, displayName, XLSX_MIME, cache, "工作簿", ctx);
        } catch (Exception e) {
            log.error("[XlsxRender] render failed for {}: {}", displayName, e.getMessage(), e);
            return "渲染失败：" + e.getMessage();
        }
    }

    @Tool(description = """
        Render a .xlsx workbook from a markdown FILE on disk and return a one-time download URL.
        Use this instead of `renderXlsx` when the markdown body is large (>5 KB) — the
        LLM does not need to repeat its own previous output as a tool argument.

        Typical workflow:
          1. write_file(path="report.md", content="# Q1\\n| ... |\\n...")
          2. renderXlsxFromFile(filePath="report.md", filename="quarterly-report")
          3. return the download link to the user

        The markdown file is read with UTF-8. Path resolution honors the workspace
        boundary (same rules as read_file / write_file).

        Same supported markdown subset as renderXlsx (`# Heading` per sheet,
        pipe-style tables; numeric cells auto-detected).
        """)
    public String renderXlsxFromFile(
            @ToolParam(description = "Absolute or workspace-relative path to a markdown file")
            String filePath,
            @ToolParam(description = "Output filename without extension, e.g. 'quarterly-report'")
            String filename,
            @Nullable ToolContext ctx) {

        Resolved input;
        try {
            input = MarkdownInputResolver.readSingle(filePath);
        } catch (ResolveException e) {
            return "Error: " + e.getMessage();
        }

        String displayName = FilenameSanitizer.sanitize(filename, "workbook", ".xlsx") + ".xlsx";

        try {
            long t0 = System.currentTimeMillis();
            byte[] bytes = renderer.render(input.markdown());
            log.info("[XlsxRender] generated {} ({} bytes from {} bytes md, {}ms)",
                    displayName, bytes.length, input.totalBytes(),
                    System.currentTimeMillis() - t0);
            return GeneratedFileLink.resultEn(bytes, displayName, XLSX_MIME, cache, "Workbook", 1, ctx);
        } catch (Exception e) {
            log.error("[XlsxRender] render failed for {} (source: {}): {}",
                    displayName, input.sources().get(0), e.getMessage(), e);
            return "Render failed: " + e.getMessage();
        }
    }
}
