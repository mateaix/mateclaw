package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MateClaw 项目文档读取工具
 * 允许 Agent 在运行时读取内置项目文档（classpath:docs/ 下的 Markdown 文件）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MateClawDocTool {

    private final MateClawDocService docService;

    @Tool(description = """
        Read MateClaw project documentation.
        Use this tool to look up information about MateClaw's features, configuration, and usage.

        Parameters:
        - action: "list" to list all available doc files, "read" to read a specific doc
        - path: (required when action="read") Relative path like "zh/config.md" or "en/quickstart.md"

        Returns: For "list", a list of available doc files grouped by language.
                 For "read", the full markdown content of the specified doc.
        """)
    public String readMateClawDoc(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Action to perform: 'list' or 'read'")
        String action,

        @JsonProperty
        @JsonPropertyDescription("Doc path relative to docs/, e.g. 'zh/config.md' or 'en/quickstart.md'. Required when action='read'.")
        String path
    ) {
        if ("list".equalsIgnoreCase(action)) {
            return listDocs();
        } else if ("read".equalsIgnoreCase(action)) {
            return docService.readRawForTool(path);
        } else {
            return "Error: Unknown action '" + action + "'. Use 'list' or 'read'.";
        }
    }

    private String listDocs() {
        StringBuilder sb = new StringBuilder();
        sb.append("MateClaw Documentation\n\n");

        sb.append("## 中文文档 (zh/)\n");
        appendGroup(sb, "zh");

        sb.append("\n## English Docs (en/)\n");
        appendGroup(sb, "en");

        sb.append("\nUse readMateClawDoc(action=\"read\", path=\"zh/config.md\") to read a specific doc.");
        return sb.toString();
    }

    private void appendGroup(StringBuilder sb, String lang) {
        List<MateClawDocService.DocMeta> docs = docService.list(lang);
        if (docs.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (MateClawDocService.DocMeta doc : docs) {
            sb.append("  - ").append(lang).append('/').append(doc.slug()).append(".md\n");
        }
    }
}
