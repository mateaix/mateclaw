package vip.mate.tool.builtin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置项目文档（classpath:docs/{zh,en}/*.md）的读取服务。
 *
 * <p>同时服务两类消费方：给智能体运行时用的 {@link MateClawDocTool}，以及给前端
 * 文档查看器用的 REST 接口。把 classpath 扫描、路径白名单校验、frontmatter 剥离
 * 等逻辑收敛在这里，避免两处重复。
 */
@Slf4j
@Component
public class MateClawDocService {

    /** 合法语言目录。 */
    private static final Pattern VALID_LANG = Pattern.compile("^(zh|en)$");
    /** 合法 slug —— 仅小写字母、数字、连字符、下划线，禁止路径穿越。 */
    private static final Pattern VALID_SLUG = Pattern.compile("^[a-z0-9_-]+$");
    /** 兼容 MateClawDocTool 的旧式 "lang/slug.md" 路径。 */
    private static final Pattern VALID_PATH = Pattern.compile("^(zh|en)/[a-z0-9_-]+\\.md$");
    private static final String DOCS_BASE = "docs/";
    /** VitePress 首页，无正文，从用户可见列表中排除。 */
    private static final String INDEX_SLUG = "index";

    /** 开头的 YAML frontmatter 块：`---\n ... \n---`。 */
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\n.*?\\n---\\s*\\n", Pattern.DOTALL);
    /** frontmatter 里的 `title:` 字段。 */
    private static final Pattern TITLE_FIELD = Pattern.compile("(?m)^title:\\s*(.+?)\\s*$");
    /** 正文里的首个 ATX 一级标题 `# xxx`。 */
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");

    public record DocMeta(String slug, String title) {}

    /**
     * 列出某语言下的全部文档（排除 index.md），按 slug 排序，
     * 每篇带一个用于展示的标题。
     */
    public List<DocMeta> list(String lang) {
        if (lang == null || !VALID_LANG.matcher(lang).matches()) {
            return List.of();
        }
        List<DocMeta> docs = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:docs/" + lang + "/*.md");
            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename == null || !filename.endsWith(".md")) {
                    continue;
                }
                String slug = filename.substring(0, filename.length() - ".md".length());
                if (INDEX_SLUG.equals(slug)) {
                    continue;
                }
                docs.add(new DocMeta(slug, resolveTitle(r, slug)));
            }
        } catch (IOException e) {
            log.debug("No {} docs found: {}", lang, e.getMessage());
        }
        docs.sort((a, b) -> a.slug().compareTo(b.slug()));
        return docs;
    }

    /**
     * 读取 (lang, slug) 对应文档的正文，剥离开头的 YAML frontmatter。
     *
     * @return 正文内容；找不到或参数非法时返回 {@code null}。
     */
    public String read(String lang, String slug) {
        if (lang == null || !VALID_LANG.matcher(lang).matches()) {
            return null;
        }
        if (slug == null || !VALID_SLUG.matcher(slug).matches()) {
            return null;
        }
        String raw = readRaw(lang + "/" + slug + ".md");
        return raw == null ? null : stripFrontmatter(raw);
    }

    /**
     * 按 "lang/slug.md" 形式读取原始文件内容（含 frontmatter），用于
     * {@link MateClawDocTool} 的 read action。返回错误字符串以保持其旧契约。
     */
    String readRawForTool(String path) {
        if (path == null || path.isBlank()) {
            return "Error: 'path' is required when action='read'. Example: 'zh/config.md'";
        }
        if (!VALID_PATH.matcher(path).matches()) {
            return "Error: Invalid path format. Expected pattern: (zh|en)/<topic>.md, e.g. 'zh/config.md'";
        }
        String raw = readRaw(path);
        return raw == null ? "Error: Document not found: " + path : raw;
    }

    private String readRaw(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(DOCS_BASE + path);
            if (!resource.exists()) {
                return null;
            }
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.info("Read doc {}: {} bytes", path, content.length());
                return content;
            }
        } catch (IOException e) {
            log.error("Failed to read doc {}: {}", path, e.getMessage());
            return null;
        }
    }

    private String resolveTitle(Resource resource, String slug) {
        String raw = null;
        try (InputStream is = resource.getInputStream()) {
            raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Failed to read doc for title {}: {}", slug, e.getMessage());
        }
        if (raw == null) {
            return slug;
        }
        Matcher fm = FRONTMATTER.matcher(raw);
        if (fm.find()) {
            Matcher title = TITLE_FIELD.matcher(fm.group());
            if (title.find()) {
                return unquote(title.group(1));
            }
        }
        Matcher h1 = H1.matcher(stripFrontmatter(raw));
        if (h1.find()) {
            return h1.group(1).trim();
        }
        return slug;
    }

    private static String stripFrontmatter(String raw) {
        Matcher m = FRONTMATTER.matcher(raw);
        return m.find() ? raw.substring(m.end()) : raw;
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }
}
