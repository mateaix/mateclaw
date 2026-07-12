package vip.mate.tool.builtin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side hard compliance scan for content bound for 公众号 / 小红书.
 * Model-side self-checks (skills) can be skipped or hallucinated; this is a
 * deterministic backstop that publish paths can enforce.
 *
 * <p>Four categories, roughly ordered by account risk:
 * <ul>
 *   <li>{@code 广告法极限词} — 绝对化用语（最/第一/唯一/国家级/100%…）</li>
 *   <li>{@code 微信诱导} — 诱导分享/关注（集赞/助力/分享解锁/关注才能看…）— WeChat's most account-fatal rule</li>
 *   <li>{@code 承诺收益/效果} — 保本/稳赚/包过/根治…</li>
 *   <li>{@code 医疗功效} — 治愈/抗癌/包瘦/排毒…</li>
 * </ul>
 * The first three are treated as high-risk (a publish path may block on them).
 */
final class ComplianceScanner {

    private ComplianceScanner() {
    }

    /** Category name → matching pattern. Ordered by severity for stable output. */
    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    /** Categories that a publish path should hard-block on. */
    private static final List<String> HIGH_RISK = List.of("广告法极限词", "微信诱导", "承诺收益/效果");

    static {
        RULES.put("广告法极限词", Pattern.compile(
                "最佳|最好|最优|最强|最高级|最便宜|最先进|最顶级|第一品牌|全国第一|全球第一"
                + "|唯一|独家|首个|首选|冠军|领导品牌|国家级|世界级|国际级|顶级|极致"
                + "|100%|百分百|绝对|彻底根治|永久|包治|一劳永逸"));
        RULES.put("微信诱导", Pattern.compile(
                "集赞|助力|砍一刀|分享到朋友圈|分享后解锁|分享解锁|分享可见|转发抽奖|转发领取"
                + "|不转不是|关注才能看|关注才可见|关注领取|关注解锁|扫码加个人微信|加我微信领"));
        RULES.put("承诺收益/效果", Pattern.compile(
                "保本|稳赚|稳赚不赔|保收益|保底收益|包赚|躺赚|一夜暴富"
                + "|包过|保过|保分|名校保录|包录取|包就业|包瘦身"));
        RULES.put("医疗功效", Pattern.compile(
                "治愈|根治|抗癌|防癌|包瘦|排毒|壮阳|丰胸|生发防脱|药到病除|无副作用"));
    }

    /** One category's hits. */
    record CategoryHit(String category, List<String> terms, boolean highRisk) {}

    /** Full scan result. */
    record Result(List<CategoryHit> hits) {
        boolean clean() {
            return hits.isEmpty();
        }

        boolean hasHighRisk() {
            return hits.stream().anyMatch(CategoryHit::highRisk);
        }
    }

    /** Scan text for policy violations across all categories. */
    static Result scan(String text) {
        List<CategoryHit> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new Result(hits);
        }
        for (Map.Entry<String, Pattern> rule : RULES.entrySet()) {
            List<String> terms = new ArrayList<>();
            Matcher m = rule.getValue().matcher(text);
            while (m.find()) {
                String term = m.group();
                if (!terms.contains(term)) {
                    terms.add(term);
                }
            }
            if (!terms.isEmpty()) {
                hits.add(new CategoryHit(rule.getKey(), terms, HIGH_RISK.contains(rule.getKey())));
            }
        }
        return new Result(hits);
    }

    /**
     * Scan with additional user-supplied banned words merged in as a
     * (non-high-risk) {@code 自定义禁用词} category — e.g. the user's
     * {@code banned_words} memory or brand-forbidden terms.
     */
    static Result scan(String text, Collection<String> extraTerms) {
        Result base = scan(text);
        if (extraTerms == null || extraTerms.isEmpty() || text == null || text.isBlank()) {
            return base;
        }
        List<String> hitTerms = new ArrayList<>();
        for (String t : extraTerms) {
            if (t == null) {
                continue;
            }
            String term = t.trim();
            if (!term.isEmpty() && text.contains(term) && !hitTerms.contains(term)) {
                hitTerms.add(term);
            }
        }
        if (hitTerms.isEmpty()) {
            return base;
        }
        List<CategoryHit> all = new ArrayList<>(base.hits());
        all.add(new CategoryHit("自定义禁用词", hitTerms, false));
        return new Result(all);
    }

    /** Render a scan result as a short Chinese report. */
    static String report(Result result) {
        if (result.clean()) {
            return "✅ 合规扫描：未命中极限词 / 诱导词 / 承诺收益 / 功效违禁词。";
        }
        StringBuilder sb = new StringBuilder("⚠️ 合规扫描命中：\n");
        for (CategoryHit h : result.hits()) {
            sb.append("- [").append(h.category()).append(h.highRisk() ? " · 高危" : "")
              .append("] ").append(String.join("、", h.terms())).append('\n');
        }
        sb.append(result.hasHighRisk()
                ? "含高危词，发布前必须替换（尤其微信诱导词，易限流/封号）。"
                : "建议替换后再发布。");
        return sb.toString();
    }
}
