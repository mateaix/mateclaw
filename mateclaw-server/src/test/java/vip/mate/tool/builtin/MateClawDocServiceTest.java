package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 针对内置文档服务的单元测试。直接读 classpath 上打包的真实文档
 * （src/main/resources/docs/{zh,en}/），不需要额外测试资源。
 */
class MateClawDocServiceTest {

    private final MateClawDocService service = new MateClawDocService();

    @Test
    @DisplayName("list(zh) 返回文档且排除 VitePress 首页 index.md")
    void listExcludesIndex() {
        List<MateClawDocService.DocMeta> docs = service.list("zh");

        assertThat(docs).isNotEmpty();
        assertThat(docs).noneMatch(d -> d.slug().equals("index"));
        // config.md 一定存在，且标题取的是中文 H1 而非文件名。
        assertThat(docs)
                .filteredOn(d -> d.slug().equals("config"))
                .singleElement()
                .satisfies(d -> assertThat(d.title()).isNotBlank().isNotEqualTo("config"));
    }

    @Test
    @DisplayName("list 对非法语言返回空")
    void listRejectsInvalidLang() {
        assertThat(service.list("fr")).isEmpty();
        assertThat(service.list("../zh")).isEmpty();
        assertThat(service.list(null)).isEmpty();
    }

    @Test
    @DisplayName("read 剥离开头的 YAML frontmatter")
    void readStripsFrontmatter() {
        // wiki.md 带 frontmatter（title/description/head）。
        String body = service.read("zh", "wiki");

        assertThat(body).isNotNull();
        assertThat(body.stripLeading()).doesNotStartWith("---");
        // `name: keywords` 只出现在 frontmatter 的 head meta 里，剥离后不应残留。
        assertThat(body).doesNotContain("name: keywords");
    }

    @Test
    @DisplayName("read 拒绝非法 slug / 路径穿越")
    void readRejectsInvalidSlug() {
        assertThat(service.read("zh", "../application")).isNull();
        assertThat(service.read("zh", "config.md")).isNull();
        assertThat(service.read("zh", "a/b")).isNull();
        assertThat(service.read("fr", "config")).isNull();
        assertThat(service.read("zh", "does-not-exist-xyz")).isNull();
    }

    @Test
    @DisplayName("readRawForTool 保留 frontmatter 并对非法路径返回错误串")
    void readRawForToolContract() {
        assertThat(service.readRawForTool("zh/config.md")).doesNotStartWith("Error:");
        assertThat(service.readRawForTool("../etc/passwd")).startsWith("Error:");
        assertThat(service.readRawForTool(null)).startsWith("Error:");
    }
}
