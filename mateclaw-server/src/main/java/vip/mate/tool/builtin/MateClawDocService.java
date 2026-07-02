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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 一个文档分组：组标题（中/英）+ 该组内文档的有序 slug 列表。 */
    private record DocGroup(String zhLabel, String enLabel, List<String> slugs) {}

    /**
     * 帮助文档的分组与顺序，镜像 VitePress 文档站侧栏 (docs/.vitepress/config.ts)：
     * 开始 → 使用 → 扩展 → 运维 → 开发 → 参考。
     * 磁盘上存在但未登记于此的文档会被归入末尾的「更多 / More」分组——不会丢失，
     * 也提示维护者把它补进对应分组。改了 VitePress 侧栏时，同步更新这里即可保持一致。
     */
    private static final List<DocGroup> STRUCTURE = List.of(
            new DocGroup("开始", "Start",
                    List.of("intro", "quickstart", "desktop")),
            new DocGroup("使用", "Use",
                    List.of("chat", "agents", "goals", "wiki", "memory", "multimodal", "model3d",
                            "channels", "webchat", "wecom-tuning", "ambient-ai", "workflow", "triggers")),
            new DocGroup("扩展", "Extend",
                    List.of("tools", "skills", "mcp", "acp")),
            new DocGroup("运维", "Operate",
                    List.of("console", "backstage", "docker-deploy", "workspaces", "security", "models", "doctor", "config")),
            new DocGroup("开发", "Develop",
                    List.of("api", "architecture", "contributing")),
            new DocGroup("参考", "Reference",
                    List.of("releases", "roadmap", "faq")));

    /** 未登记文档的兜底分组标题。 */
    private static final String OTHER_ZH = "更多";
    private static final String OTHER_EN = "More";

    /** 开头的 YAML frontmatter 块：`---\n ... \n---`。 */
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\n.*?\\n---\\s*\\n", Pattern.DOTALL);
    /** frontmatter 里的 `title:` 字段。 */
    private static final Pattern TITLE_FIELD = Pattern.compile("(?m)^title:\\s*(.+?)\\s*$");
    /** 正文里的首个 ATX 一级标题 `# xxx`。 */
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");

    public record DocMeta(String slug, String title, String group) {}

    /**
     * 列出某语言下的全部文档（排除 index.md），按 {@link #STRUCTURE} 的分组与顺序输出，
     * 每篇带展示标题和所属分组。磁盘上存在但未登记的文档归入末尾「更多」分组，按字母序。
     */
    public List<DocMeta> list(String lang) {
        if (lang == null || !VALID_LANG.matcher(lang).matches()) {
            return List.of();
        }
        // 先扫描磁盘上实际存在的文档：slug -> Resource（保序，作为兜底分组的输入）。
        Map<String, Resource> available = new LinkedHashMap<>();
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
                available.put(slug, r);
            }
        } catch (IOException e) {
            log.debug("No {} docs found: {}", lang, e.getMessage());
        }

        boolean en = "en".equals(lang);
        List<DocMeta> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        // 1) 按结构分组、按顺序输出已存在的文档。
        for (DocGroup g : STRUCTURE) {
            String label = en ? g.enLabel() : g.zhLabel();
            for (String slug : g.slugs()) {
                Resource r = available.get(slug);
                if (r == null) {
                    continue;
                }
                ordered.add(new DocMeta(slug, resolveTitle(r, slug), label));
                placed.add(slug);
            }
        }
        // 2) 未登记于结构的文档归入「更多」分组，按字母序，避免遗漏。
        String otherLabel = en ? OTHER_EN : OTHER_ZH;
        available.entrySet().stream()
                .filter(e -> !placed.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> ordered.add(new DocMeta(e.getKey(), resolveTitle(e.getValue(), e.getKey()), otherLabel)));
        return ordered;
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
        // 侧栏要简洁标题：优先正文首个 H1（如「LLM Wiki 知识库」），
        // 再退回 frontmatter 的 title（可能是较长的 SEO 标题），最后退回 slug。
        Matcher h1 = H1.matcher(stripFrontmatter(raw));
        if (h1.find()) {
            return h1.group(1).trim();
        }
        Matcher fm = FRONTMATTER.matcher(raw);
        if (fm.find()) {
            Matcher title = TITLE_FIELD.matcher(fm.group());
            if (title.find()) {
                return unquote(title.group(1));
            }
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
