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
import vip.mate.tool.document.MarkdownPptxRenderer;

/**
 * Render a brand-new .pptx deck from Markdown, in-process via Apache POI.
 * The LLM produces a Marp-style markdown body where {@code ---} separates
 * slides, {@code # / ## / ###} is the slide title, and {@code - item} are
 * bullets.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PptxRenderTool {

    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private final MarkdownPptxRenderer renderer;
    private final GeneratedFileCache cache;

    @Tool(description = """
        Render a NEW .pptx slide deck from Markdown and return a one-time download URL.
        Use for creating presentations: pitch decks, project plans, talks, briefings.

        Markdown convention (Marp-style):
          - `---` on its own line separates slides.
          - The first `# / ## / ###` of a slide becomes its title.
          - Lines starting with `-` or `*` become bullet points.
          - Other non-blank lines become plain paragraphs.
          - `<!-- speaker note -->` HTML comments become speaker notes.

        Example:
            # My Presentation

            By Author Name

            ---

            ## Topic 1

            - Point one
            - Point two
            - Point three

            <!-- Remember to mention the timeline here. -->

            ---

            ## Conclusion

            Thanks!

        For markdown bodies larger than ~5 KB, prefer renderPptxFromFile (read
        from disk) — passing huge markdown as a tool argument burns LLM tokens.

        Returns a markdown link the user can click to download the file.
        The link is valid for 10 minutes.
        """)
    public String renderPptx(
            @ToolParam(description = "Slide content in Marp-style Markdown ('---' between slides)")
            String markdown,
            @ToolParam(description = "Output filename without extension, e.g. 'pitch-deck'")
            String filename,
            @ToolParam(description = "Aspect ratio: '16:9' (default, widescreen) or '4:3' (legacy)", required = false)
            String aspectRatio,
            @Nullable ToolContext ctx) {

        if (markdown == null || markdown.isBlank()) {
            return "错误：markdown 参数为空，无法生成演示文稿。";
        }

        String displayName = FilenameSanitizer.sanitize(filename, "presentation", ".pptx") + ".pptx";
        String ratio = resolveRatio(aspectRatio);

        try {
            long t0 = System.currentTimeMillis();
            byte[] bytes = renderer.render(markdown, ratio);
            log.info("[PptxRender] generated {} ({} bytes, {}ms)",
                    displayName, bytes.length, System.currentTimeMillis() - t0);
            return GeneratedFileLink.resultZh(bytes, displayName, PPTX_MIME, cache, "演示文稿", ctx);
        } catch (Exception e) {
            log.error("[PptxRender] render failed for {}: {}", displayName, e.getMessage(), e);
            return "渲染失败：" + e.getMessage();
        }
    }

    @Tool(description = """
        Render a .pptx deck from a markdown FILE on disk and return a one-time download URL.
        Use this instead of `renderPptx` when the markdown body is large (>5 KB) — the
        LLM does not need to repeat its own previous output as a tool argument.

        Typical workflow:
          1. write_file(path="deck.md", content="# Title\\n\\n---\\n\\n## Topic\\n\\n- ...")
          2. renderPptxFromFile(filePath="deck.md", filename="pitch-deck")
          3. return the download link to the user

        The markdown file is read with UTF-8. Path resolution honors the workspace
        boundary (same rules as read_file / write_file).

        Same supported Marp-style markdown subset as renderPptx (`---` slide breaks,
        `# / ##` titles, `-` / `*` bullets, `<!-- ... -->` speaker notes).
        """)
    public String renderPptxFromFile(
            @ToolParam(description = "Absolute or workspace-relative path to a markdown file")
            String filePath,
            @ToolParam(description = "Output filename without extension, e.g. 'pitch-deck'")
            String filename,
            @ToolParam(description = "Aspect ratio: '16:9' (default) or '4:3'", required = false)
            String aspectRatio,
            @Nullable ToolContext ctx) {

        Resolved input;
        try {
            input = MarkdownInputResolver.readSingle(filePath);
        } catch (ResolveException e) {
            return "Error: " + e.getMessage();
        }

        String displayName = FilenameSanitizer.sanitize(filename, "presentation", ".pptx") + ".pptx";
        String ratio = resolveRatio(aspectRatio);

        try {
            long t0 = System.currentTimeMillis();
            byte[] bytes = renderer.render(input.markdown(), ratio);
            log.info("[PptxRender] generated {} ({} bytes from {} bytes md, {}ms)",
                    displayName, bytes.length, input.totalBytes(),
                    System.currentTimeMillis() - t0);
            return GeneratedFileLink.resultEn(bytes, displayName, PPTX_MIME, cache, "Presentation", 1, ctx);
        } catch (Exception e) {
            log.error("[PptxRender] render failed for {} (source: {}): {}",
                    displayName, input.sources().get(0), e.getMessage(), e);
            return "Render failed: " + e.getMessage();
        }
    }

    private static String resolveRatio(String aspectRatio) {
        return (aspectRatio == null || aspectRatio.isBlank()) ? "16:9" : aspectRatio.trim();
    }
}
