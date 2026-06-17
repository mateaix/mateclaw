package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for broken-link computation, focused on the slug-or-title
 * resolution contract that {@link WikiLinkService#resolvableTargetKeys} and
 * {@link WikiLinkService#computeBrokenLinks} jointly enforce.
 *
 * <p>The page viewer's {@code resolveWikilink} treats a {@code [[target]]} as
 * a hit when it matches either an existing page slug OR an existing page title.
 * The lint must agree, otherwise a {@code [[Page Title]]} reference to a real
 * page is rendered as a working link yet reported as a broken link — the
 * false-positive class this test pins down. The mismatch is most visible when
 * slugs are transliterated (a CJK title stored under a pinyin slug).
 */
class WikiLinkServiceBrokenLinkTest {

    private final WikiLinkService svc = new WikiLinkService(new ObjectMapper());

    private static WikiPageEntity page(String slug, String title) {
        WikiPageEntity p = new WikiPageEntity();
        p.setSlug(slug);
        p.setTitle(title);
        return p;
    }

    /** Mirrors the real KB in issue #333: Chinese titles, pinyin slugs. */
    private List<WikiPageEntity> cjkKb() {
        return List.of(
                page("guanghe-zuoyong", "光合作用"),
                page("xianliti", "线粒体"),
                page("yelvti", "叶绿体"),
                page("nengliang-daixie", "能量代谢"),
                page("energy-metabolism", "Energy Metabolism"));
    }

    @Test
    void resolvableKeysCarryBothSlugAndTitle() {
        Set<String> keys = svc.resolvableTargetKeys(cjkKb());
        assertTrue(keys.contains("guanghe-zuoyong"), "slug must be a key");
        assertTrue(keys.contains("光合作用"), "title must be a key");
        assertTrue(keys.contains("energy-metabolism"));
        assertTrue(keys.contains("energy metabolism"), "title lowercased, spaces kept");
    }

    @Test
    void titleFormLinkToExistingCjkPageIsNotBroken() {
        // The model naturally writes the readable title, not the pinyin slug.
        String content = "参见 [[光合作用]] 与 [[线粒体]]。";
        WikiLinkService.LinkAnalysis a = svc.analyze(content, svc.resolvableTargetKeys(cjkKb()));
        assertTrue(a.brokenLinks().isEmpty(),
                "title-form links to existing pages must resolve, got: " + a.brokenLinks());
    }

    @Test
    void slugFormLinkIsNotBroken() {
        String content = "See [[guanghe-zuoyong]] and [[energy-metabolism]].";
        WikiLinkService.LinkAnalysis a = svc.analyze(content, svc.resolvableTargetKeys(cjkKb()));
        assertTrue(a.brokenLinks().isEmpty(), "slug-form links must resolve, got: " + a.brokenLinks());
    }

    @Test
    void englishTitleWithSpacesResolvesAgainstTitleNotSlug() {
        // slug is "energy-metabolism" (dashed); the title has a space. Only the
        // title key can match the [[Energy Metabolism]] target.
        String content = "Read [[Energy Metabolism]] first.";
        WikiLinkService.LinkAnalysis a = svc.analyze(content, svc.resolvableTargetKeys(cjkKb()));
        assertTrue(a.brokenLinks().isEmpty(), "title-with-space must resolve, got: " + a.brokenLinks());
    }

    @Test
    void aliasFormResolvesOnTitleTarget() {
        String content = "更多见 [[线粒体|线粒体别名]]。";
        WikiLinkService.LinkAnalysis a = svc.analyze(content, svc.resolvableTargetKeys(cjkKb()));
        assertTrue(a.brokenLinks().isEmpty(), "aliased title link must resolve, got: " + a.brokenLinks());
    }

    @Test
    void genuinelyMissingTargetIsStillBroken() {
        String content = "悬挂引用 [[不存在的概念XYZ]] 应当被标记。";
        WikiLinkService.LinkAnalysis a = svc.analyze(content, svc.resolvableTargetKeys(cjkKb()));
        assertEquals(List.of("不存在的概念xyz"), a.brokenLinks(),
                "a target matching no slug and no title must be broken");
    }

    @Test
    void mixedContentReportsOnlyTheGenuineBreak() {
        // Reproduces the issue scenario across several markdown shapes: only the
        // hallucinated target is broken; the four title-form links resolve.
        String content = String.join("\n",
                "# 标题 [[能量代谢]]",
                "段落：[[光合作用]] 与 [[guanghe-zuoyong]] 指向同一页。",
                "- 列表：[[线粒体|别名]]",
                "> 引用：[[叶绿体]]",
                "悬挂：[[未知页面ABC]]");
        WikiLinkService.LinkAnalysis a = svc.analyze(content, svc.resolvableTargetKeys(cjkKb()));
        assertEquals(List.of("未知页面abc"), a.brokenLinks(),
                "only the hallucinated target is broken, got: " + a.brokenLinks());
    }

    @Test
    void linksInsideCodeAreNeitherOutgoingNorBroken() {
        String content = String.join("\n",
                "行内 `[[光合作用]]` 不计入。",
                "```",
                "围栏 [[不存在XYZ]] 不计入",
                "```");
        WikiLinkService.LinkAnalysis a = svc.analyze(content, svc.resolvableTargetKeys(cjkKb()));
        assertTrue(a.outgoingLinks().isEmpty(), "code-block links must be ignored, got: " + a.outgoingLinks());
        assertTrue(a.brokenLinks().isEmpty(), "code-block links must not be broken, got: " + a.brokenLinks());
    }

    @Test
    void emptyKbMakesEveryLinkBroken() {
        String content = "[[anything]]";
        WikiLinkService.LinkAnalysis a = svc.analyze(content, svc.resolvableTargetKeys(List.of()));
        assertEquals(List.of("anything"), a.brokenLinks());
    }

    @Test
    void computeBrokenLinksMatchesAgainstTitleKeys() {
        Set<String> keys = svc.resolvableTargetKeys(cjkKb());
        // direct unit on the predicate: "光合作用" is a title key → resolvable
        assertFalse(svc.computeBrokenLinks(Set.of("光合作用"), keys).contains("光合作用"));
        assertTrue(svc.computeBrokenLinks(Set.of("missing"), keys).contains("missing"));
    }

    // ── reconcileLinks: post-ingestion redirect / demote of dangling links ──

    /** Reconciler mirroring the production rule: keep resolvable, redirect via
     *  alias to a covering page, else demote to plain text. */
    private WikiLinkService.LinkReconciler reconciler(Set<String> resolvable,
                                                      java.util.Map<String, String> aliasToSlug) {
        return (target, alias) -> {
            String key = target.trim().toLowerCase();
            if (resolvable.contains(key)) return null;
            String display = (alias != null && !alias.isBlank()) ? alias : target;
            String cover = aliasToSlug.get(key);
            if (cover != null) return "[[" + cover + "|" + display + "]]";
            return display;
        };
    }

    @Test
    void reconcileKeepsResolvableLinks() {
        Set<String> resolvable = Set.of("光合作用", "guanghe-zuoyong");
        String in = "见 [[光合作用]] 与 [[guanghe-zuoyong]]。";
        String out = svc.reconcileLinks(in, reconciler(resolvable, java.util.Map.of()));
        assertEquals(in, out, "resolvable links must be left untouched");
    }

    @Test
    void reconcileRedirectsAliasToCoveringPage() {
        Set<String> resolvable = Set.of("细胞器术语辨析");
        var aliasMap = java.util.Map.of("叶绿体", "细胞器术语辨析");
        String out = svc.reconcileLinks("叶绿体见 [[叶绿体]]。", reconciler(resolvable, aliasMap));
        assertEquals("叶绿体见 [[细胞器术语辨析|叶绿体]]。", out,
                "an alias-covered link must redirect to the covering page, keeping a readable label");
    }

    @Test
    void reconcileDemotesUncoveredDanglingToPlainText() {
        Set<String> resolvable = Set.of("细胞器术语辨析");
        String out = svc.reconcileLinks("讲到 [[不存在的概念]] 和 [[未知|别名显示]]。",
                reconciler(resolvable, java.util.Map.of()));
        assertEquals("讲到 不存在的概念 和 别名显示。", out,
                "uncovered links demote to plain text, honouring the alias display when present");
    }

    @Test
    void reconcileLeavesCodeBlocksUntouched() {
        String in = "正文 [[不存在]] 降级。\n\n```\n代码里的 [[不存在]] 保留\n```\n行内 `[[不存在]]` 保留。";
        String out = svc.reconcileLinks(in, reconciler(Set.of(), java.util.Map.of()));
        assertTrue(out.startsWith("正文 不存在 降级。"), "narrative link demoted, got: " + out);
        assertTrue(out.contains("代码里的 [[不存在]] 保留"), "fenced code must be preserved");
        assertTrue(out.contains("`[[不存在]]`"), "inline code must be preserved");
    }
}
