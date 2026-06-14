package vip.mate.doc;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.tool.builtin.MateClawDocService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置帮助文档的只读接口，供前端文档查看器消费。
 *
 * <p>文档本体打包在 classpath:docs/{zh,en}/ 下，与给智能体用的
 * {@link MateClawDocService} 共享同一套扫描/校验逻辑。
 */
@Slf4j
@Tag(name = "Docs")
@RestController
@RequestMapping("/api/v1/docs")
@RequiredArgsConstructor
public class DocController {

    private final MateClawDocService docService;

    @Operation(summary = "列出某语言下的全部帮助文档（slug + 标题）")
    @GetMapping
    public R<List<MateClawDocService.DocMeta>> list(
            @RequestParam(defaultValue = "zh") String lang) {
        return R.ok(docService.list(normalizeLang(lang)));
    }

    @Operation(summary = "读取单篇帮助文档正文（已剥离 frontmatter）")
    @GetMapping("/content")
    public R<Map<String, Object>> content(
            @RequestParam(defaultValue = "zh") String lang,
            @RequestParam String slug) {
        String normLang = normalizeLang(lang);
        String body = docService.read(normLang, slug);
        if (body == null) {
            return R.fail(404, "Document not found");
        }
        String title = docService.list(normLang).stream()
                .filter(d -> d.slug().equals(slug))
                .map(MateClawDocService.DocMeta::title)
                .findFirst()
                .orElse(slug);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", slug);
        payload.put("title", title);
        payload.put("content", body);
        return R.ok(payload);
    }

    private String normalizeLang(String lang) {
        if (lang == null) {
            return "zh";
        }
        String l = lang.toLowerCase();
        // 前端 locale 形如 zh-CN / en-US，取主语言段。
        if (l.startsWith("en")) {
            return "en";
        }
        return "zh";
    }
}
