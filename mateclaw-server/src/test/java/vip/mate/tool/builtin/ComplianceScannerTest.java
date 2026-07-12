package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin {@link ComplianceScanner}: high-risk categories (极限词 / 诱导 / 承诺收益) are
 * flagged as high-risk so the publish path can hard-block them, medical-efficacy
 * is a non-high-risk hit, and clean copy scans clean.
 */
class ComplianceScannerTest {

    @Test
    @DisplayName("广告法 极限词 → high-risk hit")
    void adLawSuperlative() {
        ComplianceScanner.Result r = ComplianceScanner.scan("我们是全国第一、效果最好的品牌");
        assertFalse(r.clean());
        assertTrue(r.hasHighRisk());
        assertTrue(ComplianceScanner.report(r).contains("广告法极限词"));
    }

    @Test
    @DisplayName("WeChat 诱导 words → high-risk hit")
    void weChatInduce() {
        ComplianceScanner.Result r = ComplianceScanner.scan("集赞 20 个送礼品，分享到朋友圈解锁全文");
        assertTrue(r.hasHighRisk());
        assertTrue(ComplianceScanner.report(r).contains("微信诱导"));
    }

    @Test
    @DisplayName("promised returns → high-risk hit")
    void promisedReturns() {
        assertTrue(ComplianceScanner.scan("保本理财，稳赚不赔").hasHighRisk());
    }

    @Test
    @DisplayName("medical efficacy → hit but NOT high-risk")
    void medicalEfficacyNotHighRisk() {
        ComplianceScanner.Result r = ComplianceScanner.scan("这款茶能排毒养颜");
        assertFalse(r.clean());
        assertFalse(r.hasHighRisk(), "医疗功效 is a warning, not a hard block");
    }

    @Test
    @DisplayName("extra banned words merge in as a non-high-risk 自定义禁用词 category")
    void extraBannedWords() {
        ComplianceScanner.Result r = ComplianceScanner.scan(
                "这段文字提到了竞品X和内部代号Y", List.of("竞品X", "内部代号Y", "没出现的词"));
        assertFalse(r.clean());
        assertFalse(r.hasHighRisk(), "custom banned words are a warning, not a hard block");
        String rep = ComplianceScanner.report(r);
        assertTrue(rep.contains("自定义禁用词"));
        assertTrue(rep.contains("竞品X") && rep.contains("内部代号Y"));
        assertFalse(rep.contains("没出现的词"), "only actually-present terms are reported");
    }

    @Test
    @DisplayName("clean copy scans clean")
    void cleanCopy() {
        ComplianceScanner.Result r = ComplianceScanner.scan("这是我上周做的三道家常菜，步骤和用量都写清楚了。");
        assertTrue(r.clean());
        assertTrue(ComplianceScanner.report(r).contains("未命中"));
    }
}
