package vip.mate.tool.guard.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.model.*;

import java.util.List;

/**
 * 策略解析器
 * <p>
 * 将 Guardian 产出的 findings 映射为最终裁决（GuardDecision）。
 * <ul>
 *   <li>Guardian 只负责发现风险事实</li>
 *   <li>PolicyResolver 负责把事实映射为执行策略</li>
 * </ul>
 * 聚合规则（取最严格者）：
 * <ol>
 *   <li>遍历每条 finding，得到该 finding 的目标 decision：</li>
 *   <ul>
 *     <li>若 finding 显式带 decision（来自 DB 规则），用之；</li>
 *     <li>否则按 severity 回退：CRITICAL→BLOCK，HIGH/MEDIUM→NEEDS_APPROVAL，LOW/INFO→ALLOW。</li>
 *   </ul>
 *   <li>取所有 finding 中最严格的：BLOCK &gt; NEEDS_APPROVAL &gt; ALLOW。</li>
 * </ol>
 */
@Slf4j
@Component
public class ToolPolicyResolver {

    public GuardDecision resolve(List<GuardFinding> findings, ToolInvocationContext context) {
        if (findings == null || findings.isEmpty()) {
            return GuardDecision.ALLOW;
        }

        GuardDecision aggregate = GuardDecision.ALLOW;
        for (GuardFinding f : findings) {
            GuardDecision perFinding = resolveSingle(f);
            aggregate = stricter(aggregate, perFinding);
            if (aggregate == GuardDecision.BLOCK) {
                return aggregate;
            }
        }
        return aggregate;
    }

    /**
     * 单条 finding 的目标 decision：显式优先，否则按 severity 默认映射
     */
    private GuardDecision resolveSingle(GuardFinding finding) {
        if (finding.decision() != null) {
            return finding.decision();
        }
        GuardSeverity sev = finding.severity();
        if (sev == null) {
            return GuardDecision.ALLOW;
        }
        if (sev.isAtLeast(GuardSeverity.CRITICAL)) {
            return GuardDecision.BLOCK;
        }
        if (sev.isAtLeast(GuardSeverity.MEDIUM)) {
            return GuardDecision.NEEDS_APPROVAL;
        }
        return GuardDecision.ALLOW;
    }

    /**
     * 取两个 decision 的严格上界：BLOCK > NEEDS_APPROVAL > ALLOW
     */
    private GuardDecision stricter(GuardDecision a, GuardDecision b) {
        if (a == GuardDecision.BLOCK || b == GuardDecision.BLOCK) {
            return GuardDecision.BLOCK;
        }
        if (a == GuardDecision.NEEDS_APPROVAL || b == GuardDecision.NEEDS_APPROVAL) {
            return GuardDecision.NEEDS_APPROVAL;
        }
        return GuardDecision.ALLOW;
    }

    /**
     * 构建人类可读的摘要
     */
    public String buildSummary(List<GuardFinding> findings, GuardDecision decision) {
        if (findings == null || findings.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("检测到 ").append(findings.size()).append(" 项安全风险");

        findings.stream()
                .filter(f -> f.severity() != null && f.severity().isAtLeast(GuardSeverity.MEDIUM))
                .limit(3)
                .forEach(f -> sb.append("\n- [").append(f.severity().name()).append("] ").append(f.title()));

        if (findings.size() > 3) {
            sb.append("\n- ... 及其他 ").append(findings.size() - 3).append(" 项");
        }

        return sb.toString();
    }
}
